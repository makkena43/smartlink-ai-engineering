package com.smartlink.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartlink.api.error.ApiExceptionHandler;
import com.smartlink.application.ResolveLinkUseCase;
import com.smartlink.application.exception.LinkNotFoundException;
import com.smartlink.domain.Destination;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** T6 acceptance: the wire semantics of a redirect. */
class RedirectControllerTest {

  private final ResolveLinkUseCase resolveLink = Mockito.mock(ResolveLinkUseCase.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new RedirectController(resolveLink))
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  private void given(String code, String destination) {
    when(resolveLink.resolve(code))
        .thenReturn(
            new Link(
                ShortCode.of(code), Destination.ofStoredValue(destination), Instant.EPOCH, 0L));
  }

  @Test
  @DisplayName("a known code returns 302 with the destination in Location (GF-07, GF-08)")
  void returns302WithLocation() throws Exception {
    given("aB92xK7", "https://example.com/campaign");

    mvc.perform(get("/aB92xK7"))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/campaign"));
  }

  @Test
  @DisplayName("the Location header is byte-identical, never re-encoded")
  void locationIsByteIdentical() throws Exception {
    // ResponseEntity.location(URI) would write URI.toASCIIString() and re-encode this. The
    // difference is invisible in most URLs and fatal in signed ones, where changing a single
    // escape invalidates the signature - and the failure would appear as a broken campaign
    // rather than as anything traceable to here.
    String destination = "https://example.com/a%20b/c?z=1&a=%2F&e=a+b#Frag";
    given("aB92xK7", destination);

    mvc.perform(get("/aB92xK7")).andExpect(header().string("Location", destination));
  }

  @Test
  @DisplayName("the redirect is not cacheable (GF-11 depends on it)")
  void redirectIsNotCacheable() throws Exception {
    given("aB92xK7", "https://example.com/campaign");

    // A cached redirect stops reaching the service, so the count silently undercounts by an
    // amount nobody can measure. Choosing 302 over 301 buys nothing without this header.
    mvc.perform(get("/aB92xK7")).andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  @DisplayName("an unknown code returns 404 and no Location (GF-09)")
  void unknownCodeReturns404() throws Exception {
    when(resolveLink.resolve(anyString())).thenThrow(new LinkNotFoundException("no link"));

    mvc.perform(get("/zzzzzzz"))
        .andExpect(status().isNotFound())
        .andExpect(header().doesNotExist("Location"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/links", // management API
        "/actuator/health", // operations
        "/v3/api-docs", // generated contract
        "/swagger-ui.html",
        "/short", // too short to be a code
        "/toolongforacode",
        "/has-hyphen"
      })
  @DisplayName("non-code paths are never captured by the redirect handler (GF-16)")
  void nonCodePathsAreNotCaptured(String path) throws Exception {
    // Routing precedence enforced by the path pattern rather than by registration order.
    // A code can never shadow an operational endpoint, and a future change to code length
    // would have to change the pattern - which makes the coupling visible instead of latent.
    mvc.perform(get(path)).andExpect(status().isNotFound());
    Mockito.verifyNoInteractions(resolveLink);
  }
}
