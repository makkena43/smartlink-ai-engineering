package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * T7 fault injection: the service with its database taken away.
 *
 * <p>Points at a port nothing is listening on, so every database interaction fails for real. No
 * mock could produce this — the failure has to travel the whole way up from the driver, through
 * Hikari, the retry, JPA, the repository and the use case, before the API layer decides what it
 * looks like on the wire. Every layer in that chain has a chance to turn it into the wrong thing.
 *
 * <p>The most important assertion here is the one about <strong>liveness staying UP</strong>, and
 * it is the one most likely to be broken by a well-meaning change.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
      "spring.datasource.username=absent",
      "spring.datasource.password=absent",
      // Hikari fails startup by default if it cannot open its first connection; the point of
      // this test is a service that is UP with a database that is DOWN.
      "spring.datasource.hikari.initialization-fail-timeout=-1",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=none",
      // Hibernate determines its dialect by asking the database, so without this the context
      // cannot even start when the database is gone - and a service that will not boot without
      // its database is a different, worse failure than the one under test here.
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
      "smartlink.resilience.base-backoff-ms=0"
    })
@ActiveProfiles("test")
class DependencyOutageIT {

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("liveness stays UP while the database is unreachable (GF-13)")
  void livenessStaysUp() {
    ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);

    // The single most consequential assertion in this class. If liveness consulted the
    // database, a transient outage would make every instance report itself dead, the
    // orchestrator would restart all of them at once, and a recoverable dependency failure
    // would become a self-inflicted total outage - with the restarts themselves adding load
    // to the very database that was struggling.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  @DisplayName("readiness goes DOWN so a load balancer stops routing here (GF-13)")
  void readinessGoesDown() {
    ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

    // The counterpart: this instance genuinely cannot serve, so it must say so. Liveness and
    // readiness answering differently is the entire value of having both.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("\"status\":\"DOWN\"");
  }

  @Test
  @DisplayName("a redirect returns 503 and never a guessed destination (NFR-02)")
  void redirectFailsSafely() {
    ResponseEntity<String> response = rest.getForEntity("/aB92xK7", String.class);

    assertThat(response.getStatusCode())
        .as("503 means come back; 500 would mean something is broken")
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
        .as("the mapping could not be verified, so there is nothing safe to send anyone to")
        .isNull();
  }

  @Test
  @DisplayName("creation returns 503, not 500")
  void createFailsSafely() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/links",
            new HttpEntity<>(Map.of("destinationUrl", "https://example.com/x"), headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).contains("SERVICE_UNAVAILABLE");
  }

  @Test
  @DisplayName("the outage response discloses nothing about the database")
  void outageResponseIsSafe() {
    String body = rest.getForEntity("/aB92xK7", String.class).getBody();

    // An outage is exactly when error handling is most likely to leak: the framework has a
    // detailed message to hand and the safe path is the one that has to be deliberate.
    assertThat(body)
        .doesNotContain("jdbc")
        .doesNotContain("postgresql")
        .doesNotContain("127.0.0.1")
        .doesNotContain("Connection refused")
        .doesNotContain("HikariPool")
        .doesNotContain("Exception")
        .doesNotContain("com.smartlink");
  }

  @Test
  @DisplayName("an outage response still carries a correlation id")
  void outageResponseIsCorrelated() {
    ResponseEntity<String> response = rest.getForEntity("/aB92xK7", String.class);

    // The moment a caller most needs something to quote at support is the moment the response
    // says least. The correlation id is what makes an opaque error still actionable.
    assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    assertThat(response.getBody()).contains("requestId");
  }
}
