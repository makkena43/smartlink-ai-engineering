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
 */
public record Link(
    ShortCode code, Destination destination, Instant createdAt, long totalRedirects) {}
