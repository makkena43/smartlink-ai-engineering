package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The reviewer path, end to end: create, redirect, read usage.
 *
 * <p>Runs against the whole wiring — real HTTP, real controllers, real database — because the
 * things asserted here only exist once everything is assembled: response headers, status codes,
 * route precedence, and whether the counter actually moved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmartLinkEndToEndIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate rest;
  @LocalServerPort private int port;

  /** A client that does NOT follow redirects, so the 302 itself can be inspected. */
  private RestTemplate nonFollowing() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setOutputStreaming(false);
    RestTemplate template = new RestTemplate(factory);
    template.setErrorHandler(
        new org.springframework.web.client.DefaultResponseErrorHandler() {
          @Override
          public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
            return false;
          }
        });
    return template;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> createLink(String destination) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> response =
        rest.postForEntity(
            "/api/v1/links",
            new HttpEntity<>(Map.of("destinationUrl", destination), headers),
            Map.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return response.getBody();
  }

  private ResponseEntity<Void> follow(String code) {
    return nonFollowing().exchange(url("/" + code), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("create, redirect, read usage — the whole reviewer path")
  void createRedirectAndReadUsage() {
    String destination = "https://example.com/campaign?utm_source=e2e&x=%2F#frag";

    Map<String, Object> created = createLink(destination);
    String code = (String) created.get("code");

    assertThat(code).matches("[A-Za-z0-9]{7}");
    assertThat(created.get("shortUrl")).asString().endsWith("/" + code);
    assertThat(created.get("destinationUrl")).isEqualTo(destination);

    ResponseEntity<Void> redirect = follow(code);
    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(redirect.getHeaders().getFirst(HttpHeaders.LOCATION))
        .as("byte-identical, through the full stack")
        .isEqualTo(destination);
    assertThat(redirect.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> analytics =
        rest.getForEntity("/api/v1/links/" + code + "/analytics", Map.class);
    assertThat(analytics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) analytics.getBody().get("totalRedirects")).longValue()).isEqualTo(1L);
  }

  @Test
  @DisplayName("the same destination twice yields two independent links (GF-04)")
  void sameDestinationYieldsTwoLinks() {
    String destination = "https://example.com/repeat";

    assertThat(createLink(destination).get("code"))
        .isNotEqualTo(createLink(destination).get("code"));
  }

  @Test
  @DisplayName("analytics response carries no personal data (NFR-13)")
  void analyticsCarriesNoPersonalData() {
    String code = (String) createLink("https://example.com/privacy").get("code");

    String body = rest.getForEntity("/api/v1/links/" + code + "/analytics", String.class).getBody();

    assertThat(body)
        .doesNotContain("ip")
        .doesNotContain("userAgent")
        .doesNotContain("referrer")
        .doesNotContain("country")
        .doesNotContain("device");
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("an unknown code returns 404 and does not redirect (GF-09)")
  void unknownCodeReturns404() {
    ResponseEntity<Void> response = follow("zzzzzzz");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();
  }

  @Test
  @DisplayName("a malformed code returns 404, not 400 — no probing oracle")
  void malformedCodeReturns404() {
    assertThat(follow("short").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(follow("waytoolongforacode").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "javascript:alert(1)",
        "file:///etc/passwd",
        "http://169.254.169.254/latest/meta-data/",
        "http://127.0.0.1:8080/",
        "http://10.0.0.1/",
        "http://2852039166/",
        "http://0xA9FEA9FE/",
        "http://expected.com@169.254.169.254/",
        "https://example.com/%0d%0aX-Injected:%20yes"
      })
  @DisplayName("refused destinations are refused through the full stack (GF-14…GF-18)")
  void refusedDestinationsAreRefused(String destination) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/links",
            new HttpEntity<>(Map.of("destinationUrl", destination), headers),
            String.class);

    assertThat(response.getStatusCode())
        .as("%s must be refused as a policy violation, not accepted", destination)
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody())
        .as("the error must not reflect the submitted value")
        .doesNotContain("alert(1)")
        .doesNotContain("X-Injected");
  }

  @Test
  @DisplayName("errors carry a correlation id and no implementation detail")
  void errorsAreSafeAndCorrelated() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/links",
            new HttpEntity<>(Map.of("destinationUrl", "javascript:alert(1)"), headers),
            String.class);

    assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    assertThat(response.getBody())
        .contains("\"code\":\"INVALID_URL\"")
        .contains("\"rule\":\"destination.scheme\"")
        .contains("requestId")
        .doesNotContain("Exception")
        .doesNotContain("com.smartlink")
        .doesNotContain("jdbc");
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("concurrent redirects of one link lose no counts")
  void concurrentRedirectsLoseNoCounts() throws Exception {
    String code = (String) createLink("https://example.com/viral").get("code");

    int threads = 12;
    int perThread = 10;
    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    try {
      List<Callable<Void>> work =
          IntStream.range(0, threads)
              .<Callable<Void>>mapToObj(
                  i ->
                      () -> {
                        startLine.await();
                        RestTemplate client = nonFollowing();
                        for (int n = 0; n < perThread; n++) {
                          client.exchange(
                              url("/" + code), HttpMethod.GET, HttpEntity.EMPTY, Void.class);
                        }
                        return null;
                      })
              .toList();

      List<Future<Void>> futures = work.stream().map(pool::submit).toList();
      startLine.countDown();
      for (Future<Void> future : futures) {
        future.get(120, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> analytics =
        rest.getForEntity("/api/v1/links/" + code + "/analytics", Map.class);

    // 120 concurrent hits on one row, through the whole stack. Fails under a
    // read-modify-write counter (lost updates) and under an @Version-guarded one
    // (collisions), which is what makes it the end-to-end form of the T3 constraint.
    assertThat(((Number) analytics.getBody().get("totalRedirects")).longValue())
        .isEqualTo((long) threads * perThread);
  }

  @Test
  @DisplayName("operational routes are never shadowed by the redirect handler (GF-16)")
  void operationalRoutesAreNotShadowed() {
    Set<String> reachable = ConcurrentHashMap.newKeySet();
    for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/v3/api-docs")) {
      if (rest.getForEntity(path, String.class).getStatusCode() == HttpStatus.OK) {
        reachable.add(path);
      }
    }

    assertThat(reachable).hasSize(3);
  }
}
