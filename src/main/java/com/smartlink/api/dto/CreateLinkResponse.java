package com.smartlink.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A newly created short link.
 *
 * <p>{@code shortUrl} is returned fully formed rather than leaving the caller to assemble it from
 * {@code code} and a base they have to know. If that assembly is the caller's job, every client
 * reimplements it and every client gets to be wrong in a different way — and a wrong short URL is
 * already in the wild by the time anyone notices.
 *
 * <p>{@code destinationUrl} is echoed back exactly as stored, which is exactly as submitted. This
 * lets a caller confirm no normalisation occurred; normalising would silently break signed URLs and
 * tracking parameters (GF-07, GF-19).
 */
@Schema(name = "CreateLinkResponse", description = "A created short link")
public record CreateLinkResponse(
    @Schema(description = "The generated short code", example = "aB92xK7") String code,
    @Schema(description = "Canonical short URL", example = "http://localhost:8080/aB92xK7")
        String shortUrl,
    @Schema(description = "Destination, stored byte-identical to what was submitted")
        String destinationUrl,
    @Schema(description = "Creation instant, UTC", example = "2026-07-30T10:15:30Z")
        Instant createdAt) {}
