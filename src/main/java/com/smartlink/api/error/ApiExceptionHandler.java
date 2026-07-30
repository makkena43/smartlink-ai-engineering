package com.smartlink.api.error;

import com.smartlink.api.CorrelationIdFilter;
import com.smartlink.application.exception.DependencyUnavailableException;
import com.smartlink.application.exception.InvalidDestinationException;
import com.smartlink.application.exception.LinkNotFoundException;
import com.smartlink.application.exception.SmartLinkException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every failure to an RFC 9457 {@code application/problem+json} response.
 *
 * <p>Two rules hold on every branch below, and they are the whole point of centralising this:
 *
 * <ol>
 *   <li><strong>Nothing internal crosses the boundary.</strong> No stack trace, SQL state, database
 *       message, connection detail, or internal hostname. The public text comes from {@link
 *       ErrorCode} or {@link SmartLinkException#safeDetail()} — never from {@code getMessage()},
 *       which exists for operators and routinely names internal state.
 *   <li><strong>Submitted input is never echoed back.</strong> An error body that quotes an
 *       attacker-supplied destination is a reflected-XSS vector living inside the very endpoint
 *       added to reject dangerous destinations.
 * </ol>
 *
 * <p>The catch-all at the bottom is the reason both rules survive: a handler that enumerates known
 * failures and lets everything else fall through to the framework's default leaks whatever that
 * default happens to render.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(InvalidDestinationException.class)
  public ResponseEntity<ProblemDetail> onInvalidDestination(
      InvalidDestinationException ex, HttpServletRequest request) {
    // The rule name is safe: it is drawn from a fixed vocabulary the service controls, never
    // from the submitted value.
    log.info("Destination rejected: rule={}", ex.violatedRule());
    return problem(ErrorCode.INVALID_URL, ex.safeDetail(), ex.violatedRule(), request);
  }

  @ExceptionHandler(LinkNotFoundException.class)
  public ResponseEntity<ProblemDetail> onNotFound(
      LinkNotFoundException ex, HttpServletRequest request) {
    return problem(ErrorCode.LINK_NOT_FOUND, ex.safeDetail(), null, request);
  }

  @ExceptionHandler(DependencyUnavailableException.class)
  public ResponseEntity<ProblemDetail> onDependencyUnavailable(
      DependencyUnavailableException ex, HttpServletRequest request) {
    // WARN, not ERROR: this is an anticipated failure with a defined response. Logging it at
    // ERROR would train operators to ignore ERROR, which is how a real incident gets missed.
    log.warn("Dependency unavailable: {}", ex.getMessage());
    return problem(ErrorCode.SERVICE_UNAVAILABLE, ex.safeDetail(), null, request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    HttpRequestMethodNotSupportedException.class
  })
  public ResponseEntity<ProblemDetail> onMalformedRequest(
      Exception ex, HttpServletRequest request) {
    // Deliberately does not report *which* field failed. Spring's default binding message
    // includes the rejected value, which would echo submitted input straight back.
    return problem(
        ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.safeMessage(), null, request);
  }

  /**
   * An unmatched path.
   *
   * <p>Both types are handled because Spring raises different ones depending on how the request
   * misses: {@link NoResourceFoundException} for a path no handler or resource claims (the common
   * case since Spring Boot 3.2), {@link NoHandlerFoundException} when handler-not-found throwing is
   * enabled.
   *
   * <p><strong>This branch exists because its absence was a real bug.</strong> Without it the
   * catch-all below swallowed these and returned 500 for every unmatched URL — so a typo, a
   * scanner, or a probe for an unexposed actuator endpoint all looked like an internal failure.
   * That is precisely the corruption of the 500 signal that {@link ErrorCode#INTERNAL_ERROR}
   * promises not to allow: if routine misses raise 500, nobody can use 500 to decide whether to
   * investigate. Caught by the actuator-exposure test, which is worth noting — no test of the error
   * contract itself would have found it.
   */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<ProblemDetail> onNoHandler(Exception ex, HttpServletRequest request) {
    return problem(ErrorCode.LINK_NOT_FOUND, ErrorCode.LINK_NOT_FOUND.safeMessage(), null, request);
  }

  /**
   * The datastore could not be reached or could not answer.
   *
   * <p>Mapped to 503 rather than falling through to 500, and that distinction is NFR-02 made real:
   * when the mapping cannot be verified the service must fail in a way that says <em>come
   * back</em>, not one that says <em>something is broken</em>. The alternative — a redirect served
   * from a guess or a stale value — would break the product's only real promise, that a short link
   * goes where its owner said it goes.
   *
   * <p>{@link TransactionException} is included because a connection failure raised while a
   * transaction is being opened surfaces as {@code CannotCreateTransactionException}, which
   * descends from {@code TransactionException} and NOT from {@code DataAccessException}. Without it
   * the create path returned 500 during a database outage while the redirect path correctly
   * returned 503 - the same failure reported two different ways depending on which endpoint you
   * happened to hit. Found by fault injection; no healthy-database test could see it.
   *
   * <p>Caught here rather than translated in the persistence adapter, because the adapter lives in
   * {@code infrastructure} and the exception vocabulary lives in {@code application}: translating
   * there would mean infrastructure depending on application, which the layering rule forbids.
   * Transport is the right place to decide what a failure looks like on the wire anyway.
   */
  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<ProblemDetail> onDataAccessFailure(
      RuntimeException ex, HttpServletRequest request) {
    // The message may name the database, the host, or the SQL. It goes to the log and never
    // to the caller.
    log.warn("Datastore unavailable: {}", ex.getMessage());
    return problem(
        ErrorCode.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE.safeMessage(), null, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> onUnexpected(Exception ex, HttpServletRequest request) {
    // The only place a stack trace is recorded, and it goes to the log, never to the caller.
    // The correlation ID is what lets an operator find this entry from the response the user
    // received.
    log.error("Unhandled failure", ex);
    return problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.safeMessage(), null, request);
  }

  private ResponseEntity<ProblemDetail> problem(
      ErrorCode code, String detail, String violatedRule, HttpServletRequest request) {

    ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
    body.setTitle(code.status().getReasonPhrase());
    body.setProperty("code", code.name());
    body.setProperty("requestId", CorrelationIdFilter.currentId(request));
    if (violatedRule != null) {
      body.setProperty("rule", violatedRule);
    }

    return ResponseEntity.status(code.status())
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        .body(body);
  }
}
