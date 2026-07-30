package com.smartlink.application.exception;

/**
 * A supplied expiry could not be accepted — unparseable, missing a zone, or not in the future.
 *
 * <p>Maps to {@code 400}, unlike a rejected destination which maps to {@code 422}. The split is not
 * arbitrary: a destination refused by policy is a well-formed request the server understood and
 * declined, whereas an expiry that is malformed or already past is a request the caller has simply
 * got wrong and can fix by sending different bytes.
 *
 * <p>The submitted timestamp is never echoed back. It is less obviously dangerous than a URL, but
 * the rule that error bodies do not reflect input is only worth anything if it holds everywhere.
 */
public class InvalidExpiryException extends SmartLinkException {

  private final String reason;

  public InvalidExpiryException(String reason, String operatorMessage) {
    super(operatorMessage);
    this.reason = reason;
  }

  /** Fixed vocabulary the service controls, safe to publish. */
  public String reason() {
    return reason;
  }

  @Override
  public String safeDetail() {
    return "The expiration time is invalid. It must be a UTC instant in the future.";
  }
}
