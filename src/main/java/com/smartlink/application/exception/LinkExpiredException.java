package com.smartlink.application.exception;

/**
 * The link exists but has passed its expiry instant.
 *
 * <p>Maps to {@code 410 Gone}, not {@code 404}. The distinction is deliberate and useful: {@code
 * 404} means "never existed", {@code 410} means "existed, deliberately ended". That is what lets an
 * operator — or a campaign owner reading an access log — tell a typo apart from a finished campaign
 * <em>without querying the database</em>. Collapsing both into {@code 404} throws that signal away
 * permanently and saves nothing.
 *
 * <p>It also discloses nothing an attacker can use: knowing a code once existed requires already
 * possessing the code, which is the only access control this prototype has.
 */
public class LinkExpiredException extends SmartLinkException {

  public LinkExpiredException(String operatorMessage) {
    super(operatorMessage);
  }

  @Override
  public String safeDetail() {
    return "This short link is no longer active.";
  }
}
