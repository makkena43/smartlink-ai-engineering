package com.smartlink.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Produces candidate short codes.
 *
 * <p>Three approaches were available and two were rejected outright:
 *
 * <ul>
 *   <li><strong>Sequential</strong> — rejected. Anyone could walk the entire corpus by counting,
 *       and with unauthenticated analytics (GF-12) that includes reading every link's traffic.
 *   <li><strong>Hash of the destination</strong> — rejected twice over. It leaks whether a given
 *       URL has been shortened before, which is an oracle; and it silently reintroduces the
 *       deduplication that GF-04 explicitly rules out, since the same URL would always produce the
 *       same code.
 *   <li><strong>Cryptographically random</strong> — chosen.
 * </ul>
 *
 * <p>{@link SecureRandom}, not {@code Random} or a time-seeded generator. An ordinary PRNG's output
 * is predictable from a handful of observed values, and observed values are exactly what a caller
 * has — every code it has ever created. That would hand back the enumeration property the random
 * choice was made to avoid.
 *
 * <p>Uniform selection over the alphabet is done with {@code nextInt(bound)}, which rejects and
 * resamples internally rather than taking a modulus. A modulus over a 62-symbol alphabet would bias
 * the first few characters — a small bias, but a permanent and measurable one in a value whose
 * entire job is to be unguessable.
 */
public class CodeGenerator {

  private final RandomGenerator random;

  public CodeGenerator() {
    this(new SecureRandom());
  }

  /** Test seam: a deterministic generator makes collision handling reproducible. */
  public CodeGenerator(RandomGenerator random) {
    this.random = random;
  }

  public ShortCode next() {
    StringBuilder builder = new StringBuilder(ShortCode.LENGTH);
    for (int i = 0; i < ShortCode.LENGTH; i++) {
      builder.append(ShortCode.ALPHABET.charAt(random.nextInt(ShortCode.ALPHABET.length())));
    }
    return ShortCode.of(builder.toString());
  }
}
