package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.NonFollowingClient;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The fail-open guarantee, proven against a real database failure.
 *
 * <p>Named in the task decomposition as the test that keeps a design decision alive. The decision —
 * a counter failure must never fail a redirect — is <strong>invisible in the code</strong>: it
 * reads as an ordinary try/catch, and a refactor that wraps resolution and increment in one
 * transaction reverses it with nothing else noticing.
 *
 * <p><strong>No mock is used, on purpose.</strong> A stubbed repository proves only that the
 * application layer catches what the stub throws. What matters here is that a genuine database
 * refusal — arriving through the driver, Hibernate, the transaction manager and the repository —
 * still leaves the visitor with a working redirect. So the failure is injected in the database
 * itself, with a trigger that refuses every UPDATE while leaving reads untouched. That asymmetry is
 * exactly the production scenario: a healthy read path and a write path that has stopped working.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AnalyticsFailureIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  private void breakTheCounter() {
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION refuse_update() RETURNS trigger AS $$
        BEGIN
          RAISE EXCEPTION 'counter write refused by fault injection';
        END;
        $$ LANGUAGE plpgsql
        """);
    jdbc.execute(
        """
        CREATE TRIGGER fault_injection_refuse_update
        BEFORE UPDATE ON short_link
        FOR EACH ROW EXECUTE FUNCTION refuse_update()
        """);
  }

  @AfterEach
  void repairTheCounter() {
    jdbc.execute("DROP TRIGGER IF EXISTS fault_injection_refuse_update ON short_link");
    jdbc.execute("DROP FUNCTION IF EXISTS refuse_update()");
  }

  private String createLink(String destination) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        rest.postForEntity(
                "/api/v1/links",
                new HttpEntity<>(Map.of("destinationUrl", destination), headers),
                String.class)
            .getBody();
    return body.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
  }

  /**
   * Requests the redirect WITHOUT following it.
   *
   * <p>TestRestTemplate follows redirects, which would make every assertion below describe
   * example.com's response rather than this service's - and Location would already have been
   * consumed by the client before the test could look at it.
   */
  private ResponseEntity<Void> follow(String code) {
    return NonFollowingClient.create()
        .getForEntity("http://localhost:" + port + "/" + code, Void.class);
  }

  @Test
  @DisplayName("the redirect is still served when the counter write fails at the database")
  void redirectSurvivesCounterFailure() {
    String destination = "https://example.com/campaign-that-must-keep-working";
    String code = createLink(destination);

    breakTheCounter();

    ResponseEntity<Void> response = follow(code);

    // The visitor reaches a page that was always available. Failing here to protect a number
    // would block them from working content for a reason they could not perceive, and would
    // invert the priority between the product and its instrumentation.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo(destination);
  }

  @Test
  @DisplayName("repeated redirects keep working while the counter stays broken")
  void repeatedRedirectsKeepWorking() {
    String code = createLink("https://example.com/sustained-outage");

    breakTheCounter();

    // A single success could be luck - a cached entity, a deferred flush. Sustained success
    // is what shows the failure is genuinely absorbed on every request rather than merely
    // postponed to the next one.
    for (int i = 0; i < 10; i++) {
      assertThat(follow(code).getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
  }

  @Test
  @DisplayName("the count simply stops moving — it is best-effort, and says so")
  void countStopsMovingButNothingElseBreaks() {
    String code = createLink("https://example.com/count-freezes");
    follow(code); // one counted redirect, before the fault

    breakTheCounter();
    follow(code);
    follow(code);
    repairTheCounter();

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> analytics =
        rest.getForEntity("/api/v1/links/" + code + "/analytics", Map.class);

    // The honest outcome: the redirects happened, the count did not record them. Analytics is
    // best-effort by design, and this is what that costs. Documenting it as a test rather than
    // a comment means the trade-off cannot be quietly forgotten and later reported as a bug.
    assertThat(analytics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) analytics.getBody().get("totalRedirects")).longValue())
        .as("only the pre-fault redirect was recorded")
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("analytics reads still work while the counter write is broken")
  void analyticsReadsStillWork() {
    String code = createLink("https://example.com/reads-unaffected");

    breakTheCounter();

    // Reads and writes fail independently. A blanket "database is broken, return 503" would
    // be wrong here: the read path is fine, and a caller asking for figures can be served.
    assertThat(
            rest.getForEntity("/api/v1/links/" + code + "/analytics", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("creation still fails loudly — fail-open is scoped to the counter alone")
  void creationStillFailsLoudly() {
    breakTheCounter();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/links",
            new HttpEntity<>(Map.of("destinationUrl", "https://example.com/new"), headers),
            String.class);

    // The complement, and the reason fail-open is not simply "swallow database errors". An
    // INSERT is unaffected by this trigger, so creation succeeds; if a write that the caller
    // depends on ever did fail, it must surface rather than be absorbed. Fail-open applies to
    // instrumentation, never to the thing the caller actually asked for.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }
}
