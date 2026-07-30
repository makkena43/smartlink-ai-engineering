package com.smartlink.domain;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Decides whether an address is a legitimate public destination.
 *
 * <p>A URL shortener is an open redirector by construction — that is the product, not a defect. So
 * this class does not try to decide whether a destination is <em>good</em>; it decides whether the
 * destination is somewhere <strong>this service must never be able to point at</strong>.
 *
 * <p>The exposure is latent today, because nothing server-side fetches a destination. It becomes
 * live the moment anything does — link preview, title enrichment, safety scanning, availability
 * checking, all natural next features. Validating now costs almost nothing; retrofitting later is
 * expensive because by then the stored corpus already contains the bad rows.
 *
 * <p>{@code InetAddress}'s built-in predicates cover only part of the space, and the gaps are not
 * obscure: {@code isSiteLocalAddress()} misses IPv6 unique-local entirely, and nothing covers
 * carrier-grade NAT. The explicit ranges below are those gaps.
 */
final class AddressPolicy {

  private AddressPolicy() {}

  static boolean isBlocked(InetAddress address) {
    if (address.isLoopbackAddress() // 127/8, ::1
        || address.isLinkLocalAddress() // 169.254/16 (incl. cloud metadata), fe80::/10
        || address.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
        || address.isMulticastAddress() // 224/4, ff00::/8
        || address.isAnyLocalAddress()) { // 0.0.0.0, ::
      return true;
    }
    return address instanceof Inet4Address
        ? isBlockedIpv4(address.getAddress())
        : isBlockedIpv6(address.getAddress());
  }

  private static boolean isBlockedIpv4(byte[] octets) {
    int first = octets[0] & 0xFF;
    int second = octets[1] & 0xFF;
    int third = octets[2] & 0xFF;

    // 0.0.0.0/8 - "this network". Some stacks route it to loopback.
    if (first == 0) {
      return true;
    }
    // 100.64.0.0/10 - carrier-grade NAT. Reachable internal space on many networks, and
    // isSiteLocalAddress() does not cover it.
    if (first == 100 && second >= 64 && second <= 127) {
      return true;
    }
    // 192.0.0.0/24 - IETF protocol assignments.
    if (first == 192 && second == 0 && third == 0) {
      return true;
    }
    // 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 - documentation ranges. Never a real
    // destination, and their presence in a submission is a strong signal of probing.
    if ((first == 192 && second == 0 && third == 2)
        || (first == 198 && second == 51 && third == 100)
        || (first == 203 && second == 0 && third == 113)) {
      return true;
    }
    // 198.18.0.0/15 - benchmarking.
    if (first == 198 && (second == 18 || second == 19)) {
      return true;
    }
    // 240.0.0.0/4 - reserved, including 255.255.255.255 broadcast.
    return first >= 240;
  }

  private static boolean isBlockedIpv6(byte[] bytes) {
    // fc00::/7 - unique local addresses, the IPv6 equivalent of RFC 1918. Java's
    // isSiteLocalAddress() only recognises the deprecated fec0::/10, so without this the
    // entire IPv6 private range would be accepted.
    return (bytes[0] & 0xFE) == 0xFC;
  }
}
