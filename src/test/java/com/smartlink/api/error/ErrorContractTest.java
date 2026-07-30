package com.smartlink.api.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartlink.api.CorrelationIdFilter;
import com.smartlink.api.dto.CreateLinkRequest;
import com.smartlink.application.exception.DependencyUnavailableException;
import com.smartlink.application.exception.InvalidDestinationException;
import com.smartlink.application.exception.LinkNotFoundException;
import jakarta.validation.Valid;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T2 acceptance: the public error contract in engineering-spec.md §4.4.
 *
 * <p>Driven through a test-only controller that raises each condition on demand. The alternative —
 * waiting for the real controllers in T5 and T6 — would mean the error contract is first exercised
 * by tests whose actual subject is something else, and a contract nobody tests directly is a
 * contract nobody notices breaking.
 *
 * <p>Standalone MockMvc rather than a Spring context: this is a transport-layer concern with no
 * database in it, and a test that needs a container is a test that stops being run.
 */
class ErrorContractTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
  }

  @Nested
  @DisplayName("status and code mapping")
  class Mapping {

    @Test
    @DisplayName("policy rejection is 422 INVALID_URL, not 400")
    void invalidDestinationIs422() throws Exception {
      mvc.perform(get("/test/invalid-destination"))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("INVALID_URL"))
          .andExpect(jsonPath("$.status").value(422))
          .andExpect(jsonPath("$.rule").value("destination.scheme"))
          .andExpect(jsonPath("$.detail").value("The destination URL is invalid or unsupported."));
    }

    @Test
    @DisplayName("unknown link is 404 LINK_NOT_FOUND")
    void notFoundIs404() throws Exception {
      mvc.perform(get("/test/not-found"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("dependency failure is 503, never 500 — retryable is a different signal")
    void dependencyUnavailableIs503() throws Exception {
      mvc.perform(get("/test/dependency-down"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("unanticipated failure is 500 INTERNAL_ERROR")
    void unexpectedIs500() throws Exception {
      mvc.perform(get("/test/boom"))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    @DisplayName("unparseable body is 400 MALFORMED_REQUEST, distinct from 422")
    void malformedBodyIs400() throws Exception {
      mvc.perform(
              post("/test/create")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{ this is not json"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("missing required field is 400, not 422")
    void missingFieldIs400() throws Exception {
      mvc.perform(post("/test/create").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
  }

  @Nested
  @DisplayName("nothing internal crosses the boundary (NFR-04)")
  class NoLeakage {

    @Test
    @DisplayName("submitted input is never reflected in the error body")
    void doesNotReflectSubmittedInput() throws Exception {
      // The operator-facing message deliberately contains the payload; the public body must
      // not. Reflecting an attacker-supplied destination is how a validation endpoint becomes
      // the XSS vector it was added to prevent.
      MvcResult result = mvc.perform(get("/test/invalid-destination")).andReturn();
      String body = result.getResponse().getContentAsString();

      Assertions.assertThat(body)
          .doesNotContain("<script>")
          .doesNotContain("javascript:")
          .doesNotContain("alert(1)");
    }

    @Test
    @DisplayName("no stack trace, exception class, or internal detail is disclosed")
    void doesNotLeakImplementation() throws Exception {
      MvcResult result = mvc.perform(get("/test/boom")).andReturn();
      String body = result.getResponse().getContentAsString();

      Assertions.assertThat(body)
          .doesNotContain("java.lang")
          .doesNotContain("com.smartlink")
          .doesNotContain("Exception")
          .doesNotContain("at ")
          .doesNotContain("jdbc")
          .doesNotContain("postgres")
          .doesNotContain("connection pool secret");
    }

    @Test
    @DisplayName("dependency failures disclose nothing about the dependency")
    void dependencyFailureNamesNoDependency() throws Exception {
      MvcResult result = mvc.perform(get("/test/dependency-down")).andReturn();
      String body = result.getResponse().getContentAsString();

      Assertions.assertThat(body)
          .doesNotContain("PostgreSQL")
          .doesNotContain("db-primary.internal")
          .doesNotContain("5432");
    }
  }

  @Nested
  @DisplayName("correlation")
  class Correlation {

    @Test
    @DisplayName("every error carries a requestId and the response header")
    void errorsCarryRequestId() throws Exception {
      mvc.perform(get("/test/not-found").header(CorrelationIdFilter.HEADER, "abc-123"))
          .andExpect(jsonPath("$.requestId").value("abc-123"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                  .string(CorrelationIdFilter.HEADER, "abc-123"));
    }

    @Test
    @DisplayName("a requestId is generated when the caller supplies none")
    void requestIdGeneratedWhenAbsent() throws Exception {
      MvcResult result = mvc.perform(get("/test/not-found")).andReturn();

      Assertions.assertThat(result.getResponse().getHeader(CorrelationIdFilter.HEADER))
          .isNotBlank()
          .doesNotContain("unknown");
    }
  }

  /** Raises each contract condition on demand. Test scope only. */
  @RestController
  @RequestMapping("/test")
  static class ThrowingController {

    @org.springframework.web.bind.annotation.GetMapping("/invalid-destination")
    void invalidDestination() {
      throw new InvalidDestinationException(
          "destination.scheme",
          "rejected scheme for javascript:alert(1) submitted as <script>x</script>");
    }

    @org.springframework.web.bind.annotation.GetMapping("/not-found")
    void notFound() {
      throw new LinkNotFoundException("no row for code aB92xK7");
    }

    @org.springframework.web.bind.annotation.GetMapping("/dependency-down")
    void dependencyDown() {
      throw new DependencyUnavailableException(
          "PostgreSQL db-primary.internal:5432 unreachable after 1 retry");
    }

    @org.springframework.web.bind.annotation.GetMapping("/boom")
    void boom() {
      throw new IllegalStateException("connection pool secret leaked into a message");
    }

    @PostMapping("/create")
    void create(@Valid @RequestBody CreateLinkRequest request) {
      // Never reached in these tests; exists so binding and validation failures are exercised.
    }
  }
}
