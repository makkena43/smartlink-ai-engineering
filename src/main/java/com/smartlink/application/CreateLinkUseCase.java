package com.smartlink.application;

import com.smartlink.application.exception.DependencyUnavailableException;
import com.smartlink.application.exception.InvalidDestinationException;
import com.smartlink.application.exception.InvalidExpiryException;
import com.smartlink.domain.CodeGenerator;
import com.smartlink.domain.Destination;
import com.smartlink.domain.DestinationPolicy;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
import com.smartlink.domain.port.TimeSource;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a submitted URL into a durable, independent short link.
 *
 * <p><strong>Nothing here looks a destination up.</strong> That absence is the implementation of
 * GF-04: submitting the same URL twice produces two independent links, because no step ever asks
 * whether the destination already exists. Deduplicating would look like a helpful optimisation and
 * would quietly merge two campaigns into one analytics bucket — irreversibly, since the
 * per-campaign figures were never recorded in the first place.
 *
 * <p>The method is deliberately <em>not</em> transactional. Each insert attempt must stand alone: a
 * rejected insert marks the surrounding transaction rollback-only, so retrying inside it would fail
 * for a reason unrelated to the collision being retried.
 */
@Service
public class CreateLinkUseCase {

  /**
   * Candidate codes tried before giving up.
   *
   * <p>Three is generous rather than tight. With 62⁷ ≈ 3.5 × 10¹² codes, exhausting three distinct
   * random candidates is not a collision problem — it means something is wrong with the generator
   * or the database, which is why exhaustion reports as a dependency failure rather than as a
   * retryable clash.
   *
   * <p>Kept strictly separate from the transient-failure allowance applied at the adapter (T7): if
   * the two shared a budget, a database outage would burn the collision attempts and a genuine
   * collision would then be reported as an outage.
   */
  static final int MAX_CODE_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(CreateLinkUseCase.class);

  private final DestinationPolicy policy;
  private final CodeGenerator generator;
  private final LinkRepository repository;
  private final TimeSource timeSource;

  public CreateLinkUseCase(
      DestinationPolicy policy,
      CodeGenerator generator,
      LinkRepository repository,
      TimeSource timeSource) {
    this.policy = policy;
    this.generator = generator;
    this.repository = repository;
    this.timeSource = timeSource;
  }

  /**
   * @param rawDestinationUrl exactly as submitted; stored verbatim if accepted
   * @throws InvalidDestinationException the destination is refused by policy (422)
   * @throws DependencyUnavailableException no code could be allocated (503)
   */
  /**
   * Creates a link that never expires.
   *
   * <p>Kept as an overload so every Greenfield call site — production and test — compiles
   * unchanged. Omitting an expiry <em>is</em> a non-expiring link, so this reads as a meaningful
   * default rather than a compatibility shim.
   */
  public Link create(String rawDestinationUrl) {
    return create(rawDestinationUrl, null);
  }

  /**
   * @param rawExpiresAt ISO-8601 instant with an offset, or {@code null} for never
   * @throws InvalidExpiryException the expiry is malformed, zone-less, or not strictly in the
   *     future (400 {@code INVALID_EXPIRY})
   */
  public Link create(String rawDestinationUrl, String rawExpiresAt) {
    Destination destination = validate(rawDestinationUrl);
    Instant expiresAt = parseExpiry(rawExpiresAt);

    for (int attempt = 1; attempt <= MAX_CODE_ATTEMPTS; attempt++) {
      ShortCode candidate = generator.next();
      Optional<Link> stored = repository.insert(candidate, destination, expiresAt);
      if (stored.isPresent()) {
        return stored.get();
      }
      // Empty means the database refused the code as already taken. Nothing is broken; try
      // another. Logged at DEBUG because at this keyspace a genuine collision is vanishingly
      // rare, so a burst of these is a signal worth being able to see.
      log.debug(
          "Short code already taken, retrying (attempt {} of {})", attempt, MAX_CODE_ATTEMPTS);
    }

    // 503, not 500. Nothing is unanticipated here - the attempts were consumed and the request
    // is safely retryable, which is exactly what 503 means. Reporting 500 would put a routine
    // outcome into the channel reserved for "someone needs to look at this".
    throw new DependencyUnavailableException(
        "exhausted " + MAX_CODE_ATTEMPTS + " short-code candidates without finding a free one");
  }

  /**
   * Rejects an expiry that is already past.
   *
   * <p>Checked against the authoritative {@link TimeSource}, not {@code Instant.now()}: with
   * several instances the latter means several clocks, and a link created near its own expiry would
   * then be accepted by one instance and refused by another.
   *
   * <p>An expiry exactly equal to "now" is refused, because {@link
   * com.smartlink.domain.LinkLifecycle} treats that same instant as already expired — accepting it
   * would create a link that is dead the moment it exists, which is never what the caller meant.
   */
  private Instant parseExpiry(String rawExpiresAt) {
    if (rawExpiresAt == null || rawExpiresAt.isBlank()) {
      return null; // absent means non-expiring, which is always valid
    }

    Instant expiresAt;
    try {
      // Parsed here rather than by the JSON binder so that a malformed value produces
      // INVALID_EXPIRY, which is what the specification promises. Left to Jackson, binding
      // fails before this use case is ever entered and the caller receives MALFORMED_REQUEST -
      // a contract that holds only for values the deserialiser happened to accept.
      //
      // Instant.parse requires an offset, so a zone-less "2026-08-01T00:00:00" lands here as a
      // parse failure rather than being silently interpreted in the server's zone.
      expiresAt = Instant.parse(rawExpiresAt);
    } catch (DateTimeParseException e) {
      throw new InvalidExpiryException(
          "expiry.malformed", "expiry is not an ISO-8601 instant with an offset");
    }

    if (!expiresAt.isAfter(timeSource.now())) {
      // The submitted timestamp is deliberately absent from the message: it reaches the logs,
      // and the rule that input is never reflected is only worth anything if it holds
      // everywhere, not just where reflection is obviously dangerous.
      throw new InvalidExpiryException("expiry.not-in-future", "expiry is not in the future");
    }
    return expiresAt;
  }

  private Destination validate(String rawDestinationUrl) {
    return switch (policy.evaluate(rawDestinationUrl)) {
      case DestinationPolicy.Result.Accepted accepted -> accepted.destination();
      case DestinationPolicy.Result.Rejected rejected ->
          // The rule name is safe to publish: it comes from a fixed vocabulary the service
          // controls. The submitted value is not, and never reaches the exception - which is
          // what keeps it out of the error body and out of the logs.
          throw new InvalidDestinationException(
              rejected.violation().ruleName(),
              "destination refused by policy: " + rejected.violation().name());
    };
  }
}
