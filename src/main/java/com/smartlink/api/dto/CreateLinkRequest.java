package com.smartlink.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to shorten a destination URL.
 *
 * <p>The constraints here are the cheap, transport-level ones only: present, and not absurdly long.
 * **They are not the destination policy.** Scheme rules, address-range rules and notation-evasion
 * handling live in the domain layer (T4), because a rule enforced at the transport boundary is
 * bypassed by the next entry point someone adds — a batch import, a message consumer, an admin path
 * (NFR-15).
 *
 * <p>The length bound is duplicated here on purpose. Rejecting a megabyte body before it reaches
 * parsing is a denial-of-service control, and it is worth doing early even though the domain will
 * check the same bound again.
 *
 * @param destinationUrl the URL a visitor should be sent to
 * @param expiresAt optional UTC instant after which the link stops resolving. Omit it for a link
 *     that never expires — which is exactly the Greenfield behaviour, so existing callers are
 *     unaffected (BC-2). Must be ISO-8601 with an offset, e.g. {@code 2026-08-01T00:00:00Z}; a
 *     local date-time is rejected rather than guessed at, because guessing a timezone is how a
 *     campaign silently expires five and a half hours early.
 */
@Schema(name = "CreateLinkRequest", description = "Request to create a short link")
public record CreateLinkRequest(
    @Schema(
            description = "Destination URL. Must be http or https and publicly routable.",
            example = "https://www.example.com/campaign",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 2048)
        String destinationUrl,
    @Schema(
            description =
                "Optional UTC instant after which the link stops resolving. Omit for a link that "
                    + "never expires. ISO-8601 and must carry an offset; a zone-less local "
                    + "date-time is refused rather than guessed at.",
            example = "2026-08-01T00:00:00Z",
            type = "string",
            format = "date-time",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String expiresAt) {}
