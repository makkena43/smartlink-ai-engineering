package com.smartlink.application.exception;

/**
 * A destination URL was well-formed as a request but rejected by policy — unsupported scheme,
 * blocked address range, over-length, or containing control characters.
 *
 * <p>Maps to 422, not 400. The server parsed the request perfectly and declined it, and a caller
 * needs to tell that apart from "I sent you something unparseable" to know whether a corrected URL
 * is worth sending.
 *
 * <p>The violated rule is carried separately so the response can name it without ever quoting the
 * submitted value back. Reflecting attacker-supplied input into an error body is how a validation
 * endpoint becomes the XSS vector it was added to prevent.
 */
public class InvalidDestinationException extends SmartLinkException {

  private final String violatedRule;

  public InvalidDestinationException(String violatedRule, String operatorMessage) {
    super(operatorMessage);
    this.violatedRule = violatedRule;
  }

  public String violatedRule() {
    return violatedRule;
  }

  @Override
  public String safeDetail() {
    return "The destination URL is invalid or unsupported.";
  }
}
