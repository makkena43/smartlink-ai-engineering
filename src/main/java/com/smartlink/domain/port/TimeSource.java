package com.smartlink.domain.port;

import java.time.Instant;

/**
 * The authoritative present moment.
 *
 * <p>A port rather than a call to {@code Instant.now()}, for two reasons that both bite in
 * production rather than in a demo:
 *
 * <ul>
 *   <li><strong>Testability at the boundary.</strong> Expiry is defined by an inequality against
 *       "now", so the only interesting cases sit exactly on it — the instant before, the instant
 *       itself, the instant after. With a hardcoded clock those tests can only be written by
 *       sleeping, and a test that sleeps is slow, flaky, and quietly deleted the first time it
 *       fails in CI for an unrelated reason.
 *   <li><strong>One clock, not one per instance.</strong> With several stateless instances, {@code
 *       Instant.now()} means several clocks that disagree. A link near its expiry would then
 *       resolve on one instance and return 410 on the next, with no way to reproduce it — the kind
 *       of bug that costs days.
 * </ul>
 *
 * <p>Named {@code TimeSource} rather than {@code Clock} to avoid colliding with {@link
 * java.time.Clock} in the reader's head; the production implementation delegates to it.
 */
public interface TimeSource {

  Instant now();
}
