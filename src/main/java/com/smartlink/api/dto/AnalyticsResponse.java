package com.smartlink.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Basic usage figures for a short link.
 *
 * <p>Aggregate counters only. There is deliberately no field for IP address, geography, browser,
 * device or referrer (NFR-13) — and no column behind one either, so the omission is enforced by the
 * schema rather than by anyone remembering.
 *
 * <p>That restraint is not squeamishness. Persisting per-request data turns a link shortener into a
 * behavioural tracking system and acquires retention, subject-access and deletion obligations that
 * nothing in the requirements asked for and nothing in this design is built to honour. Data never
 * collected cannot be recovered later; data collected under an unclear basis cannot be
 * un-collected. Only one of those two mistakes is fixable.
 */
@Schema(name = "AnalyticsResponse", description = "Aggregate usage for a short link")
public record AnalyticsResponse(
    @Schema(description = "The short code", example = "aB92xK7") String code,
    @Schema(description = "Destination the code resolves to") String destinationUrl,
    @Schema(description = "Creation instant, UTC", example = "2026-07-30T10:15:30Z")
        Instant createdAt,
    @Schema(description = "Total successful redirects served", example = "1432")
        long totalRedirects) {}
