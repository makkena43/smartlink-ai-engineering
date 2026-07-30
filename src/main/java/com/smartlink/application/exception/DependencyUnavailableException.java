package com.smartlink.application.exception;

/**
 * A required dependency could not be reached within its bounded retry allowance, so the request
 * cannot be served correctly.
 *
 * <p>Maps to 503, never 500, and never to a redirect. Two reasons, and the second is the one that
 * matters:
 *
 * <ul>
 *   <li>503 tells the caller to come back; 500 tells an operator to investigate. Collapsing them
 *       destroys the signal that decides whether a page is warranted.
 *   <li>NFR-02 requires that the service never issue an unverified redirect. When the mapping
 *       cannot be read, failing loudly is the *correct* outcome — guessing, or serving something
 *       stale, would break the product's only real promise, that a short link goes where its owner
 *       said it goes.
 * </ul>
 *
 * <p>Also raised when short-code allocation exhausts its candidate attempts. Nothing is broken in
 * that case either; the attempts were consumed and the request is safely retryable, which is
 * exactly what 503 means.
 */
public class DependencyUnavailableException extends SmartLinkException {

  public DependencyUnavailableException(String operatorMessage) {
    super(operatorMessage);
  }

  public DependencyUnavailableException(String operatorMessage, Throwable cause) {
    super(operatorMessage, cause);
  }

  @Override
  public String safeDetail() {
    return "The service is temporarily unavailable. Please try again later.";
  }
}
