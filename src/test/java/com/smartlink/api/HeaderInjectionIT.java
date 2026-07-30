package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.NonFollowingClient;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Response-splitting, tested at both layers of defence independently (GF-18).
 *
 * <p>This is the control specific to <em>being a redirect service</em>. The destination is written
 * into a {@code Location} response header, so a value carrying CR/LF can terminate that header and
 * inject others — forging a response body or poisoning an intermediary cache.
 *
 * <p>There are two defences, and testing them together would prove neither. If creation-time
 * rejection is the only thing exercised, the emission path is never asked whether it is safe; and
 * the emission path is what remains if a row ever reaches storage another way — a migration, a bulk
 * import, a manual correction, a future endpoint. So the second half of this class writes a hostile
 * row <strong>directly to the database</strong>, bypassing validation entirely, and asks what the
 * redirect does with it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HeaderInjectionIT extends AbstractPostgresIT {

  @Autowired private TestRestTemplate rest;
  @Autowired private JdbcTemplate jdbc;
  @LocalServerPort private int port;

  // ---------------------------------------------------------------------------------------
  // Defence 1: never accepted
  // ---------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://example.com/%0d%0aX-Injected:%20yes",
        "https://example.com/%0D%0ASet-Cookie:%20session=stolen",
        "https://example.com/%0a%0a<html>forged</html>",
        "https://example.com/%00",
        "https://example.com/%09tab"
      })
  @DisplayName("a splitting payload is refused at creation and never stored")
  void splittingPayloadIsRefusedAtCreation(String hostile) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/links",
            new HttpEntity<>(Map.of("destinationUrl", hostile), headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody())
        .as("and the rejection must not echo the payload back")
        .doesNotContain("X-Injected")
        .doesNotContain("Set-Cookie")
        .doesNotContain("forged");

    Integer stored =
        jdbc.queryForObject(
            "select count(*) from short_link where destination_url like ?",
            Integer.class,
            "%" + "Injected" + "%");
    assertThat(stored).isZero();
  }

  // ---------------------------------------------------------------------------------------
  // Defence 2: even if stored, never emitted as a split
  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a hostile row written directly to the database cannot split the response")
  void storedPayloadCannotSplitTheResponse() {
    // Deliberately bypasses every application-level control. This is the state the service
    // would be in after a migration, a bulk import, or a manual correction that did not go
    // through the policy - and the emission path has to hold on its own.
    String code = "hdrinj1";
    jdbc.update(
        "insert into short_link (short_code, destination_url) values (?, ?)",
        code,
        "https://example.com/\r\nX-Injected: yes\r\n\r\nforged-body");

    ResponseEntity<String> response =
        NonFollowingClient.create()
            .getForEntity("http://localhost:" + port + "/" + code, String.class);

    // The service may refuse to serve this at all - that is a perfectly good answer, and the
    // one the container gives, since it will not write a header containing CR/LF. What it may
    // NOT do is emit the injected header. The assertion is about the absence of the attack,
    // not about a particular status code, because both outcomes are safe and only one of them
    // is under this application's control.
    assertThat(response.getHeaders().keySet())
        .as("no header the attacker named may appear in the response")
        .noneMatch(name -> name.equalsIgnoreCase("X-Injected"));
    assertThat(response.getHeaders().getFirst("X-Injected")).isNull();

    List<String> location = response.getHeaders().get(HttpHeaders.LOCATION);
    if (location != null) {
      assertThat(location)
          .as("if a Location is sent at all, it carries no control characters")
          .allSatisfy(value -> assertThat(value).doesNotContain("\r").doesNotContain("\n"));
    }

    jdbc.update("delete from short_link where short_code = ?", code);
  }

  @Test
  @DisplayName(
      "a benign stored destination still redirects, so defence 2 is not just refusing everything")
  void benignStoredDestinationStillRedirects() {
    // The control for the test above. Without this, an implementation that refused every
    // redirect would pass the injection test perfectly while being entirely broken - which is
    // the classic way a security assertion ends up proving nothing.
    String code = "hdrinj2";
    jdbc.update(
        "insert into short_link (short_code, destination_url) values (?, ?)",
        code,
        "https://example.com/perfectly-fine");

    ResponseEntity<Void> response =
        NonFollowingClient.create()
            .getForEntity("http://localhost:" + port + "/" + code, Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
        .isEqualTo("https://example.com/perfectly-fine");

    jdbc.update("delete from short_link where short_code = ?", code);
  }
}
