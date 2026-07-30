package com.smartlink.api;

import com.smartlink.application.ResolveLinkUseCase;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public redirect. Unversioned and mounted at the root, because short links must stay short.
 */
@RestController
@Tag(name = "Redirect")
public class RedirectController {

  /**
   * Matches only code-shaped paths.
   *
   * <p>This is GF-16 (routing precedence) enforced structurally rather than by ordering luck.
   * Constraining the path variable means {@code /actuator/health}, {@code /api/v1/links} and {@code
   * /swagger-ui.html} can never be captured by this handler — not because they happen to be
   * registered first, but because they do not match. A future change to code length or alphabet
   * would have to change this pattern too, which makes the coupling visible instead of latent.
   */
  private static final String CODE_PATTERN = "/{code:[A-Za-z0-9]{" + ShortCode.LENGTH + "}}";

  private final ResolveLinkUseCase resolveLink;

  public RedirectController(ResolveLinkUseCase resolveLink) {
    this.resolveLink = resolveLink;
  }

  @GetMapping(CODE_PATTERN)
  @Operation(
      summary = "Resolve a short code",
      description =
          "Returns 302 with the exact registered destination. Never cacheable: every click must "
              + "reach the service or the redirect count silently undercounts by an unmeasurable "
              + "margin.")
  @ApiResponses({
    @ApiResponse(responseCode = "302", description = "Redirect to the destination"),
    @ApiResponse(responseCode = "404", description = "Unknown or malformed code"),
    @ApiResponse(
        responseCode = "410",
        description =
            "The link existed and has passed its expiry. No Location header is sent, so a "
                + "redirect-following client cannot reach the destination. Distinct from 404, "
                + "which means the code never existed."),
    @ApiResponse(responseCode = "503", description = "Mapping could not be verified")
  })
  public ResponseEntity<Void> resolve(@PathVariable String code) {
    Link link = resolveLink.resolve(code);

    return ResponseEntity.status(HttpStatus.FOUND)
        // Set as a raw string rather than via ResponseEntity.location(URI). That overload
        // writes URI.toASCIIString(), which re-encodes the value - and GF-07 requires the
        // destination byte-identical, because normalising it silently breaks signed URLs and
        // tracking parameters. Safe to write directly because the destination policy already
        // refused control characters (GF-18), so this cannot split the header.
        .header(HttpHeaders.LOCATION, link.destination().value())
        // 302 exists to keep every click reaching the service; a cached response would defeat
        // that just as thoroughly as a 301 would.
        .cacheControl(CacheControl.noStore())
        .build();
  }
}
