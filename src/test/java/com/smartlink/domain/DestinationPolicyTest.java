package com.smartlink.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.domain.port.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T4 acceptance: the destination policy.
 *
 * <p>Runs with a stubbed resolver and no network, which is the entire reason DNS sits behind a
 * port. A policy whose tests need working DNS is a policy whose tests get skipped on the day
 * someone is in a hurry.
 *
 * <p>The notation cases are table-driven on purpose. Encoding-evasion bugs are found by
 * <em>enumerating</em> notations, not by reasoning about them — the failure is always a spelling
 * nobody thought of, so adding one has to cost a single line.
 */
class DestinationPolicyTest {

  private static final String METADATA = "169.254.169.254";

  private final StubResolver resolver = new StubResolver();
  private final DestinationPolicy policy = new DestinationPolicy(resolver);

  private PolicyViolation rejectionFor(String url) {
    DestinationPolicy.Result result = policy.evaluate(url);
    assertThat(result)
        .as("expected %s to be rejected", url)
        .isInstanceOf(DestinationPolicy.Result.Rejected.class);
    return ((DestinationPolicy.Result.Rejected) result).violation();
  }

  private Destination acceptanceOf(String url) {
    DestinationPolicy.Result result = policy.evaluate(url);
    assertThat(result)
        .as("expected %s to be accepted", url)
        .isInstanceOf(DestinationPolicy.Result.Accepted.class);
    return ((DestinationPolicy.Result.Accepted) result).destination();
  }

  // ---------------------------------------------------------------------------------------

  @Nested
  @DisplayName("scheme allowlist (GF-14)")
  class Schemes {

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/", "https://example.com/", "HTTPS://example.com/"})
    @DisplayName("http and https are accepted, case-insensitively")
    void allowsHttpAndHttps(String url) {
      resolver.with("example.com", "93.184.216.34");

      assertThat(acceptanceOf(url).value()).isEqualTo(url);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
          "javascript:alert(1)", // stored XSS behind a trusted-looking link
          "data:text/html;base64,PHNjcmlwdD4=",
          "file:///etc/passwd",
          "vbscript:msgbox(1)",
          "ftp://example.com/x",
          "mailto:someone@example.com",
          "gopher://example.com/",
          "jar:http://example.com/a.jar!/"
        })
    @DisplayName("every other scheme is refused — allowlist, never denylist")
    void refusesEveryOtherScheme(String url) {
      assertThat(rejectionFor(url)).isEqualTo(PolicyViolation.SCHEME_NOT_ALLOWED);
    }

    @Test
    @DisplayName("a relative reference has no scheme and is refused")
    void refusesRelativeReference() {
      assertThat(rejectionFor("/just/a/path")).isEqualTo(PolicyViolation.UNPARSEABLE);
    }
  }

  // ---------------------------------------------------------------------------------------

  @Nested
  @DisplayName("notation evasion — every spelling of the metadata address (GF-16)")
  class NotationTable {

    /**
     * All of these denote 169.254.169.254. A validator comparing host strings rejects the first and
     * admits the rest, which is why the host is converted to an address before any rule runs.
     *
     * <p>Two entries here were originally wrong — the octal and two-part forms carried values that
     * are not this address at all — and the suite failed on them. Worth recording, because the
     * failure is evidence of something the passing cases cannot show: the policy is genuinely
     * <em>evaluating</em> these hosts rather than reflexively refusing anything that looks unusual.
     * A blanket-reject implementation would have passed the bad fixtures too.
     */
    @ParameterizedTest(name = "{1}: {0}")
    @CsvSource({
      "http://169.254.169.254/,               dotted quad",
      "http://2852039166/,                    single decimal",
      "http://0xA9FEA9FE/,                    single hexadecimal",
      "http://0xa9fea9fe/,                    lowercase hexadecimal",
      "http://025177524776/,                  single octal",
      "http://0251.0376.0251.0376/,           dotted octal",
      "http://0xA9.0xFE.0xA9.0xFE/,           dotted hexadecimal",
      "http://169.254.43518/,                 three-part mixed",
      "http://169.16689662/,                  two-part mixed",
      "http://[::ffff:169.254.169.254]/,      IPv6-mapped",
      "http://expected.com@169.254.169.254/,  credential-embedded",
      "http://user:pass@169.254.169.254/,     credential-embedded with password"
    })
    @DisplayName("is rejected as a blocked address")
    void rejectsEverySpellingOfMetadata(String url, String notation) {
      assertThat(rejectionFor(url))
          .as("%s (%s) must be refused exactly like its plain form", url, notation)
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
    }

    @Test
    @DisplayName("the credential-embedded form is the one a substring check misses")
    void credentialEmbeddedHostIsTheAuthorityAfterTheAt() {
      // Reads as expected.com to a human and to any check that scans for a substring, because
      // everything before '@' is userinfo and is discarded by a real parser. The authority
      // AFTER the '@' is the host, and that is what gets evaluated.
      resolver.with("expected.com", "93.184.216.34");

      assertThat(rejectionFor("http://expected.com@" + METADATA + "/"))
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
      // Control: the same hostname on its own is fine, so the rejection above is about the
      // real host and not about the presence of an '@'.
      assertThat(acceptanceOf("http://expected.com/")).isNotNull();
    }
  }

  // ---------------------------------------------------------------------------------------

  @Nested
  @DisplayName("blocked address ranges (GF-15)")
  class BlockedRanges {

    @ParameterizedTest(name = "{1}")
    @CsvSource({
      "http://127.0.0.1/,          loopback",
      "http://127.1/,              loopback shorthand",
      "http://10.0.0.1/,           private class A",
      "http://172.16.0.1/,         private class B",
      "http://192.168.1.1/,        private class C",
      "http://169.254.1.1/,        link-local",
      "http://100.64.0.1/,         carrier-grade NAT",
      "http://0.0.0.0/,            unspecified",
      "http://0.1.2.3/,            this-network",
      "http://224.0.0.1/,          multicast",
      "http://255.255.255.255/,    broadcast",
      "http://240.0.0.1/,          reserved",
      "http://198.18.0.1/,         benchmarking",
      "http://192.0.2.1/,          documentation",
      "http://[::1]/,              IPv6 loopback",
      "http://[fc00::1]/,          IPv6 unique-local",
      "http://[fd00::1]/,          IPv6 unique-local",
      "http://[fe80::1]/,          IPv6 link-local",
      "http://[::]/,               IPv6 unspecified"
    })
    @DisplayName("is refused")
    void refusesBlockedRange(String url, String description) {
      assertThat(rejectionFor(url))
          .as("%s (%s)", url, description)
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
    }

    @Test
    @DisplayName("IPv6 unique-local is caught even though isSiteLocalAddress does not see it")
    void ipv6UniqueLocalIsCaught() {
      // Java's isSiteLocalAddress() only recognises the deprecated fec0::/10. Relying on it
      // alone would accept the entire modern IPv6 private range.
      assertThat(rejectionFor("http://[fd12:3456:789a::1]/"))
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://8.8.8.8/", "https://93.184.216.34/path?q=1"})
    @DisplayName("public literals are accepted")
    void acceptsPublicLiterals(String url) {
      assertThat(acceptanceOf(url).value()).isEqualTo(url);
    }
  }

  // ---------------------------------------------------------------------------------------

  @Nested
  @DisplayName("resolution")
  class Resolution {

    @Test
    @DisplayName("every resolved address is checked, not merely the first")
    void checksEveryResolvedAddress() {
      // A host with one public and one private record. Which arrives first is not something
      // the caller controls, so a first-address-only check is a coin flip rather than a rule.
      resolver.with("mixed.example.com", "93.184.216.34", "10.0.0.5");

      assertThat(rejectionFor("https://mixed.example.com/"))
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
    }

    @Test
    @DisplayName("order does not matter: private-first is refused the same way")
    void orderDoesNotMatter() {
      resolver.with("mixed2.example.com", "10.0.0.5", "93.184.216.34");

      assertThat(rejectionFor("https://mixed2.example.com/"))
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
    }

    @Test
    @DisplayName("an unresolvable host fails closed (NFR-16)")
    void unresolvableHostFailsClosed() {
      // Accepting the unverifiable would make resolver failure the bypass for every address
      // rule: an attacker would only need DNS to be slow.
      assertThat(rejectionFor("https://nowhere.invalid/")).isEqualTo(PolicyViolation.UNRESOLVABLE);
    }

    @Test
    @DisplayName(
        "a literal address is never resolved, so a slow resolver cannot be used as a bypass")
    void literalAddressSkipsResolution() {
      resolver.failIfCalled();

      assertThat(rejectionFor("http://" + METADATA + "/"))
          .isEqualTo(PolicyViolation.BLOCKED_ADDRESS);
    }
  }

  // ---------------------------------------------------------------------------------------

  @Nested
  @DisplayName("control characters and header integrity (GF-18)")
  class ControlCharacters {

    // Deliberately a MethodSource rather than @CsvSource. A CSV literal cannot carry a raw
    // CR, LF or tab through the parser intact - the values arrive trimmed or mangled, and the
    // test then passes for a reason unrelated to the one it claims.
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> literalControls() {
      return java.util.stream.Stream.of(
          org.junit.jupiter.params.provider.Arguments.of("https://example.com/a\rb", "literal CR"),
          org.junit.jupiter.params.provider.Arguments.of("https://example.com/a\nb", "literal LF"),
          org.junit.jupiter.params.provider.Arguments.of("https://example.com/a\tb", "literal tab"),
          org.junit.jupiter.params.provider.Arguments.of("https://example.com/a b", "literal NUL"),
          org.junit.jupiter.params.provider.Arguments.of("https://example.com/ab", "literal DEL"));
    }

    @ParameterizedTest(name = "{1}")
    @org.junit.jupiter.params.provider.MethodSource("literalControls")
    @DisplayName("literal control characters are refused")
    void refusesLiteralControlCharacters(String url, String description) {
      assertThat(rejectionFor(url)).as(description).isEqualTo(PolicyViolation.CONTROL_CHARACTERS);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
      "https://example.com/%0d%0aX-Injected:%20yes,  encoded CRLF",
      "https://example.com/%0D%0A,                   encoded CRLF uppercase",
      "https://example.com/%00,                      encoded NUL",
      "https://example.com/%09,                      encoded tab",
      "https://example.com/%1b[31m,                  encoded escape"
    })
    @DisplayName("percent-encoded control characters are refused too")
    void refusesEncodedControlCharacters(String url, String description) {
      // Inert as stored text, but any component that decodes the destination before writing
      // it into a Location header turns this back into a real CRLF. Rejecting at creation
      // means no such component can ever be handed the payload.
      assertThat(rejectionFor(url)).as(description).isEqualTo(PolicyViolation.CONTROL_CHARACTERS);
    }

    @Test
    @DisplayName("ordinary percent-encoding is still accepted")
    void acceptsOrdinaryPercentEncoding() {
      resolver.with("example.com", "93.184.216.34");
      String url = "https://example.com/a%20b?q=%2Fvalue";

      // A blanket ban on '%' would break legitimate URLs. Only escapes that decode to a
      // control character are refused.
      assertThat(acceptanceOf(url).value()).isEqualTo(url);
    }
  }

  // ---------------------------------------------------------------------------------------

  @Nested
  @DisplayName("bounds and storage")
  class BoundsAndStorage {

    @Test
    @DisplayName("over-length is refused before parsing (GF-17)")
    void refusesOverLength() {
      String url = "https://example.com/" + "a".repeat(DestinationPolicy.DEFAULT_MAX_LENGTH);

      assertThat(rejectionFor(url)).isEqualTo(PolicyViolation.TOO_LONG);
    }

    @Test
    @DisplayName("the limit is configurable and enforced at the boundary")
    void limitIsConfigurable() {
      resolver.with("example.com", "93.184.216.34");
      DestinationPolicy strict = new DestinationPolicy(resolver, 30);

      assertThat(strict.evaluate("https://example.com/ok"))
          .isInstanceOf(DestinationPolicy.Result.Accepted.class);
      assertThat(strict.evaluate("https://example.com/far/too/long/for/thirty"))
          .isInstanceOf(DestinationPolicy.Result.Rejected.class);
    }

    @Test
    @DisplayName("the accepted destination is stored byte-identical, never normalised (GF-19)")
    void storesVerbatim() {
      resolver.with("example.com", "93.184.216.34");
      // Mixed case, encoded characters, query, fragment, trailing slash absent. Normalising
      // any of it would silently break signed URLs and tracking parameters, and the breakage
      // would surface as an underperforming campaign rather than as an error.
      String url = "https://example.com/A%20b/C?z=1&a=%2F&e=a+b#Frag";

      assertThat(acceptanceOf(url).value()).isEqualTo(url);
    }

    @Test
    @DisplayName("blank and null are refused rather than throwing")
    void refusesBlankInput() {
      assertThat(rejectionFor(null)).isEqualTo(PolicyViolation.UNPARSEABLE);
      assertThat(rejectionFor("   ")).isEqualTo(PolicyViolation.UNPARSEABLE);
    }

    @Test
    @DisplayName("a scheme with no host is refused")
    void refusesMissingHost() {
      assertThat(rejectionFor("http:///path")).isEqualTo(PolicyViolation.HOST_MISSING);
    }

    @Test
    @DisplayName("toString does not leak the destination (NFR-14)")
    void toStringDoesNotLeak() {
      resolver.with("example.com", "93.184.216.34");

      assertThat(acceptanceOf("https://example.com/reset?token=secret-value").toString())
          .doesNotContain("secret-value")
          .contains("chars");
    }
  }

  // ---------------------------------------------------------------------------------------

  /** In-memory resolver. Unknown hosts resolve to nothing, which the policy treats as reject. */
  private static final class StubResolver implements HostResolver {

    private final Map<String, List<InetAddress>> table = new HashMap<>();
    private boolean mustNotBeCalled;

    void with(String hostname, String... addresses) {
      List<InetAddress> resolved = new ArrayList<>();
      for (String address : addresses) {
        try {
          resolved.add(InetAddress.getByName(address)); // literal: no network access
        } catch (UnknownHostException e) {
          throw new IllegalArgumentException("bad fixture address: " + address, e);
        }
      }
      table.put(hostname, resolved);
    }

    void failIfCalled() {
      mustNotBeCalled = true;
    }

    @Override
    public List<InetAddress> resolve(String hostname) {
      if (mustNotBeCalled) {
        throw new AssertionError(
            "resolver must not be consulted for a literal address: " + hostname);
      }
      return table.getOrDefault(hostname, List.of());
    }
  }
}
