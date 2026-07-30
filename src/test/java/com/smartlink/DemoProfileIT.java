package com.smartlink;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.NonFollowingClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

/**
 * Runs the <strong>demo profile</strong> — H2, no Docker — end to end.
 *
 * <p><strong>Why this test exists.</strong> Every other integration test in this repository runs
 * against real PostgreSQL via Testcontainers, which is the right default: most of what they assert
 * <em>is</em> PostgreSQL behaviour. The consequence was a blind spot. The demo profile is the first
 * thing a reviewer without Docker runs, and it had no automated coverage whatsoever, so three
 * separate PostgreSQL-only constructs were shipped into it and none of the 252 passing tests could
 * see any of them:
 *
 * <ol>
 *   <li>{@code statement_timestamp()} in the redirect projection — H2 has no such function, so
 *       every redirect returned {@code 503}
 *   <li>{@code statement_timestamp()} again in the create-path clock — every create carrying an
 *       expiry returned {@code 503}
 *   <li>An interface projection declaring {@code Instant}, which the PostgreSQL driver satisfies
 *       via {@link java.sql.Timestamp} and H2 does not, throwing {@code
 *       UnsupportedOperationException} on the redirect path
 * </ol>
 *
 * <p>All three were found by starting the jar and calling it by hand. That is not a repeatable
 * guarantee, so this test is the guarantee: the demo path is now covered by the same suite as
 * everything else, and it needs no Docker to run.
 *
 * <p>This does <strong>not</strong> weaken the position that H2 proves little. It asserts one
 * narrow thing — that the demo profile is not broken — and deliberately does not re-test the
 * concurrency, locking or schema behaviour the PostgreSQL suite owns.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
@DisplayName("Demo profile (H2, no Docker)")
class DemoProfileIT {

  @LocalServerPort private int port;

  private final RestTemplate http = NonFollowingClient.create();

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  private ResponseEntity<Map> create(String json) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(
        url("/api/v1/links"), HttpMethod.POST, new HttpEntity<>(json, headers), Map.class);
  }

  @Test
  @DisplayName("creates a link and resolves it — the demo path works at all")
  void createAndResolve() {
    ResponseEntity<Map> created = create("{\"destinationUrl\":\"https://example.com/demo\"}");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String code = (String) created.getBody().get("code");
    ResponseEntity<String> redirect =
        http.exchange(url("/" + code), HttpMethod.GET, null, String.class);

    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(redirect.getHeaders().getFirst("Location")).isEqualTo("https://example.com/demo");
  }

  @Test
  @DisplayName("a link created with a future expiry still resolves — the create-path clock works")
  void futureExpiryResolves() {
    String future = Instant.now().plus(1, ChronoUnit.HOURS).toString();
    ResponseEntity<Map> created =
        create(
            "{\"destinationUrl\":\"https://example.com/later\",\"expiresAt\":\"" + future + "\"}");

    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String code = (String) created.getBody().get("code");
    ResponseEntity<String> redirect =
        http.exchange(url("/" + code), HttpMethod.GET, null, String.class);

    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
  }

  @Test
  @DisplayName("an expiry in the past is refused as INVALID_EXPIRY, not 503")
  void pastExpiryIsRefusedNotFailed() {
    ResponseEntity<Map> response =
        create(
            "{\"destinationUrl\":\"https://example.com/gone\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}");

    // 503 here would mean the clock query itself failed, which is exactly how this broke.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().get("code")).isEqualTo("INVALID_EXPIRY");
  }

  @Test
  @DisplayName("an expired link returns 410 with no Location — the read-path clock works")
  void expiredLinkIsGone() throws InterruptedException {
    String soon = Instant.now().plusSeconds(2).toString();
    ResponseEntity<Map> created =
        create("{\"destinationUrl\":\"https://example.com/brief\",\"expiresAt\":\"" + soon + "\"}");
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    String code = (String) created.getBody().get("code");
    Thread.sleep(2_500);

    ResponseEntity<String> redirect =
        http.exchange(url("/" + code), HttpMethod.GET, null, String.class);

    assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.GONE);
    assertThat(redirect.getHeaders().getFirst("Location")).isNull();
  }

  @Test
  @DisplayName("analytics reports EXPIRED using the same clock as the redirect")
  void analyticsAgreesWithRedirect() throws InterruptedException {
    String soon = Instant.now().plusSeconds(2).toString();
    String code =
        (String)
            create(
                    "{\"destinationUrl\":\"https://example.com/counted\",\"expiresAt\":\""
                        + soon
                        + "\"}")
                .getBody()
                .get("code");

    http.exchange(url("/" + code), HttpMethod.GET, null, String.class); // one live redirect
    Thread.sleep(2_500);
    http.exchange(url("/" + code), HttpMethod.GET, null, String.class); // refused, must not count

    ResponseEntity<Map> analytics =
        http.exchange(url("/api/v1/links/" + code + "/analytics"), HttpMethod.GET, null, Map.class);

    assertThat(analytics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(analytics.getBody().get("status")).isEqualTo("EXPIRED");
    assertThat(((Number) analytics.getBody().get("totalRedirects")).longValue()).isEqualTo(1L);
  }

  @Test
  @DisplayName("Flyway applies both PostgreSQL-authored migrations unchanged")
  void migrationsApplyOnH2() {
    // Startup would have failed at Hibernate schema validation otherwise, but asserting it
    // directly names what is being relied on: V1 and V2 are portable as written.
    ResponseEntity<Map> health =
        http.exchange(url("/actuator/health"), HttpMethod.GET, null, Map.class);

    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody().get("status")).isEqualTo("UP");
  }
}
