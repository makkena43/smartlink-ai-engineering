package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.SmartLinkApplication;
import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.TcpProxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
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
 * A3/A5 — readiness goes DOWN during a database outage and comes back <strong>on its own</strong>
 * (R-1).
 *
 * <p>{@code DependencyOutageIT} already proves the DOWN half by pointing at a dead port. It cannot
 * prove the other half, because that outage never ends. Recovery is the half that matters
 * operationally: an instance that correctly reports itself unready and then stays unready after the
 * database returns needs a human to notice and restart it, which is precisely the 3am page that
 * readiness probes exist to avoid.
 *
 * <p>The database is reached through {@link TcpProxy} so the outage can be ended. Restarting the
 * container instead would hand it a new mapped port, and the application would be recovering to an
 * address that no longer existed — proving nothing about recovery and quite a lot about the test.
 *
 * <p>The recovery bound asserted here is the 10 s design target from spec §4.2. It is polled rather
 * than slept through: a sleep asserts the clock, a poll asserts the transition.
 */
@DisplayName("Readiness recovery (R-1)")
class ReadinessRecoveryIT extends AbstractPostgresIT {

  /** Spec §4.2: readiness returns UP within 10 s of the datastore recovering. */
  private static final Duration RECOVERY_BUDGET = Duration.ofSeconds(10);

  private TcpProxy proxy;
  private ConfigurableApplicationContext app;
  private int port;

  @BeforeEach
  void startApplicationBehindProxy() throws Exception {
    proxy = new TcpProxy(postgres().getHost(), postgres().getFirstMappedPort());

    String database = postgres().getDatabaseName();
    app =
        new SpringApplicationBuilder(SmartLinkApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(
                "--server.port=0",
                "--spring.profiles.active=test",
                "--spring.datasource.url=jdbc:postgresql://localhost:"
                    + proxy.port()
                    + "/"
                    + database,
                "--spring.datasource.username=" + postgres().getUsername(),
                "--spring.datasource.password=" + postgres().getPassword(),
                // Hikari otherwise refuses to start when it cannot open its first connection.
                // The subject here is a running service losing its database, not a failed boot.
                "--spring.datasource.hikari.initialization-fail-timeout=-1",
                "--smartlink.resilience.base-backoff-ms=0");
    port = Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port"));
  }

  @AfterEach
  void stop() {
    if (app != null && app.isActive()) {
      app.close();
    }
    if (proxy != null) {
      proxy.close();
    }
  }

  private RestTemplate client() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setOutputStreaming(false);
    factory.setConnectTimeout(1_000);
    factory.setReadTimeout(10_000);

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

  private HttpStatus readiness() {
    return (HttpStatus)
        client().getForEntity(url("/actuator/health/readiness"), String.class).getStatusCode();
  }

  /** Polls until the readiness endpoint reports {@code expected}, or the budget runs out. */
  private Duration awaitReadiness(HttpStatus expected, Duration budget) {
    Instant deadline = Instant.now().plus(budget);
    Instant startedAt = Instant.now();
    while (Instant.now().isBefore(deadline)) {
      if (readiness() == expected) {
        return Duration.between(startedAt, Instant.now());
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    throw new AssertionError(
        "readiness never reached " + expected + " within " + budget + "; last was " + readiness());
  }

  private String createLink() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<Map> created =
        client()
            .exchange(
                url("/api/v1/links"),
                HttpMethod.POST,
                new HttpEntity<>("{\"destinationUrl\":\"https://example.com/recovery\"}", headers),
                Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) created.getBody().get("code");
  }

  @Test
  @DisplayName("readiness goes DOWN on outage and returns UP on recovery, with no intervention")
  void readinessRecoversOnItsOwn() {
    assertThat(readiness()).isEqualTo(HttpStatus.OK);

    proxy.cut();
    awaitReadiness(HttpStatus.SERVICE_UNAVAILABLE, Duration.ofSeconds(15));

    // Liveness must not follow readiness down: if it did, an orchestrator would restart every
    // instance at once and add a thundering herd to a database that is already unavailable.
    assertThat(
            client().getForEntity(url("/actuator/health/liveness"), String.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);

    proxy.restore();
    Duration recoveredIn = awaitReadiness(HttpStatus.OK, RECOVERY_BUDGET);

    assertThat(recoveredIn)
        .as("readiness recovered in %s", recoveredIn)
        .isLessThan(RECOVERY_BUDGET);
  }

  @Test
  @DisplayName("resolve fails safe during the outage and serves correctly again after recovery")
  void resolveFailsSafeThenRecovers() {
    String code = createLink();
    assertThat(
            client().exchange(url("/" + code), HttpMethod.GET, null, String.class).getStatusCode())
        .isEqualTo(HttpStatus.FOUND);

    proxy.cut();
    ResponseEntity<String> duringOutage =
        client().exchange(url("/" + code), HttpMethod.GET, null, String.class);

    assertThat(duringOutage.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    // AS-4 under the least convenient conditions: this exact code resolved successfully moments
    // ago, so the destination is known. It is still not emitted, because it cannot be verified.
    assertThat(duringOutage.getHeaders().getFirst("Location")).isNull();

    proxy.restore();
    awaitReadiness(HttpStatus.OK, RECOVERY_BUDGET);

    ResponseEntity<String> afterRecovery =
        client().exchange(url("/" + code), HttpMethod.GET, null, String.class);
    assertThat(afterRecovery.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(afterRecovery.getHeaders().getFirst("Location"))
        .isEqualTo("https://example.com/recovery");
  }
}
