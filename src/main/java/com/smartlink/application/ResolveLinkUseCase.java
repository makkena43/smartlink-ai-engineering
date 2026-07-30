package com.smartlink.application;

import com.smartlink.application.exception.LinkExpiredException;
import com.smartlink.application.exception.LinkNotFoundException;
import com.smartlink.domain.Link;
import com.smartlink.domain.LinkLifecycle;
import com.smartlink.domain.ResolvedLink;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves a short code to its destination and records the hit.
 *
 * <p>This is the path that carries the entire load and serves the least-trusted callers, so two
 * properties matter more here than anywhere else in the system, and they pull in opposite
 * directions:
 *
 * <ul>
 *   <li><strong>Never serve an unverified destination</strong> (NFR-02). If the mapping cannot be
 *       read, the request fails. It does not guess and it does not serve something stale.
 *   <li><strong>Never fail a redirect for a reason the visitor does not care about.</strong> The
 *       redirect is the product; the counter is instrumentation.
 * </ul>
 *
 * <p>The resolution of that tension is that the two failures are handled at different scopes: a
 * lookup failure propagates, a counter failure is swallowed.
 */
@Service
public class ResolveLinkUseCase {

  private static final Logger log = LoggerFactory.getLogger(ResolveLinkUseCase.class);

  private final LinkRepository repository;

  /**
   * R-5. The fail-open catch below is deliberately invisible to the caller, which is correct
   * behaviour and an observability problem at the same time: a counter that silently stops
   * incrementing looks identical, from outside, to a link nobody clicked.
   *
   * <p>A WARN log is not a substitute. Nobody computes a rate from log lines during an incident,
   * and the operational question — "are the numbers I am looking at still trustworthy?" — needs a
   * number, not a search. This counter is what separates "analytics are degraded" from "traffic has
   * dropped", two situations that demand opposite responses.
   */
  private final Counter analyticsWriteFailures;

  public ResolveLinkUseCase(LinkRepository repository, MeterRegistry meters) {
    this.repository = repository;
    this.analyticsWriteFailures =
        Counter.builder("smartlink.analytics.write.failures")
            .description("Redirects served whose counter update failed (fail-open, R-5)")
            .register(meters);
  }

  /**
   * @param rawCode whatever appeared in the URL path — untrusted
   * @throws LinkNotFoundException the code is unknown, or not a well-formed code at all
   */
  public Link resolve(String rawCode) {
    ShortCode code =
        ShortCode.parse(rawCode)
            .orElseThrow(
                () ->
                    // A malformed code gets the same answer as an unknown one. Distinguishing
                    // them would let a caller narrow the namespace by reading which error came
                    // back, and with anonymous creation and unauthenticated analytics the code
                    // is the only access control there is.
                    new LinkNotFoundException("code is not well-formed"));

    // Deliberately outside any try/catch. A failure here means the mapping could not be
    // verified, and NFR-02 says that must surface - the caller gets 503 rather than a guess.
    ResolvedLink resolved =
        repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException("no link"));

    // Lifecycle is decided AFTER the mapping is verified and BEFORE anything is counted or
    // emitted. Both halves of that ordering are load-bearing:
    //
    //   * before the lookup -> an expired link would be indistinguishable from an unknown one,
    //     collapsing 410 back into 404 and throwing away the signal the status split exists for
    //   * after the increment -> redirects that never happened would be counted, and the number
    //     would drift upward for exactly the campaigns most likely to be examined
    // Evaluated against the DATABASE's clock, carried back with the row rather than read from
    // this instance (A-12). Several stateless instances each reading their own clock would
    // disagree near an expiry: the same link would resolve on one and 410 on the next, with
    // nothing reproducible to chase. The observation costs no extra round trip - it rides
    // along in the lookup that was happening anyway.
    if (resolved.lifecycle() == LinkLifecycle.EXPIRED) {
      throw new LinkExpiredException("link expired");
    }

    recordRedirect(code);

    return resolved.link();
  }

  /**
   * Records the hit, and never lets that failure reach the caller.
   *
   * <p>The narrow scope of this catch is the entire point. Blocking a visitor from a page that is
   * perfectly available, in order to protect a counter, inverts the priority between the product
   * and its instrumentation — and the visitor cannot even tell why they were stopped.
   *
   * <p>This posture is <strong>invisible in the code</strong>: it reads as an ordinary try/catch,
   * and a well-meaning refactor that wraps resolution and increment in one transaction reverses it
   * with nothing else noticing. {@code AnalyticsFailureIT} is what keeps it true.
   */
  private void recordRedirect(ShortCode code) {
    try {
      repository.recordRedirect(code);
    } catch (RuntimeException e) {
      // WARN, not ERROR: the request succeeded and nobody needs to be woken up. But it is not
      // silent either - a sustained run of these means the counts are drifting, and an
      // operator should be able to see that before someone reports the numbers as wrong.
      log.warn("Could not record redirect for code {}; serving the redirect regardless", code, e);
      analyticsWriteFailures.increment();
    }
  }
}
