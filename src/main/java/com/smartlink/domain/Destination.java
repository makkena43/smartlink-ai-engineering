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
