package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.NonFollowingClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
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
 * B6/B7: expiry through the whole stack.
 *
 * <p>Expired rows are seeded <strong>directly via JDBC</strong> rather than by creating a link and
 * waiting. Waiting would make the suite slow and flaky, and creating an already-expired link is
 * impossible through the API by design (BF-02 refuses a past expiry). Writing the row directly is
 * also the more faithful test: it is exactly the state the database is in when a link created last
 * month reaches its expiry today.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LinkExpiryIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  private ResponseEntity<Void> follow(String code) {
    return NonFollowingClient.create()
        .getForEntity("http://localhost:" + port + "/" + code, Void.class);
  }

  private ResponseEntity<String> create(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.postForEntity("/api/v1/links", new HttpEntity<>(body, headers), String.class);
  }

  private String createWith(String destination, Instant expiresAt) {
    Map<String, Object> body = new HashMap<>();
    body.put("destinationUrl", destination);
    if (expiresAt != null) {
      body.put("expiresAt", expiresAt.toString());
    }
    String response = create(body).getBody();
    return response.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
  }

  private void seedExpired(String code, String destination, Instant expiredAt) {
    jdbc.update(
        "insert into short_link (short_code, destination_url, expires_at) values (?, ?, ?)",
        code,
        destination,
        java.sql.Timestamp.from(expiredAt));
  }

  // ---------------------------------------------------------------------------------------
  // BF-01..BF-04: creation
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a link created without expiry behaves exactly as before (BC-2)")
  void omittedExpiryPreservesGreenfieldBehaviour() {
    String code = createWith("https://example.com/no-expiry", null);

    assertThat(follow(code).getStatusCode()).isEqualTo(HttpStatus.FOUND);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> analytics =
        rest.getForEntity("/api/v1/links/" + code + "/analytics", Map.class);
    assertThat(analytics.getBody().get("expiresAt")).isNull();
    assertThat(analytics.getBody().get("status")).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("a future expiry is accepted and echoed back (BF-01)")
  void futureExpiryIsAccepted() {
    Instant expiry = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

    ResponseEntity<String> response =
        create(
            Map.of(
                "destinationUrl", "https://example.com/campaign", "expiresAt", expiry.toString()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getBody()).contains("\"expiresAt\"");
  }

  @Test
  @DisplayName("a link with a future expiry still redirects (BF-04)")
  void futureExpiryStillRedirects() {
    String code =
        createWith("https://example.com/still-live", Instant.now().plus(1, ChronoUnit.DAYS));

    ResponseEntity<Void> response = follow(code);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
        .isEqualTo("https://example.com/still-live");
  }

  @Test
  @DisplayName("a past expiry is refused at creation with 400 INVALID_EXPIRY (BF-02)")
  void pastExpiryIsRefused() {
    ResponseEntity<String> response =
        create(
            Map.of(
                "destinationUrl",
                "https://example.com/already-over",
                "expiresAt",
                Instant.now().minus(1, ChronoUnit.DAYS).toString()));

    // 400, not 422: the caller got the request wrong and can fix it by sending different
    // bytes, which is a different remedy from a destination declined on policy grounds.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).contains("INVALID_EXPIRY");
  }

  @Test
  @DisplayName("a malformed or zone-less expiry is refused, never guessed at")
  void malformedExpiryIsRefused() {
    // "2026-08-01T00:00:00" has no offset. Guessing a zone is how a campaign silently expires
    // five and a half hours early for whoever deployed in a different one.
    for (String bad : new String[] {"2026-08-01T00:00:00", "not-a-timestamp", "01/08/2026"}) {
      assertThat(
              create(Map.of("destinationUrl", "https://example.com/x", "expiresAt", bad))
                  .getStatusCode())
          .as("expiry %s must be refused", bad)
          .isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);
    }
  }

  // ---------------------------------------------------------------------------------------
  // BF-05, BF-06: the redirect contract
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("an expired link returns 410 and never a Location header (BF-05, BF-06)")
  void expiredLinkReturns410WithoutLocation() {
    seedExpired("expird1", "https://example.com/finished-campaign", Instant.now().minusSeconds(60));

    ResponseEntity<Void> response = follow("expird1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    // The absence of Location is the whole guarantee. A 410 carrying one would let a client
    // that follows redirects reach the destination anyway, which is precisely what "stop
    // redirecting" was asked to prevent.
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();
  }

  @Test
  @DisplayName("the 410 body names LINK_EXPIRED and leaks nothing")
  void expiredResponseIsSafe() {
    seedExpired(
        "expird2", "https://example.com/secret-campaign?token=abc", Instant.now().minusSeconds(60));

    String body =
        NonFollowingClient.create()
            .getForEntity("http://localhost:" + port + "/expird2", String.class)
            .getBody();

    assertThat(body).contains("LINK_EXPIRED");
    assertThat(body)
        .as("an expired link must not disclose where it used to point")
        .doesNotContain("secret-campaign")
        .doesNotContain("token=abc");
  }

  @Test
  @DisplayName("an expired attempt is NOT counted as a redirect (BF-05)")
  void expiredAttemptDoesNotIncrementCounter() {
    seedExpired("expird3", "https://example.com/counted", Instant.now().minusSeconds(60));

    for (int i = 0; i < 5; i++) {
      follow("expird3");
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> analytics = rest.getForEntity("/api/v1/links/expird3/analytics", Map.class);

    // The lifecycle check sits before the increment for exactly this reason. Counting
    // attempts that never redirected would inflate the figure for finished campaigns - the
    // ones most likely to be examined afterwards.
    assertThat(((Number) analytics.getBody().get("totalRedirects")).longValue()).isZero();
  }

  @Test
  @DisplayName("an unknown code is still 404, not 410 — the two states stay distinct")
  void unknownCodeIsStill404() {
    // 404 means never existed; 410 means existed and ended. Collapsing them would throw away
    // the operational signal the split was introduced for.
    assertThat(follow("zzzzzzz").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(follow("nosuch1").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------------------------------------------------------------------------------------
  // BF-07 and BC-1: analytics and legacy rows
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("analytics reports expiry and EXPIRED status (BF-07)")
  void analyticsReportsLifecycle() {
    Instant expiry = Instant.now().minusSeconds(60);
    seedExpired("expird4", "https://example.com/status", expiry);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map> analytics = rest.getForEntity("/api/v1/links/expird4/analytics", Map.class);

    assertThat(analytics.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(analytics.getBody().get("status")).isEqualTo("EXPIRED");
    assertThat(analytics.getBody().get("expiresAt")).isNotNull();
  }

  @Test
  @DisplayName("analytics remains readable for an expired link")
  void analyticsStillReadableAfterExpiry() {
    seedExpired("expird5", "https://example.com/readable", Instant.now().minusSeconds(60));

    // Expiry stops the link resolving; it does not delete it. The owner can still see what it
    // did, which is why expired rows are retained rather than cleaned up.
    assertThat(rest.getForEntity("/api/v1/links/expird5/analytics", String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("a pre-existing row with NULL expires_at still resolves (BC-1)")
  void legacyRowWithNullExpiryStillResolves() {
    // Exactly the shape of every row written before this migration: the column exists and is
    // NULL. If NULL were ever treated as "expired at the epoch", every link in the database
    // would have died the moment this change deployed.
    jdbc.update(
        "insert into short_link (short_code, destination_url) values (?, ?)",
        "legacy1",
        "https://example.com/created-before-expiry-existed");

    ResponseEntity<Void> response = follow("legacy1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
        .isEqualTo("https://example.com/created-before-expiry-existed");
  }

  @Test
  @DisplayName("a supplied expiry round-trips as the same instant")
  void expiryRoundTripsExactly() {
    Instant expiry = Instant.parse("2030-06-15T12:34:56Z");
    String code = createWith("https://example.com/roundtrip", expiry);

    Instant stored =
        jdbc.queryForObject(
                "select expires_at from short_link where short_code = ?",
                java.sql.Timestamp.class,
                code)
            .toInstant();

    // Stored as a timezone-aware instant, so it survives the round trip without drifting by
    // whatever offset the server happens to run in.
    assertThat(stored).isEqualTo(expiry);
  }
}
