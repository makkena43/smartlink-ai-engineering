package com.smartlink.domain;

/**
 * Why a destination was refused.
 *
 * <p>Each constant carries a stable rule name that is safe to return to an untrusted caller. The
 * name comes from a fixed vocabulary the service controls, so an error response can say precisely
 * what was violated <em>without ever quoting the submitted value back</em> — which is how a
 * validation endpoint would otherwise become the reflected-XSS vector it exists to prevent.
 *
 * <p>The names are part of the public API contract. A caller may branch on them, so renaming one is
 * a breaking change even though nothing in the compiler will say so.
 */
public enum PolicyViolation {

  /**
   * Longer than the configured maximum. Checked before parsing, so oversized input costs nothing.
   */
  TOO_LONG("destination.length"),

  /**
   * Contains CR, LF, NUL or a raw tab.
   *
   * <p>The control specific to being a redirect service: the destination is written into a {@code
   * Location} response header, so an embedded CRLF is a response-splitting primitive able to forge
   * a body or poison an intermediary cache.
   */
  CONTROL_CHARACTERS("destination.control-characters"),

  /** Not a syntactically valid absolute URI. */
  UNPARSEABLE("destination.syntax"),

  /** Scheme is not {@code http} or {@code https}. */
  SCHEME_NOT_ALLOWED("destination.scheme"),

  /** No host component — a relative reference, or a scheme-only string. */
  HOST_MISSING("destination.host"),

  /**
   * The host resolves to an address that must never be reachable through this service: loopback,
   * private, link-local, carrier-grade NAT, multicast, reserved, or cloud metadata.
   */
  BLOCKED_ADDRESS("destination.address-range"),

  /**
   * The host could not be resolved.
   *
   * <p>Rejected rather than accepted (NFR-16). Accepting the unverifiable would make DNS failure
   * the bypass for every other address rule below it — an attacker would only need the resolver to
   * be slow.
   */
  UNRESOLVABLE("destination.unresolvable");

  private final String ruleName;

  PolicyViolation(String ruleName) {
    this.ruleName = ruleName;
  }

  public String ruleName() {
    return ruleName;
  }
}
