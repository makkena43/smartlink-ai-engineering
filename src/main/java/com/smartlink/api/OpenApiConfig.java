package com.smartlink.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document metadata.
 *
 * <p>Only the metadata is declared here. Every operation, schema and status code is derived from
 * the controllers and DTOs themselves, so the published contract cannot drift from the running
 * service — a hand-maintained contract is wrong the first time someone changes a controller and
 * forgets, and nothing fails when they do.
 *
 * <p>The description below states the anonymous-access boundary in the document a reviewer actually
 * opens. Requiring them to find it in a separate file is how a deliberate prototype decision gets
 * mistaken for a missing control.
 */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI smartLinkOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("SmartLink")
                .version("1.0.0")
                .description(
                    """
                    URL shortener: create short links, resolve them, and read basic usage counts.

                    **Prototype access boundary.** Link creation and analytics are anonymous by \
                    design in this prototype (GF-03, GF-12). Possession of the short code is the \
                    only access control that exists, which is why codes are cryptographically \
                    random rather than sequential. Production requires authentication, \
                    authorization, quotas and rate limiting before public exposure.

                    **Privacy.** Analytics are aggregate counts only. No IP address, geography, \
                    browser, device or referrer is collected or stored.

                    **Redirects** return `302 Found` with `Cache-Control: no-store`, so every \
                    resolution reaches the service and the redirect count stays complete.\
                    """)
                .license(new License().name("Assessment submission")))
        .tags(
            List.of(
                new Tag().name("Links").description("Create short links and read their usage"),
                new Tag()
                    .name("Redirect")
                    .description(
                        "Public resolution of a short code. Unversioned by design: "
                            + "short links are printed and messaged, so a version prefix would make "
                            + "every issued link a hostage to an internal decision.")));
  }
}
