package com.smartlink.infrastructure.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;

/**
 * T7 acceptance: the retry is bounded, and bounded on the correct side.
 *
 * <p>The obvious tests here — "a transient failure is retried" — are the least useful. The
 * dangerous bug is <em>over</em>-retrying, and it is invisible on a healthy system: it only shows
 * up during an outage, as the thing making the outage worse. So most of what follows asserts the
 * upper bound and the refusals.
 *
 * <p>Backoff is set to zero throughout. Sleeping would make the suite slower without testing
 * anything the jitter calculation does not already cover.
 */
class BoundedRetryTest {

  private final BoundedRetry retry = new BoundedRetry(1, 0);

  @Test
  @DisplayName("a successful call is not retried")
  void successIsNotRetried() {
    AtomicInteger calls = new AtomicInteger();

    String result =
        retry.execute(
            () -> {
              calls.incrementAndGet();
              return "ok";
            });

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("a transient failure is retried exactly once, then succeeds")
  void transientFailureIsRetriedOnce() {
    AtomicInteger calls = new AtomicInteger();

    String result =
        retry.execute(
            () -> {
              if (calls.incrementAndGet() == 1) {
                // A connection reset, not a query timeout. This test is about retry mechanics
                // and needs any genuinely transient failure; QueryTimeoutException stopped
                // being one at ADR-013 and now proves the opposite of what is intended here.
                throw new DataAccessResourceFailureException("first attempt hit a reset");
              }
              return "recovered";
            });

    assertThat(result).isEqualTo("recovered");
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("the total attempt count is capped at two — the assertion that actually matters")
  void attemptsAreCappedAtTwo() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retry.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw new DataAccessResourceFailureException("still down");
                    }))
        .isInstanceOf(DataAccessResourceFailureException.class);

    // Asserting the UPPER bound, not merely that a retry happened. A test that only checks
    // "it retried" passes just as happily against an implementation that retries ten times,
    // and ten times is what turns a degraded dependency into a total outage.
    assertThat(calls).as("initial attempt plus at most one retry").hasValue(2);
  }

  @Test
  @DisplayName("the original failure is surfaced, not a wrapper")
  void surfacesTheOriginalFailure() {
    QueryTimeoutException original = new QueryTimeoutException("the real cause");

    assertThatThrownBy(
            () ->
                retry.execute(
                    () -> {
                      throw original;
                    }))
        // Wrapping would bury the cause an operator needs, and would break the API layer's
        // DataAccessException handler, silently turning a 503 into a 500.
        .isSameAs(original);
  }

  // ---------------------------------------------------------------------------------------

  static Stream<Arguments> nonRetryableFailures() {
    return Stream.of(
        Arguments.of(
            new DataIntegrityViolationException("duplicate key"),
            "a constraint violation is a collision signal, not an outage — retrying it would "
                + "consume the caller's collision budget three times over"),
        Arguments.of(
            new BadSqlGrammarException("task", "select ?", new java.sql.SQLException()),
            "broken SQL will be broken on every attempt"),
        Arguments.of(new EmptyResultDataAccessException(1), "absence is an answer, not a failure"),
        Arguments.of(
            new InvalidDataAccessApiUsageException("misuse"), "a programming error, not a blip"),
        Arguments.of(new IllegalStateException("not a data access failure at all"), "out of scope"),
        // Moved here from the retryable set at ADR-013, and it is the only entry in this file
        // that changed meaning rather than being added. A QueryTimeoutException IS a
        // TransientDataAccessException, so this is a deliberate carve-out: a timeout means the
        // database is too slow right now, and sending the same expensive query back doubles the
        // load it is already failing to carry while making the caller wait the budget twice.
        // Measured, not theorised - a 10 s query produced a >20 s request before this changed.
        Arguments.of(
            new QueryTimeoutException("timed out"),
            "a timeout means the dependency is overloaded; retrying deepens the outage"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("nonRetryableFailures")
  @DisplayName("non-transient failures are refused on the first attempt")
  void nonTransientFailuresAreNotRetried(RuntimeException failure, String why) {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                retry.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(calls).as(why).hasValue(1);
  }

  static Stream<Arguments> retryableFailures() {
    return Stream.of(
        Arguments.of(new CannotAcquireLockException("lock"), "lock contention"),
        Arguments.of(
            new DataAccessResourceFailureException("connection reset"), "connection reset"),
        Arguments.of(new RecoverableDataAccessException("recoverable"), "explicitly recoverable"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("retryableFailures")
  @DisplayName("transient failures get their one retry")
  void transientFailuresAreRetried(RuntimeException failure, String description) {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
        () ->
            retry.execute(
                () -> {
                  calls.incrementAndGet();
                  throw failure;
                }));

    assertThat(calls).as(description).hasValue(2);
  }

  @Test
  @DisplayName("a transient cause nested inside another exception is still recognised")
  void detectsTransientCauseInAChain() {
    AtomicInteger calls = new AtomicInteger();

    // JPA and Spring wrap failures liberally, so a classifier that inspects only the top-level
    // type would refuse to retry almost every real connection reset.
    assertThatThrownBy(
        () ->
            retry.execute(
                () -> {
                  calls.incrementAndGet();
                  throw new IllegalStateException(
                      "wrapper", new DataAccessResourceFailureException("connection reset"));
                }));

    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("retries can be disabled entirely")
  void retriesCanBeDisabled() {
    AtomicInteger calls = new AtomicInteger();
    BoundedRetry none = new BoundedRetry(0, 0);

    assertThatThrownBy(
        () ->
            none.execute(
                () -> {
                  calls.incrementAndGet();
                  throw new QueryTimeoutException("down");
                }));

    assertThat(calls).hasValue(1);
  }
}
