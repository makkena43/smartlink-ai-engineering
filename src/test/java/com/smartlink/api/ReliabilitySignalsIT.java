package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.NonFollowingClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * A4 — the reliability signals in spec §4.1 are actually collectible (R-5).
 *
 * <p>The failure this guards against is a documentation failure rather than a code one. An SLO
 * table is easy to write and impossible to honour if nothing emits the numbers it is defined over;
 * the result reads as rigour and delivers nothing, which is worse than an admitted gap because it
 * stops anyone from looking. These tests assert the data exists and can be split the way the SLI
 * definitions require.
 *
 * <p>It also asserts the two things that adding a metrics endpoint could plausibly break: the
 * actuator surface must stay narrow, and metrics must not become a side channel for the destination
 * URLs that NFR-14 keeps out of the logs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Reliability signals (R-5)")
class ReliabilitySignalsIT extends AbstractPostgresIT {

  @LocalServerPort private int port;

  private final RestTemplate http = NonFollowingClient.create();

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private String createLink(String destination) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> created =
        http.exchange(
            url("/api/v1/links"),
            HttpMethod.POST,
            new HttpEntity<>("{\"destinationUrl\":\"" + destination + "\"}", headers),
            Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) created.getBody().get("code");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> metric(String name) {
    ResponseEntity<Map> response =
        http.exchange(url("/actuator/metrics/" + name), HttpMethod.GET, null, Map.class);
    assertThat(response.getStatusCode())
        .as("metric %s must be published", name)
        .isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  @Test
  @DisplayName("request metrics exist and carry the tags the SLI definitions are split by")
  void requestMetricsSupportTheSlis() {
    String code = createLink("https://example.com/signals");
    http.exchange(url("/" + code), HttpMethod.GET, null, String.class); // a 302
    http.exchange(url("/AAAAAAA"), HttpMethod.GET, null, String.class); // a 404

    Map<String, Object> requests = metric("http.server.requests");
    List<Map<String, Object>> tags = (List<Map<String, Object>>) requests.get("availableTags");
    List<String> tagNames = tags.stream().map(t -> (String) t.get("tag")).toList();

    // Spec §4.1 defines resolve availability as 302 plus expected 404/410 over all completed
    // resolve requests, and latency split by outcome. Both need status and uri to be tags, not
    // just a total count — an aggregate number cannot be split after the fact.
    assertThat(tagNames).contains("status", "uri", "outcome");

    List<String> statuses =
        tags.stream()
            .filter(t -> "status".equals(t.get("tag")))
            .flatMap(t -> ((List<String>) t.get("values")).stream())
            .toList();
    assertThat(statuses).contains("302", "404");
  }

  @Test
  @DisplayName("the analytics-write-failure counter is registered, so degraded counts are visible")
  void analyticsWriteFailureCounterIsPublished() {
    // Registered at construction rather than on first failure, so an operator can build a
    // dashboard before an incident rather than during one.
    Map<String, Object> counter = metric("smartlink.analytics.write.failures");
    assertThat((String) counter.get("name")).isEqualTo("smartlink.analytics.write.failures");
  }

  @Test
  @DisplayName("metrics do not leak destination URLs — the URI tag is the template, not the target")
  void metricsDoNotLeakDestinations() {
    String secret = "https://example.com/reset?token=metrics-must-not-carry-this";
    String code = createLink(secret);
    http.exchange(url("/" + code), HttpMethod.GET, null, String.class);

    ResponseEntity<String> raw =
        http.exchange(
            url("/actuator/metrics/http.server.requests"), HttpMethod.GET, null, String.class);

    // NFR-14 keeps destinations out of logs. A metrics endpoint that tagged by full request or
    // destination URL would reopen exactly that exposure, in a place nobody thinks to check —
    // and would also produce unbounded cardinality, which is how a metrics backend falls over.
    assertThat(raw.getBody()).doesNotContain("metrics-must-not-carry-this");
    assertThat(raw.getBody()).doesNotContain(code);
  }

  @Test
  @DisplayName("adding metrics did not widen the actuator surface")
  void actuatorSurfaceIsStillNarrow() {
    for (String endpoint :
        List.of("env", "beans", "configprops", "mappings", "loggers", "heapdump", "threaddump")) {
      ResponseEntity<String> response =
          http.exchange(url("/actuator/" + endpoint), HttpMethod.GET, null, String.class);
      assertThat(response.getStatusCode())
          .as("/actuator/%s must not be exposed", endpoint)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
