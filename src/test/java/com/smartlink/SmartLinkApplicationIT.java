package com.smartlink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T1 acceptance: the application starts against a real database and reports health.
 *
 * <p>A real PostgreSQL container is used rather than an in-memory substitute because the thing
 * being asserted is that the configured datasource, Flyway and JPA validation agree with each
 * other. Swapping in H2 would test a different configuration than the one that ships, which is the
 * failure mode this test exists to rule out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SmartLinkApplicationIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Autowired private TestRestTemplate rest;

  @Test
  @DisplayName("application context starts against a real database")
  void contextLoads() {
    assertThat(POSTGRES.isRunning()).isTrue();
  }

  @Test
  @DisplayName("liveness reports UP")
  void livenessIsUp() {
    ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  @DisplayName("readiness reports UP while the database is reachable")
  void readinessIsUp() {
    ResponseEntity<String> response = rest.getForEntity("/actuator/health/readiness", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
  }

  @Test
  @DisplayName("health response exposes no internal detail")
  void healthLeaksNoInternals() {
    String body = rest.getForEntity("/actuator/health", String.class).getBody();

    assertThat(body).isNotNull();
    // show-details: never. A health endpoint that names the database, its host, or its
    // version is an unauthenticated reconnaissance surface, and this one is public.
    assertThat(body)
        .doesNotContain("PostgreSQL")
        .doesNotContain("jdbc:")
        .doesNotContain("validationQuery")
        .doesNotContain(POSTGRES.getHost());
  }

  @Test
  @DisplayName("only health and info actuator endpoints are exposed")
  void actuatorSurfaceIsNarrow() {
    // Actuator's full surface leaks configuration, environment and beans. The default
    // exposure is deliberately narrowed in application.yml; this asserts it stayed narrow,
    // because widening it is a one-line change that no other test would notice.
    for (String endpoint : new String[] {"env", "beans", "configprops", "mappings", "loggers"}) {
      ResponseEntity<String> response = rest.getForEntity("/actuator/" + endpoint, String.class);

      assertThat(response.getStatusCode())
          .as("/actuator/%s must not be exposed", endpoint)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }
  }

  @Test
  @DisplayName("generated OpenAPI document is served")
  void openApiDocumentIsServed() {
    // AC-6.6 equivalent: documentation is generated from the implementation. Asserting it
    // is reachable now means a later change that breaks generation fails the build rather
    // than silently shipping a stale contract.
    ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"openapi\"");
  }
}
