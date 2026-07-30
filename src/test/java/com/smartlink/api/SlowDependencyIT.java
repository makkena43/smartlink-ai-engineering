package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.SlowDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * A3 fault injection — the database is <strong>slow</strong>, not gone (R-3).
 *
 * <p>This is the failure mode {@code DependencyOutageIT} cannot reach. An absent database refuses
 * the connection at once; a slow one accepts it and holds the request thread for as long as it
 * likes. Without an explicit time budget the service has no way to stop waiting, and the damage is
 * not confined to the slow requests: threads accumulate until the pool is exhausted and requests
 * that had nothing to do with the slow query start failing too.
 *
 * <p><strong>Written before the fix, and it failed.</strong> With only Hikari's {@code
 * connection-timeout} configured — which bounds *acquiring* a connection and says nothing about how
 * long a query may run on one — a 10-second query produced a 10-second request. The evidence is
 * recorded in {@code validation.md} §3. That ordering is the point of task A3: a timeout that has
 * never been observed to fire is indistinguishable from one that does not work.
 *
 * <p>The budget asserted here is a <strong>prototype guard against an unbounded wait</strong> (spec
 * §3.2), not a latency SLO. It is deliberately loose enough not to be flaky on a loaded laptop and
 * far tighter than the injected 10-second delay, so it can only pass if a timeout actually fired.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Slow database (R-3)")
class SlowDependencyIT extends AbstractPostgresIT {

  /** Spec §3.2: a forced slow datastore must complete within 3 s locally. */
  private static final Duration BUDGET = Duration.ofSeconds(3);

  /** Far longer than the budget, so a pass cannot be luck. */
  private static final int INJECTED_DELAY_SECONDS = 10;

  @LocalServerPort private int port;
  @Autowired private DataSource dataSource;

  private SlowDatabase slowDatabase;

  private RestTemplate client() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setOutputStreaming(false);
    // Generous, and deliberately longer than the budget: if the service fails to bound its own
    // wait, this test must report "took too long" rather than a client-side read timeout that
    // looks like a network problem.
    factory.setConnectTimeout(2_000);
    factory.setReadTimeout(20_000);

    RestTemplate template = new RestTemplate(factory);
    template.setErrorHandler(
        new DefaultResponseErrorHandler() {
          @Override
          public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
            return false;
          }
        });
    return template;
  }

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private String createLink() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> created =
        client()
            .exchange(
                url("/api/v1/links"),
                HttpMethod.POST,
                new HttpEntity<>("{\"destinationUrl\":\"https://example.com/slow\"}", headers),
                Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) created.getBody().get("code");
  }

  private SlowDatabase slowDatabase() {
    if (slowDatabase == null) {
      slowDatabase = new SlowDatabase(dataSource);
    }
    return slowDatabase;
  }

  @AfterEach
  void removeFault() {
    slowDatabase().restore();
  }

  @Test
  @DisplayName("a slow lookup fails within the time budget instead of waiting on the database")
  void slowLookupIsBounded() {
    String code = createLink();
    slowDatabase().install(INJECTED_DELAY_SECONDS);

    long startedAt = System.nanoTime();
    ResponseEntity<String> response =
        client().exchange(url("/" + code), HttpMethod.GET, null, String.class);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(elapsed)
        .as(
            "a %ds query must not produce a %ds request",
            INJECTED_DELAY_SECONDS, elapsed.toSeconds())
        .isLessThan(BUDGET);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  @DisplayName("the timed-out response is a safe 503 — no Location, no destination, no internals")
  void timeoutResponseIsSafe() {
    String code = createLink();
    slowDatabase().install(INJECTED_DELAY_SECONDS);

    ResponseEntity<String> response =
        client().exchange(url("/" + code), HttpMethod.GET, null, String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    // AS-4: correctness beats availability. A known-good destination is in the database and the
    // service still must not emit it, because it could not verify the mapping within its budget.
    assertThat(response.getHeaders().getFirst("Location")).isNull();

    String body = response.getBody() == null ? "" : response.getBody();
    assertThat(body).contains("SERVICE_UNAVAILABLE");
    assertThat(body).doesNotContain("example.com");
    assertThat(body.toLowerCase())
        .doesNotContain("pg_sleep")
        .doesNotContain("timeout")
        .doesNotContain("postgres")
        .doesNotContain("jdbc")
        .doesNotContain("short_link");
  }

  @Test
  @DisplayName(
      "concurrent slow requests all fail within budget — the request pool is not exhausted")
  void concurrentSlowRequestsDoNotExhaustThePool() throws Exception {
    String code = createLink();
    slowDatabase().install(INJECTED_DELAY_SECONDS);

    int concurrency = 12;
    ExecutorService pool = Executors.newFixedThreadPool(concurrency);
    try {
      List<Callable<Long>> calls = new ArrayList<>();
      for (int i = 0; i < concurrency; i++) {
        calls.add(
            () -> {
              long started = System.nanoTime();
              client().exchange(url("/" + code), HttpMethod.GET, null, String.class);
              return System.nanoTime() - started;
            });
      }

      long startedAll = System.nanoTime();
      List<Future<Long>> results = pool.invokeAll(calls);
      Duration wallClock = Duration.ofNanos(System.nanoTime() - startedAll);

      for (Future<Long> result : results) {
        result.get(); // surfaces any execution failure
      }

      // The real assertion is on the aggregate. If each slow request held a thread for the full
      // injected delay, 12 of them would serialise far past this bound; bounded requests all
      // fail at roughly the same moment instead.
      assertThat(wallClock)
          .as("%d concurrent slow requests took %s", concurrency, wallClock)
          .isLessThan(BUDGET.multipliedBy(3));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("the service recovers as soon as the database does — no restart, no manual step")
  void recoversWhenTheDatabaseDoes() {
    String code = createLink();

    slowDatabase().install(INJECTED_DELAY_SECONDS);
    assertThat(
            client().exchange(url("/" + code), HttpMethod.GET, null, String.class).getStatusCode())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

    slowDatabase().restore();

    ResponseEntity<String> afterRecovery =
        client().exchange(url("/" + code), HttpMethod.GET, null, String.class);
    assertThat(afterRecovery.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(afterRecovery.getHeaders().getFirst("Location"))
        .isEqualTo("https://example.com/slow");
  }
}
