package com.smartlink.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * B4/B7: the expiry rule, at the boundary.
 *
 * <p>Every case here is an exact-instant comparison, which is only writable as a plain assertion
 * because the rule takes {@code now} as a parameter rather than reading a clock. With a hardcoded
 * clock the boundary could only be tested by sleeping, and a sleeping test is slow, flaky, and the
 * first thing deleted when CI goes red for an unrelated reason.
 */
class LinkLifecycleTest {

  private static final Instant EXPIRY = Instant.parse("2026-08-01T00:00:00Z");

  private static Link linkExpiringAt(Instant expiresAt) {
    return new Link(
        ShortCode.of("aB92xK7"),
        Destination.ofStoredValue("https://example.com/campaign"),
        Instant.parse("2026-07-01T00:00:00Z"),
        0L,
        expiresAt);
  }

  @Nested
  @DisplayName("a link with no expiry")
  class NoExpiry {

    @Test
    @DisplayName("is active, however far in the future you look")
    void neverExpires() {
      Link link = linkExpiringAt(null);

      // null means "never", not "expired at the epoch". Getting this backwards would expire
      // every link created before scenario 02 the moment the change deployed.
      assertThat(link.lifecycleAt(Instant.parse("2026-07-01T00:00:00Z")))
          .isEqualTo(LinkLifecycle.ACTIVE);
      assertThat(link.lifecycleAt(Instant.parse("2999-01-01T00:00:00Z")))
          .isEqualTo(LinkLifecycle.ACTIVE);
      assertThat(LinkLifecycle.isExpired(link, Instant.MAX)).isFalse();
    }
  }

  @Nested
  @DisplayName("a link with an expiry")
  class WithExpiry {

    @Test
    @DisplayName("is active one nanosecond before the instant")
    void activeJustBefore() {
      assertThat(linkExpiringAt(EXPIRY).lifecycleAt(EXPIRY.minusNanos(1)))
          .isEqualTo(LinkLifecycle.ACTIVE);
    }

    @Test
    @DisplayName("is EXPIRED at exactly the instant — the boundary is inclusive")
    void expiredAtTheInstant() {
      // The decisive case, and the one a specification usually leaves ambiguous. "Expires at
      // midnight" means it stops working at midnight, not one tick afterwards. Asserted here
      // so the choice cannot drift into disagreeing with the documentation.
      assertThat(linkExpiringAt(EXPIRY).lifecycleAt(EXPIRY)).isEqualTo(LinkLifecycle.EXPIRED);
    }

    @Test
    @DisplayName("is expired one nanosecond after, and stays expired")
    void expiredAfter() {
      Link link = linkExpiringAt(EXPIRY);

      assertThat(link.lifecycleAt(EXPIRY.plusNanos(1))).isEqualTo(LinkLifecycle.EXPIRED);
      assertThat(link.lifecycleAt(EXPIRY.plusSeconds(31_536_000L)))
          .isEqualTo(LinkLifecycle.EXPIRED);
    }

    @Test
    @DisplayName("expiry is never reversed by time moving on")
    void expiryIsMonotonic() {
      Link link = linkExpiringAt(EXPIRY);
      Instant instant = EXPIRY;

      for (int i = 0; i < 100; i++) {
        instant = instant.plusSeconds(3_600);
        assertThat(link.lifecycleAt(instant)).isEqualTo(LinkLifecycle.EXPIRED);
      }
    }
  }

  @Test
  @DisplayName("the four-argument constructor still means non-expiring")
  void legacyConstructorMeansNoExpiry() {
    // This overload is what keeps every Greenfield call site compiling. If it ever started
    // defaulting to something other than null, links created through it would silently gain
    // an expiry - so the default is asserted rather than assumed.
    Link legacy =
        new Link(
            ShortCode.of("aB92xK7"),
            Destination.ofStoredValue("https://example.com/x"),
            Instant.EPOCH,
            0L);

    assertThat(legacy.expiresAt()).isNull();
    assertThat(legacy.lifecycleAt(Instant.parse("2999-01-01T00:00:00Z")))
        .isEqualTo(LinkLifecycle.ACTIVE);
  }
}
