package com.smartlink.domain;

import com.smartlink.domain.port.HostResolver;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether a submitted string may become a redirect destination.
 *
 * <p>A URL shortener is an <strong>open redirector by construction</strong>. This policy therefore
 * does not try to stop redirection; it bounds what can be redirected to, and what a submitted
 * string can do to the service on its way through.
 *
 * <p><strong>The stage order is load-bearing: normalise first, then decide.</strong> A validator
 * that decides before normalising is inspecting a string the rest of the system will never see —
 * which is exactly how {@code http://expected.com@169.254.169.254/} passes a substring check while
 * actually addressing the cloud metadata endpoint. Everything before {@code @} is userinfo and is
 * discarded by any real parser.
 *
 * <p>Cheap rejections come first so that hostile input costs as little as possible: an over-length
 * body is refused before parsing, and control characters before that.
 *
 * <p>No framework, no I/O. DNS arrives through {@link HostResolver}, which is what lets every rule
 * here be proven with a stubbed resolver and no network (NFR-15).
 */
public final class DestinationPolicy {

  /** Allowlist, never a denylist. A denylist bets you enumerated every dangerous scheme. */
  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

  public static final int DEFAULT_MAX_LENGTH = 2048;

  private final HostResolver resolver;
  private final int maxLength;

  public DestinationPolicy(HostResolver resolver) {
    this(resolver, DEFAULT_MAX_LENGTH);
  }

  public DestinationPolicy(HostResolver resolver, int maxLength) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.maxLength = maxLength;
  }

  /** Outcome of evaluation. Exhaustive, so a caller cannot forget the rejection branch. */
  public sealed interface Result {

    record Accepted(Destination destination) implements Result {}

    record Rejected(PolicyViolation violation) implements Result {}
  }

  public Result evaluate(String raw) {
    if (raw == null || raw.isBlank()) {
      return new Result.Rejected(PolicyViolation.UNPARSEABLE);
    }
    if (raw.length() > maxLength) {
      // Before parsing: a megabyte of input should not buy a megabyte of work.
      return new Result.Rejected(PolicyViolation.TOO_LONG);
    }
    if (containsControlCharacter(raw)) {
      return new Result.Rejected(PolicyViolation.CONTROL_CHARACTERS);
    }

    URI uri;
    try {
      uri = new URI(raw);
    } catch (URISyntaxException e) {
      return new Result.Rejected(PolicyViolation.UNPARSEABLE);
    }
    if (!uri.isAbsolute() || uri.getScheme() == null) {
      return new Result.Rejected(PolicyViolation.UNPARSEABLE);
    }
    if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
      return new Result.Rejected(PolicyViolation.SCHEME_NOT_ALLOWED);
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      // java.net.URI returns null here more often than is obvious. Its hostname grammar
      // requires the final label to begin with a letter, so every numeric spelling of an
      // address - 0251.0376.0251.0376, 169.254.43518, 127.1 - fails getHost() outright.
      //
      // Rejecting on that basis alone would "work": the caller gets a 422 either way. But it
      // would be rejection by ACCIDENT, resting on a parser quirk rather than on the address
      // policy, and a future parser that becomes more permissive would silently open the
      // hole with no test noticing. So the authority is recovered from the raw URI and
      // classified properly, and HOST_MISSING is reserved for something that genuinely has
      // no host.
      host = rawAuthorityHost(uri);
      if (host == null || host.isBlank()) {
        return new Result.Rejected(PolicyViolation.HOST_MISSING);
      }
    }

    return evaluateHost(host, raw);
  }

  /**
   * Recovers the host from the raw authority when {@link URI#getHost()} declines to parse it.
   *
   * <p>Userinfo is dropped at the <strong>last</strong> {@code @}, not the first. That detail is
   * the whole credential-embedded attack: in {@code http://expected.com@169.254.169.254/}
   * everything before the {@code @} is a username, so the real host is what follows — and splitting
   * on the first {@code @} instead would hand back {@code expected.com} for a URL carrying two.
   */
  private static String rawAuthorityHost(URI uri) {
    String authority = uri.getRawAuthority();
    if (authority == null || authority.isBlank()) {
      return null;
    }

    int userInfoEnd = authority.lastIndexOf('@');
    if (userInfoEnd >= 0) {
      authority = authority.substring(userInfoEnd + 1);
    }

    if (authority.startsWith("[")) {
      int close = authority.indexOf(']');
      return close < 0 ? null : authority.substring(0, close + 1);
    }
    int portStart = authority.indexOf(':');
    return portStart < 0 ? authority : authority.substring(0, portStart);
  }

  private Result evaluateHost(String host, String raw) {
    Optional<InetAddress> literal = HostLiterals.parse(host);
    if (literal.isPresent()) {
      // Already an address in some notation. No DNS needed, and none is performed — which
      // also means an attacker cannot use a slow resolver to time out this check.
      return AddressPolicy.isBlocked(literal.get())
          ? new Result.Rejected(PolicyViolation.BLOCKED_ADDRESS)
          : new Result.Accepted(new Destination(raw));
    }

    List<InetAddress> resolved = resolver.resolve(host);
    if (resolved.isEmpty()) {
      // Fail closed (NFR-16). Accepting the unverifiable would make resolver failure the
      // bypass for every address rule above.
      return new Result.Rejected(PolicyViolation.UNRESOLVABLE);
    }
    // EVERY address, not the first. A hostname with one public and one private A record
    // defeats a first-address-only check, and the caller does not control which arrives first.
    for (InetAddress address : resolved) {
      if (AddressPolicy.isBlocked(address)) {
        return new Result.Rejected(PolicyViolation.BLOCKED_ADDRESS);
      }
    }
    return new Result.Accepted(new Destination(raw));
  }

  /**
   * Rejects control characters, whether written literally or percent-encoded.
   *
   * <p>The literal check is the obvious one. The percent-encoded check is the one that matters:
   * {@code %0d%0a} is inert as stored text, but any component that decodes the destination before
   * writing it — a redirect helper, a client library, an intermediary — turns it back into a real
   * CRLF inside a {@code Location} header. Rejecting both forms at creation means no such component
   * can ever be handed the payload, rather than relying on every one of them to be careful.
   */
  private static boolean containsControlCharacter(String raw) {
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c < 0x20 || c == 0x7F) {
        return true;
      }
      if (c == '%' && i + 2 < raw.length()) {
        int decoded = hexPair(raw.charAt(i + 1), raw.charAt(i + 2));
        if (decoded >= 0 && (decoded < 0x20 || decoded == 0x7F)) {
          return true;
        }
      }
    }
    return false;
  }

  private static int hexPair(char high, char low) {
    int h = Character.digit(high, 16);
    int l = Character.digit(low, 16);
    return (h < 0 || l < 0) ? -1 : (h << 4) | l;
  }
}
