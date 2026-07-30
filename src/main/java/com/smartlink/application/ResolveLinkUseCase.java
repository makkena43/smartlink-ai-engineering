package com.smartlink.application;

import com.smartlink.application.exception.LinkNotFoundException;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
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

  public ResolveLinkUseCase(LinkRepository repository) {
    this.repository = repository;
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
    Link link = repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException("no link"));

    recordRedirect(code);

    return link;
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
    }
  }
}
