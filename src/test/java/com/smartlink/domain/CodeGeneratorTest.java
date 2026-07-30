package com.smartlink.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T4 acceptance: short-code format and generation. */
class CodeGeneratorTest {

  private final CodeGenerator generator = new CodeGenerator();

  @Test
  @DisplayName("codes are 7 characters of Base62")
  void codesHaveTheDeclaredShape() {
    IntStream.range(0, 500)
        .forEach(
            i -> {
              String code = generator.next().value();
              assertThat(code).hasSize(ShortCode.LENGTH).matches("[A-Za-z0-9]{7}");
            });
  }

  @Test
  @DisplayName("codes are not sequential and do not repeat over a large sample")
  void codesAreNotSequential() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 20_000; i++) {
      seen.add(generator.next().value());
    }

    // A weak signal individually, but a decisive one against the implementation actually
    // being guarded here: any counter- or time-based generator produces adjacent values, and
    // 20,000 draws from 62^7 should collide essentially never.
    assertThat(seen).hasSize(20_000);
  }

  @Test
  @DisplayName("every alphabet position is reachable")
  void everyAlphabetPositionIsReachable() {
    Set<Character> observed = new HashSet<>();
    for (int i = 0; i < 20_000; i++) {
      for (char c : generator.next().value().toCharArray()) {
        observed.add(c);
      }
    }

    // Catches an off-by-one in the bound, which would silently shrink the keyspace. A
    // generator that can never emit 'z' still looks entirely correct in every other test.
    assertThat(observed).hasSize(ShortCode.ALPHABET.length());
  }

  @Test
  @DisplayName("generation is deterministic under a seeded generator, so collisions are testable")
  void isDeterministicUnderASeededGenerator() {
    // The seam T5's forced-collision test depends on: without it, exercising insert-and-retry
    // would mean waiting for a 1-in-3.5-trillion event.
    RandomGenerator seeded = new java.util.Random(42);
    RandomGenerator sameSeed = new java.util.Random(42);

    assertThat(new CodeGenerator(seeded).next()).isEqualTo(new CodeGenerator(sameSeed).next());
  }

  @Test
  @DisplayName(
      "a generator stuck on one value produces the same code, which drives collision tests")
  void supportsAStuckGenerator() {
    RandomGenerator alwaysZero =
        new RandomGenerator() {
          @Override
          public long nextLong() {
            return 0L;
          }

          @Override
          public int nextInt(int bound) {
            return 0;
          }
        };

    assertThat(new CodeGenerator(alwaysZero).next().value()).isEqualTo("AAAAAAA");
  }

  @ParameterizedTest
  @ValueSource(strings = {"aB92xK7", "AAAAAAA", "0000000", "zzzzzzz"})
  @DisplayName("well-formed codes parse")
  void parsesWellFormedCodes(String candidate) {
    assertThat(ShortCode.parse(candidate)).isPresent();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "aB92xK", // too short
        "aB92xK78", // too long
        "aB92-K7", // hyphen is not in the alphabet
        "aB92 K7", // space
        "aB92/K7", // path separator would break routing
        "aB92.K7",
        "aB92%K7",
        "aB92éK7" // non-ASCII
      })
  @DisplayName("malformed codes do not parse")
  void rejectsMalformedCodes(String candidate) {
    assertThat(ShortCode.parse(candidate)).isEmpty();
  }

  @Test
  @DisplayName("null does not parse and does not throw")
  void nullDoesNotParse() {
    // Reached from the redirect path with whatever a caller put in the URL, so it must be
    // total. Throwing here would turn a routine 404 into a 500.
    assertThat(ShortCode.parse(null)).isEmpty();
  }

  @Test
  @DisplayName("of() refuses a malformed value outright")
  void ofRefusesMalformed() {
    assertThatThrownBy(() -> ShortCode.of("nope")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("codes with equal values are equal")
  void equality() {
    assertThat(ShortCode.of("aB92xK7"))
        .isEqualTo(ShortCode.of("aB92xK7"))
        .hasSameHashCodeAs(ShortCode.of("aB92xK7"))
        .isNotEqualTo(ShortCode.of("zZ11yY2"))
        .isNotEqualTo("aB92xK7")
        .hasToString("aB92xK7");
  }
}
