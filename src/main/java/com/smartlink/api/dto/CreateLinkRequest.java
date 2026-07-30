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
 */
@Schema(name = "CreateLinkRequest", description = "Request to create a short link")
public record CreateLinkRequest(
    @Schema(
            description = "Destination URL. Must be http or https and publicly routable.",
            example = "https://www.example.com/campaign",
            requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        @Size(max = 2048)
        String destinationUrl) {}
