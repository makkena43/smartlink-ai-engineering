package com.smartlink.application.exception;

/**
 * No link exists for the requested code.
 *
 * <p>Carries no detail about the code beyond its absence, and the same response is returned for a
 * code that is merely malformed. Distinguishing "never existed" from "not a valid code shape" would
 * turn resolution into a probing oracle: with anonymous creation (GF-03) and unauthenticated
 * analytics (GF-12), possession of the code is the only access control there is, so anything that
 * narrows a guess is a real weakness rather than a theoretical one.
 */
public class LinkNotFoundException extends SmartLinkException {

  public LinkNotFoundException(String operatorMessage) {
    super(operatorMessage);
  }

  @Override
  public String safeDetail() {
    return "This short link does not exist.";
  }
}
