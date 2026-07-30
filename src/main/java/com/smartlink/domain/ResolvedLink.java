package com.smartlink.domain;

import java.time.Instant;

/**
 * A link together with the instant the datastore observed while reading it.
 *
 * <p>Exists to satisfy A-12: expiry is decided against a <strong>single authoritative
 * clock</strong>, and the database is the only thing every instance already agrees on.
 *
 * <p>The obvious implementation of that requirement — asking the database for {@code now()} before
 * each decision — would add a round trip to the path that carries the entire load. Carrying the
 * observation alongside the row costs nothing: the lookup was happening anyway, and {@code now()}
 * rides along in the same result set.
 *
 * @param observedAt the database's clock at the moment the row was read. Not the application's:
 *     several stateless instances each reading their own clock would disagree near an expiry, so a
 *     link would resolve on one and return 410 on the next with nothing to reproduce.
 */
public record ResolvedLink(Link link, Instant observedAt) {

  public LinkLifecycle lifecycle() {
    return link.lifecycleAt(observedAt);
  }
}
