package com.smartlink.domain;

import java.util.Objects;

/**
 * A destination URL that has passed the policy in {@link DestinationPolicy}.
 *
 * <p>Constructible only from within this package, so possession of a {@code Destination} is proof
 * that validation happened. That is what makes GF-19 structural rather than procedural: no use case
 * can persist an unvalidated destination, because it has no way to obtain one.
 *
 * <p>The stored value is the <strong>raw submitted string</strong>, not a normalised form.
 * Normalisation happens during evaluation and is discarded afterwards. Rewriting a stored
 * destination would silently break signed URLs and tracking parameters, and the breakage would
 * surface much later as a campaign that quietly underperformed rather than as an error anyone could
 * trace back here.
 */
public final class Destination {

  private final String value;

  Destination(String value) {
    this.value = Objects.requireNonNull(value, "value");
  }

  /**
   * Rebuilds a destination that policy already accepted, on its way out of storage.
   *
   * <p>Re-running the policy on read would be wrong twice over. It would put a DNS lookup on the
   * redirect path, which carries the whole load; and a host that was public on Monday may be
   * unresolvable on Tuesday, so a link that was legitimately created would start returning errors
   * for reasons entirely outside its owner's control.
   *
   * <p>Validation happens once, at creation (GF-19). The known consequence — a destination
   * re-pointed after the fact — is the time-of-check-to-time-of-use gap recorded as R-1b, and it is
   * not fixable here: only re-validation at fetch time closes it, by whichever component eventually
   * fetches.
   */
  public static Destination ofStoredValue(String alreadyValidated) {
    return new Destination(alreadyValidated);
  }

  /** The exact string that was submitted and accepted. */
  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Destination destination && value.equals(destination.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  /**
   * Deliberately does not include the URL.
   *
   * <p>{@code toString()} reaches logs and exception messages by accident far more often than by
   * design, and destination query strings routinely carry credentials — reset tokens, signed URLs,
   * session identifiers. Including the value here would copy those into every log sink the service
   * touches (NFR-14).
   */
  @Override
  public String toString() {
    return "Destination[" + value.length() + " chars]";
  }
}
