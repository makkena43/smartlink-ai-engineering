package com.smartlink.domain;

import java.time.Instant;

/**
 * A short link as the system holds it.
 *
 * <p>A domain type, so the ports either side of the application layer speak in {@link ShortCode}
 * and {@link Destination} rather than in strings. That is not ceremony: a {@code Destination} can
 * only be obtained from {@link DestinationPolicy}, so a signature taking one is a signature that
 * <em>cannot</em> be handed an unvalidated URL. GF-19 becomes a compile-time property rather than a
 * convention someone has to remember.
 *
 * @param createdAt assigned by the database clock, never by an instance — several stateless
 *     instances would otherwise mean several clocks that disagree
 * @param expiresAt UTC instant after which the link stops resolving, or {@code null} for a link
 *     that never expires. Set at creation only and never mutated (A-13): a short link is a stable
 *     public handle, and silently changing when it stops working is a smaller version of silently
 *     changing where it points.
 */
public record Link(
    ShortCode code,
    Destination destination,
    Instant createdAt,
    long totalRedirects,
    Instant expiresAt) {

  /**
   * A link with no expiry.
   *
   * <p><strong>This overload exists to keep BC-5 literally true.</strong> Adding a component to a
   * record changes its canonical constructor, which would have broken three Greenfield test files
   * at compile time — and a suite that no longer compiles has not "passed unchanged", however
   * mechanical the fix.
   *
   * <p>Keeping the four-argument form costs one constructor and means the brownfield change edits
   * no existing test at all. It also happens to read correctly: omitting an expiry is exactly what
   * a non-expiring link is, so this is a meaningful overload rather than a compatibility shim that
   * needs apologising for.
   */
  public Link(ShortCode code, Destination destination, Instant createdAt, long totalRedirects) {
    this(code, destination, createdAt, totalRedirects, null);
  }

  /** Whether this link still resolves at the given instant. */
  public LinkLifecycle lifecycleAt(Instant now) {
    return LinkLifecycle.of(this, now);
  }
}
