package com.smartlink.infrastructure.resilience;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Retries a database operation once, at most, after a jittered pause.
 *
 * <p><strong>The cap is a load-shedding decision as much as a resilience one.</strong> The redirect
 * path carries the entire load, so during a database outage three retries per request means three
 * times the load against a dependency that is already failing, three times the thread-holding, and
 * a caller who waits three times as long to be told to come back. One retry survives a reset
 * connection; more than one turns a degraded dependency into a total outage.
 *
 * <p>Jitter is not decoration. Without it, every request in flight retries at the same instant, and
 * the retry itself becomes a synchronised burst — the thundering herd the retry was meant to
 * absorb, merely delayed by the backoff interval.
 *
 * <p>Non-transient failures are never retried. Classification, not the count, is what keeps this
 * from making an outage worse.
 */
@Component
public class BoundedRetry {

  private static final Logger log = LoggerFactory.getLogger(BoundedRetry.class);

  private final int maxRetries;
  private final Duration baseBackoff;

  public BoundedRetry(
      @Value("${smartlink.resilience.max-retries:1}") int maxRetries,
      @Value("${smartlink.resilience.base-backoff-ms:50}") long baseBackoffMillis) {
    this.maxRetries = maxRetries;
    this.baseBackoff = Duration.ofMillis(baseBackoffMillis);
  }

  /**
   * Runs the action, retrying only transient failures.
   *
   * @throws RuntimeException the last failure, unchanged — so the caller still sees the real cause
   *     rather than a wrapper that hides it
   */
  public <T> T execute(Supplier<T> action) {
    RuntimeException lastFailure = null;

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return action.get();
      } catch (RuntimeException failure) {
        lastFailure = failure;

        if (!TransientFailures.isTransient(failure)) {
          // Not worth another attempt, and pretending otherwise costs the caller latency to
          // reach the same answer.
          throw failure;
        }
        if (attempt == maxRetries) {
          break;
        }
        pause(attempt);
      }
    }

    log.warn("Giving up after {} attempt(s); the failure is being surfaced", maxRetries + 1);
    throw lastFailure;
  }

  /** Full jitter: a random point in [0, base × 2^attempt), rather than a fixed delay. */
  private void pause(int attempt) {
    long ceiling = baseBackoff.toMillis() << attempt;
    if (ceiling <= 0) {
      return; // backoff disabled, as tests do to stay fast
    }
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(ceiling));
    } catch (InterruptedException e) {
      // Restore the flag and abandon the retry. Swallowing an interrupt leaves a thread that
      // cannot be shut down, which turns a routine deployment into a hung one.
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while backing off", e);
    }
  }
}
