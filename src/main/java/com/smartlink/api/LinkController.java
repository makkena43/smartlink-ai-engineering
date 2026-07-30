package com.smartlink.api;

import com.smartlink.api.dto.AnalyticsResponse;
import com.smartlink.api.dto.CreateLinkRequest;
import com.smartlink.api.dto.CreateLinkResponse;
import com.smartlink.application.CreateLinkUseCase;
import com.smartlink.application.ReadAnalyticsUseCase;
import com.smartlink.domain.Link;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link management: create, and read usage.
 *
 * <p>Versioned under {@code /api/v1} while the redirect deliberately is not. Short links get
 * printed and messaged; a version prefix inside them would make every issued link hostage to an
 * internal decision.
 *
 * <p>Both endpoints are unauthenticated (GF-03, GF-12), which is a stated prototype boundary rather
 * than an omission — production needs authentication, quotas and rate limiting before public
 * exposure.
 */
@RestController
@RequestMapping("/api/v1/links")
@Tag(name = "Links")
public class LinkController {

  private final CreateLinkUseCase createLink;
  private final ReadAnalyticsUseCase readAnalytics;
  private final String baseUrl;

  public LinkController(
      CreateLinkUseCase createLink,
      ReadAnalyticsUseCase readAnalytics,
      @Value("${smartlink.base-url}") String baseUrl) {
    this.createLink = createLink;
    this.readAnalytics = readAnalytics;
    // Trailing slash normalised once, here. Getting this wrong produces short URLs with a
    // double slash - which mostly work, so it would ship, and every link issued in the
    // meantime would carry it.
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @PostMapping
  @Operation(
      summary = "Create a short link",
      description =
          "Each call creates an independent link. Submitting the same destination twice returns "
              + "two different codes, on purpose: merging them would merge their usage figures "
              + "irreversibly.")
  @ApiResponses({
    @ApiResponse(responseCode = "201", description = "Created"),
    @ApiResponse(responseCode = "400", description = "Request could not be parsed"),
    @ApiResponse(responseCode = "422", description = "Destination refused by policy"),
    @ApiResponse(responseCode = "503", description = "Dependency unavailable")
  })
  public ResponseEntity<CreateLinkResponse> create(@Valid @RequestBody CreateLinkRequest request) {
    Link link = createLink.create(request.destinationUrl());

    return ResponseEntity.created(URI.create("/api/v1/links/" + link.code().value()))
        .body(
            new CreateLinkResponse(
                link.code().value(),
                baseUrl + "/" + link.code().value(),
                link.destination().value(),
                link.createdAt()));
  }

  @GetMapping("/{code}/analytics")
  @Operation(
      summary = "Read aggregate usage",
      description = "Total successful redirects. No personal data is collected or returned.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Usage figures"),
    @ApiResponse(responseCode = "404", description = "Unknown or malformed code")
  })
  public AnalyticsResponse analytics(@PathVariable String code) {
    Link link = readAnalytics.read(code);

    return new AnalyticsResponse(
        link.code().value(), link.destination().value(), link.createdAt(), link.totalRedirects());
  }
}
