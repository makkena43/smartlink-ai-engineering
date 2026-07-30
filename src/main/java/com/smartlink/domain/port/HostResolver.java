package com.smartlink.domain.port;

import java.net.InetAddress;
import java.util.List;

/**
 * Resolves a hostname to the addresses it points at.
 *
 * <p>A port rather than a direct call to {@code InetAddress.getAllByName}, for one concrete reason:
 * DNS is the only I/O the destination policy needs, and putting it behind an interface is what lets
 * the entire policy — every scheme rule, every notation, every blocked range — be proven with a
 * stubbed resolver and no network. A policy that can only be tested when DNS cooperates is a policy
 * whose tests get skipped.
 *
 * <p>It also keeps {@code domain} free of I/O, which {@code LayeringTest} enforces.
 */
public interface HostResolver {

  /**
   * Every address the hostname resolves to.
   *
   * <p><strong>Every</strong> is load-bearing. A hostname with one public and one private A record
   * defeats any check that inspects only the first result, and which one arrives first is not
   * something the caller controls.
   *
   * @return the resolved addresses, or an <strong>empty list</strong> when resolution fails. Empty
   *     means reject, never accept (NFR-16) — otherwise a resolver timeout becomes the way past
   *     every address rule.
   */
  List<InetAddress> resolve(String hostname);
}
