package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartlink.SmartLinkApplication;
import com.smartlink.support.AbstractPostgresIT;
import com.smartlink.support.SlowDatabase;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * A6 — graceful shutdown (R-4).
 *
 * <p>Two properties, and the second is the one usually assumed rather than tested:
 *
 * <ol>
 *   <li><strong>Readiness goes DOWN before the socket closes.</strong> A load balancer must stop
 *       routing here first. Reversed, every instance drops a burst of requests on each deploy, and
 *       it presents as a mysterious periodic error-rate spike rather than as a shutdown bug.
 *   <li><strong>In-flight work finishes.</strong> A request already being served is completed, not
 *       severed. For this service that is correctness rather than courtesy: a redirect cut off
 *       mid-flight is indistinguishable, to the person clicking it, from a broken link.
 * </ol>
 *
 * <p><strong>This test boots its own application instance</strong> rather than using the shared
 * {@code @SpringBootTest} context. Shutting down the test's own context works, but leaves Spring's
 * test listeners operating on a closed context and the failure is reported as an error in a test
 * whose assertions all passed — noise indistinguishable from a real defect. Owning the lifecycle
 * removes the ambiguity, and an instance started and stopped by this test is a closer analogue of a
 * deployment than a framework-managed context anyway.
 */
@DisplayName("Graceful shutdown (R-4)")
class GracefulShutdownIT extends AbstractPostgresIT {

  private ConfigurableApplicationContext app;
  private int port;

  @BeforeEach
  void startApplication() {
    app =
        new SpringApplicationBuilder(SmartLinkApplication.class)
            .web(WebApplicationType.SERVLET)
            // Command-line args, not .properties(): the latter registers *default* properties,
            // the lowest-precedence source there is, so application.yml's
            // ${SMARTLINK_DB_USER:smartlink} won and the application tried to connect as a role
            // the container has never heard of.
            .run(
                "--server.port=0",
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + postgres().getJdbcUrl(),
                "--spring.datasource.username=" + postgres().getUsername(),
                "--spring.datasource.password=" + postgres().getPassword());
    port = Integer.parseInt(app.getEnvironment().getRequiredProperty("local.server.port"));
  }

  @AfterEach
  void stopApplicationAndClearFaults() {
    if (app != null && app.isActive()) {
      app.close();
    }
    slowDatabase().restore();
  }

  /** Reaches the container directly, so cleanup survives the application being shut down. */
  private SlowDatabase slowDatabase() {
    DriverManagerDataSource direct = new DriverManagerDataSource();
    direct.setUrl(postgres().getJdbcUrl());
    direct.setUsername(postgres().getUsername());
    direct.setPassword(postgres().getPassword());
    return new SlowDatabase(direct);
  }

  private RestTemplate client(int readTimeoutMillis) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setOutputStreaming(false);
    factory.setConnectTimeout(1_000);
    factory.setReadTimeout(readTimeoutMillis);

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
        client(5_000)
            .exchange(
                url("/api/v1/links"),
                HttpMethod.POST,
                new HttpEntity<>("{\"destinationUrl\":\"https://example.com/shutdown\"}", headers),
                Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return (String) created.getBody().get("code");
  }

  @Test
  @DisplayName("readiness reports OUT_OF_SERVICE while shutting down; liveness stays UP")
  void readinessGoesDownWhileLivenessStaysUp() {
    assertThat(
            client(5_000).getForEntity(url("/actuator/health/readiness"), String.class).getBody())
        .contains("UP");

    // Exactly the event Spring Boot publishes when graceful shutdown begins.
    org.springframework.boot.availability.AvailabilityChangeEvent.publish(
        app, org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);

    ResponseEntity<String> readiness =
        client(5_000).getForEntity(url("/actuator/health/readiness"), String.class);
    ResponseEntity<String> liveness =
        client(5_000).getForEntity(url("/actuator/health/liveness"), String.class);

    assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(readiness.getBody()).contains("OUT_OF_SERVICE");

    // A shutting-down instance is not a sick one. Liveness DOWN here would invite an orchestrator
    // to record a crash-loop for what is an ordinary, planned deployment.
    assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(liveness.getBody()).contains("UP");
  }

  @Test
  @DisplayName("an in-flight request completes during shutdown, and the port then stops answering")
  void inFlightRequestSurvivesShutdown() throws Exception {
    String code = createLink();

    // ~1 s of database time: long enough that shutdown certainly begins mid-request, and
    // comfortably inside the 2 s statement timeout so the request is not cut short by that.
    slowDatabase().install(1);

    CompletableFuture<ResponseEntity<String>> inFlight =
        CompletableFuture.supplyAsync(
            () -> client(20_000).exchange(url("/" + code), HttpMethod.GET, null, String.class));

    TimeUnit.MILLISECONDS.sleep(300); // let it reach the database

    long startedAt = System.nanoTime();
    app.close(); // blocks until graceful shutdown completes
    Duration shutdownTook = Duration.ofNanos(System.nanoTime() - startedAt);

    ResponseEntity<String> response = inFlight.get(20, TimeUnit.SECONDS);

    // The whole point: served, not severed.
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    assertThat(response.getHeaders().getFirst("Location"))
        .isEqualTo("https://example.com/shutdown");

    // It waited for the request rather than killing it, and nowhere near the 30 s grace period —
    // that value is a bound, not a delay every deploy pays.
    assertThat(shutdownTook).isLessThan(Duration.ofSeconds(10));

    // And no new work is accepted afterwards.
    assertThat(catchThrowable(() -> client(2_000).getForEntity(url("/" + code), String.class)))
        .as("the port must stop answering once shutdown has completed")
        .isNotNull();
  }
}
