package com.smartlink.domain;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

/**
 * Recognises a host component that is already an IP address, in any notation.
 *
 * <p>This class is the whole of GF-16, and it exists because <strong>the same address has many
 * spellings</strong>. All of these are {@code 169.254.169.254}, the cloud instance-metadata
 * endpoint:
 *
 * <pre>
 *   169.254.169.254        dotted quad
 *   2852039166             single decimal
 *   0xA9FEA9FE             single hexadecimal
 *   0251.0376.0251.0376    dotted octal
 *   0xA9FE.43518           mixed
 *   ::ffff:169.254.169.254 IPv6-mapped
 * </pre>
 *
 * <p>A validator that compares the host <em>string</em> against a blocklist rejects the first and
 * admits the rest. So the host is converted to an address <em>before</em> any rule is applied, and
 * the rules then run against 4 or 16 bytes rather than against text. Normalise first, then decide.
 *
 * <p>The forms below are those of {@code inet_aton}, which is what the C library — and therefore
 * most of the software that will eventually follow one of these links — actually accepts. Handling
 * only dotted quads would mean this service and its clients disagree about where a URL points,
 * which is the disagreement an attacker is looking for.
 *
 * <p>Nothing here performs I/O: every address is built from bytes. That is what keeps the policy
 * testable with no network.
 */
final class HostLiterals {

  private HostLiterals() {}

  /**
   * @return the address this host literally denotes, or empty if it is a name needing resolution
   */
  static Optional<InetAddress> parse(String host) {
    if (host == null || host.isEmpty()) {
      return Optional.empty();
    }

    String candidate = host;
    if (candidate.startsWith("[") && candidate.endsWith("]")) {
      candidate = candidate.substring(1, candidate.length() - 1);
    }

    if (candidate.indexOf(':') >= 0) {
      return parseIpv6(candidate);
    }
    return parseIpv4(candidate);
  }

  private static Optional<InetAddress> parseIpv6(String candidate) {
    // A string containing ':' is never a DNS name, so getByName resolves it locally without
    // touching the network. Guarded by a charset check so a malformed value cannot slip
    // through into a lookup.
    if (!candidate.matches("[0-9A-Fa-f:.%]+")) {
      return Optional.empty();
    }
    try {
      return Optional.of(InetAddress.getByName(candidate));
    } catch (UnknownHostException e) {
      return Optional.empty();
    }
  }

  /**
   * Parses the {@code inet_aton} family: 1, 2, 3 or 4 parts, each decimal, octal or hexadecimal.
   *
   * <p>With fewer than four parts the final part absorbs the remaining bytes — which is why {@code
   * 0xA9FE.43518} and {@code 2852039166} are both the metadata address.
   */
  private static Optional<InetAddress> parseIpv4(String candidate) {
    String[] parts = candidate.split("\\.", -1);
    if (parts.length == 0 || parts.length > 4) {
      return Optional.empty();
    }

    long[] values = new long[parts.length];
    for (int i = 0; i < parts.length; i++) {
      Long parsed = parsePart(parts[i]);
      if (parsed == null) {
        return Optional.empty(); // a hostname, not a numeric literal
      }
      values[i] = parsed;
    }

    // Leading parts are always single bytes; the last absorbs whatever is left.
    long maxForLast =
        switch (parts.length) {
          case 1 -> 0xFFFFFFFFL;
          case 2 -> 0xFFFFFFL;
          case 3 -> 0xFFFFL;
          default -> 0xFFL;
        };
    for (int i = 0; i < parts.length - 1; i++) {
      if (values[i] > 0xFF) {
        return Optional.empty();
      }
    }
    if (values[parts.length - 1] > maxForLast) {
      return Optional.empty();
    }

    long address = values[parts.length - 1];
    for (int i = 0; i < parts.length - 1; i++) {
      address |= values[i] << (8 * (3 - i));
    }

    byte[] octets = {
      (byte) (address >>> 24), (byte) (address >>> 16), (byte) (address >>> 8), (byte) address
    };
    try {
      return Optional.of(InetAddress.getByAddress(octets));
    } catch (UnknownHostException e) {
      return Optional.empty(); // unreachable for a 4-byte array
    }
  }

  /**
   * @return the numeric value, or null when the part is not numeric in any accepted base
   */
  private static Long parsePart(String part) {
    if (part.isEmpty()) {
      return null;
    }
    try {
      if (part.length() > 2 && (part.startsWith("0x") || part.startsWith("0X"))) {
        return Long.parseLong(part.substring(2), 16);
      }
      if (part.length() > 1 && part.charAt(0) == '0') {
        return Long.parseLong(part.substring(1), 8);
      }
      return Long.parseLong(part, 10);
    } catch (NumberFormatException notNumeric) {
      return null;
    }
  }
}
