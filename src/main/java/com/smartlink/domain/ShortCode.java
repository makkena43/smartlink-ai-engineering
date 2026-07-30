package com.smartlink.domain;

import java.util.Objects;

/**
 * A short code: 7 characters of Base62.
 *
 * <p>Length and alphabet are fixed here rather than left implicit, because together they decide two
 * unrelated things at once — collision probability (62⁷ ≈ 3.5 × 10¹²) and how hard the namespace is
 * to walk.
 *
 * <p>The second matters more than it looks. With anonymous creation (GF-03) and unauthenticated
 * analytics (GF-12), <strong>possession of the code is the only access control that
 * exists</strong>. A sequential or otherwise predictable code would make the entire corpus, and its
 * traffic figures, enumerable by counting.
 */
public final class ShortCode {

  public static final int LENGTH = 7;
  static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private final String value;

  private ShortCode(String value) {
    this.value = value;
  }

  /**
   * Parses a code received from a caller.
   *
   * @return the code, or empty when it is not a well-formed code
   */
  public static java.util.Optional<ShortCode> parse(String candidate) {
    if (candidate == null || candidate.length() != LENGTH) {
      return java.util.Optional.empty();
    }
    for (int i = 0; i < candidate.length(); i++) {
      if (ALPHABET.indexOf(candidate.charAt(i)) < 0) {
        return java.util.Optional.empty();
      }
    }
    return java.util.Optional.of(new ShortCode(candidate));
  }

  /** Creates a code from a value already known to be well-formed. */
  static ShortCode of(String value) {
    return parse(value)
        .orElseThrow(() -> new IllegalArgumentException("not a well-formed short code"));
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ShortCode code && value.equals(code.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
