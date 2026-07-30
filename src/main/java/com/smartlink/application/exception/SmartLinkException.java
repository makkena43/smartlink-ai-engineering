package com.smartlink.application.exception;

/**
 * Base type for conditions the service understands and can describe safely to a caller.
 *
 * <p>Deliberately carries no HTTP status. Mapping a condition to a status code is a transport
 * decision, and it lives in {@code api.error.ErrorCode}. Putting a status here would push HTTP
 * knowledge down into the application layer and make these types unusable from any non-HTTP entry
 * point — a batch import or a message consumer would have to care what 422 means.
 *
 * <p>Anything not descended from this type is, by definition, unanticipated, and maps to {@code
 * INTERNAL_ERROR}. That split is what keeps the 500 signal meaningful: a 500 means nobody predicted
 * this, and someone needs to look.
 */
public abstract class SmartLinkException extends RuntimeException {

  protected SmartLinkException(String message) {
    super(message);
  }

  protected SmartLinkException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Detail safe to return to an untrusted caller.
   *
   * <p>Separate from {@link #getMessage()} on purpose. The message is for operators and may name
   * internal state; this is for the public and may not. Collapsing the two is how internal detail
   * reaches an error body (NFR-04).
   */
  public abstract String safeDetail();
}
