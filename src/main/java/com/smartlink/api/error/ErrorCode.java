package com.smartlink.api.error;

import org.springframework.http.HttpStatus;

/**
 * The public error vocabulary, per engineering-spec.md §4.4.
 *
 * <p>These codes are part of the API contract. A caller may branch on them, so renaming one is a
 * breaking change even though nothing in the compiler will say so.
 *
 * <p>Every message here is written to be returned to an untrusted caller: actionable enough to be
 * useful, specific enough to be trusted, and carrying nothing about how the service is built
 * (NFR-04).
 */
public enum ErrorCode {

  /**
   * The request itself could not be understood — unparseable body, missing required field, wrong
   * type. Distinct from {@link #INVALID_URL}: this one means "I could not read what you sent".
   */
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request could not be parsed."),

  /**
   * The request was understood perfectly and the destination was declined by policy.
   *
   * <p>422 rather than 400 is a deliberate distinction. A caller retrying a 400 should fix their
   * serialisation; a caller retrying a 422 should change the URL. Collapsing both into 400 tells
   * them neither.
   */
  INVALID_URL(HttpStatus.UNPROCESSABLE_ENTITY, "The destination URL is invalid or unsupported."),

  /** No link exists for this code, or the code is not a valid shape. Deliberately the same. */
  LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "This short link does not exist."),

  /**
   * The link existed and has passed its expiry (scenario 02).
   *
   * <p>Kept distinct from {@link #LINK_NOT_FOUND} because 404 and 410 answer different questions:
   * "never existed" versus "existed, deliberately ended". An operator uses that difference to tell
   * a typo from a finished campaign without touching the database.
   *
   * <p>Additive: no state that previously returned 404 or 302 returns this instead. A link only
   * reaches it if someone gave it an expiry.
   */
  LINK_EXPIRED(HttpStatus.GONE, "This short link is no longer active."),

  /**
   * A supplied expiration timestamp was malformed, zone-ambiguous, or not in the future.
   *
   * <p>400 rather than the 422 used for a rejected destination: this is a request the caller got
   * wrong and can fix, not a well-formed one the server declined on policy grounds.
   */
  INVALID_EXPIRY(HttpStatus.BAD_REQUEST, "The expiration time is invalid."),

  /**
   * Reserved for the production rate limiter. Present in the vocabulary so the contract is stable
   * when it arrives, and <strong>not implemented in the prototype</strong> — requirements §6 puts
   * distributed rate limiting out of scope, and NFR-09 asks only that the design define it.
   */
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later."),

  /**
   * A dependency is unreachable, or code allocation exhausted its attempts. Retryable.
   *
   * <p>Kept strictly separate from {@link #INTERNAL_ERROR}: this means come back, that means
   * someone must look. An operator uses exactly this distinction to decide whether to page.
   */
  SERVICE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "The service is temporarily unavailable. Please try again later."),

  /**
   * Nobody predicted this. Reserved for genuinely unanticipated failures, so that its presence in a
   * log is meaningful rather than routine.
   */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

  private final HttpStatus status;
  private final String safeMessage;

  ErrorCode(HttpStatus status, String safeMessage) {
    this.status = status;
    this.safeMessage = safeMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String safeMessage() {
    return safeMessage;
  }
}
