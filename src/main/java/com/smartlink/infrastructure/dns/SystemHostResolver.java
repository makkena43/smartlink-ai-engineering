package com.smartlink.infrastructure.dns;

import com.smartlink.domain.port.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves hostnames through the platform resolver.
 *
 * <p>The one piece of I/O the destination policy needs, isolated here so the policy itself stays
 * pure and provable offline.
 */
@Component
public class SystemHostResolver implements HostResolver {

  private static final Logger log = LoggerFactory.getLogger(SystemHostResolver.class);

  @Override
  public List<InetAddress> resolve(String hostname) {
    try {
      // getAllByName, not getByName. A hostname with one public and one private record
      // defeats any check that sees only the first address, and which one is returned first
      // is not something this service controls.
      return Arrays.asList(InetAddress.getAllByName(hostname));
    } catch (UnknownHostException e) {
      // Empty means reject (NFR-16). Returning an empty list rather than throwing keeps the
      // fail-closed decision in one place — the policy — instead of splitting it between a
      // catch block here and a rule there.
      //
      // Logged at DEBUG and without the hostname's surrounding URL: the destination is
      // attacker-controlled and its query string may carry credentials (NFR-14).
      log.debug("Host did not resolve; destination will be refused");
      return List.of();
    }
  }
}
