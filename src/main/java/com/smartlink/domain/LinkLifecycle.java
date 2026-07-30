package com.smartlink.domain;

import java.time.Instant;

/**
 * Whether a link is still serving.
 *
 * <p>A pure function of {@code (link, now)} with no framework, no I/O and no hidden clock — which
 * is what lets every boundary case be written as an ordinary assertion instead of a sleep.
 *
 * <p><strong>The boundary is inclusive at the expiry instant:</strong> a link with {@code expiresAt
 * = T} is active strictly before {@code T} and expired at {@code T} itself. That matches how a
 * campaign owner reads "expires at midnight" — they mean the link stops working at midnight, not
 * one millisecond after. Stated here because "at or after" versus "after" is exactly the kind of
 * difference that gets decided by accident in an {@code if} and then disagrees with the
 * documentation forever.
 */
public enum LinkLifecycle {
  ACTIVE,
  EXPIRED;

  public static LinkLifecycle of(Link link, Instant now) {
    return isExpired(link, now) ? EXPIRED : ACTIVE;
  }

  /** A link with no expiry never expires — which is why {@code null} is the correct default. */
  public static boolean isExpired(Link link, Instant now) {
    Instant expiresAt = link.expiresAt();
    return expiresAt != null && !now.isBefore(expiresAt);
  }
}
