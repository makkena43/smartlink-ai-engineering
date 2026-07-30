package com.smartlink.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smartlink.support.AbstractPostgresIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * T7 acceptance: destination URLs never reach the logs (NFR-14).
 *
 * <p>Not a stylistic rule. A destination is attacker-controlled and its query string routinely
 * carries credentials — password-reset tokens, signed URLs, session identifiers. Logging one copies
 * that secret into every log sink, backup and aggregation pipeline the service touches, where it
 * outlives the token's own lifetime and sits somewhere with far weaker access controls than the
 * system that issued it.
 *
 * <p>Asserted by capturing what is actually logged, because this cannot be reviewed for reliably:
 * the leak usually arrives through a {@code toString()} or an exception message nobody was thinking
 * about as logging at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LogHygieneIT extends AbstractPostgresIT {

  /** Distinctive enough that a match cannot be coincidental. */
  private static final String SECRET = "s3cr3t-reset-token-9f2c1a7e";

  private static final String DESTINATION_WITH_SECRET =
      "https://example.com/reset?token=" + SECRET + "&next=/account";

  @Autowired private TestRestTemplate rest;
  @Autowired private org.springframework.core.env.Environment environment;

  private ListAppender<ILoggingEvent> captured;
  private ch.qos.logback.classic.Logger rootLogger;
  private Level originalLevel;

  @BeforeEach
  void attachAppender() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    rootLogger = context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    originalLevel = rootLogger.getLevel();

    // DEBUG, not INFO. The requirement is "INFO or below", so the quieter levels are exactly
    // the ones that must be inspected — and DEBUG is where a destination is most likely to be
    // logged by someone who reasoned that DEBUG is not really production.
    rootLogger.setLevel(Level.DEBUG);

    captured = new ListAppender<>();
    captured.setContext(context);
    captured.start();
    rootLogger.addAppender(captured);
  }

  @AfterEach
  void detachAppender() {
    rootLogger.detachAppender(captured);
    rootLogger.setLevel(originalLevel);
    captured.stop();
  }

  /**
   * What <em>this application's</em> code logged.
   *
   * <p>Scoped to {@code com.smartlink} deliberately, and the reason is a finding rather than a
   * convenience. With the root logger at DEBUG, five framework loggers emit the destination —
   * {@code RestTemplate}, {@code DispatcherServlet} and {@code RequestResponseBodyMethodProcessor}
   * among them, the last logging the deserialised request body verbatim. None of them belong to
   * this service, and none can be fixed here.
   *
   * <p>That exposure is real and is closed in configuration instead: {@code application.yml} pins
   * {@code org.springframework.web} and Hibernate's SQL loggers to INFO explicitly, so that setting
   * root to DEBUG while troubleshooting something unrelated cannot silently start copying customer
   * secrets into every log sink.
   *
   * <p>So there are two separate guarantees. These tests assert the one this codebase owns — our
   * code never logs a destination, at any level. The configuration pin covers the other.
   */
  private List<String> loggedText() {
    return captured.list.stream()
        .filter(event -> event.getLoggerName().startsWith("com.smartlink"))
        .map(
            event -> {
              StringBuilder text = new StringBuilder(event.getFormattedMessage());
              for (Throwable t =
                      event.getThrowableProxy() == null
                          ? null
                          : ((ch.qos.logback.classic.spi.ThrowableProxy) event.getThrowableProxy())
                              .getThrowable();
                  t != null;
                  t = t.getCause()) {
                text.append(' ').append(t.getMessage());
              }
              return text.toString();
            })
        .toList();
  }

  private void createLink(String destination) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    rest.postForEntity(
        "/api/v1/links",
        new HttpEntity<>(Map.of("destinationUrl", destination), headers),
        String.class);
  }

  @Test
  @DisplayName("creating a link never logs the destination")
  void createDoesNotLogDestination() {
    createLink(DESTINATION_WITH_SECRET);

    assertThat(loggedText())
        .as("a token in a query string must not reach any log sink")
        .noneMatch(line -> line.contains(SECRET));
  }

  @Test
  @DisplayName("resolving a link never logs the destination")
  void resolveDoesNotLogDestination() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body =
        rest.postForEntity(
                "/api/v1/links",
                new HttpEntity<>(Map.of("destinationUrl", DESTINATION_WITH_SECRET), headers),
                String.class)
            .getBody();
    String code = body.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");

    captured.list.clear();
    rest.getForEntity("/" + code, String.class);

    assertThat(loggedText()).noneMatch(line -> line.contains(SECRET));
  }

  @Test
  @DisplayName("a refused destination is not echoed into the logs either")
  void refusalDoesNotLogDestination() {
    // The rejection path is the likeliest place to leak: reporting *what* was rejected feels
    // helpful, and the value being reported is the attacker's.
    createLink("javascript:alert('" + SECRET + "')");

    assertThat(loggedText())
        .as("the violated rule may be logged; the submitted value may not")
        .noneMatch(line -> line.contains(SECRET));
  }

  @Test
  @DisplayName("the rule name IS logged, so a rejection remains diagnosable")
  void rejectionLogsTheRuleName() {
    createLink("javascript:alert(1)");

    // The complement of the tests above. A log that omits everything is safe and useless;
    // this asserts the service still records enough to explain itself, using a vocabulary it
    // controls rather than one the caller supplied.
    assertThat(loggedText()).anyMatch(line -> line.contains("destination.scheme"));
  }

  @Test
  @DisplayName("framework request logging is pinned to INFO, closing the other half of NFR-14")
  void frameworkRequestLoggingIsPinnedToInfo() {
    // Spring MVC logs the deserialised request body at DEBUG, which for this service is the
    // destination URL. Leaving that level to inherit from root would mean a routine
    // troubleshooting change - set root to DEBUG - silently begins writing customer tokens to
    // disk, with nothing in the diff to suggest it.
    //
    // Asserted rather than merely commented, because the pin is a single line in a YAML file
    // and is exactly the kind of thing removed during a tidy-up by someone who cannot see
    // what it was protecting.
    assertThat(environment.getProperty("logging.level.org.springframework.web"))
        .as("must be pinned explicitly, not inherited from root")
        .isEqualTo("INFO");
  }
}
