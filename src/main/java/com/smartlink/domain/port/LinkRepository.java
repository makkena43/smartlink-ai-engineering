package com.smartlink.domain.port;

import com.smartlink.domain.Destination;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import java.time.Instant;
import java.util.Optional;

/** Storage for short links. Implemented in {@code infrastructure}; consumed by use cases. */
public interface LinkRepository {

  /**
   * Stores a new link, if and only if the code is still free.
   *
   * <p>Deliberately <strong>not</strong> a "check then save" pair. Two concurrent callers can both
   * see a code as free before either writes, so any pre-check is a race rather than a slower
   * correct answer. This method exists so the decision is made by a database constraint,
   * atomically, and the caller's job is only to try again with a different code.
   *
   * <p>Each call must be its own transaction. A rejected insert leaves the persistence context
   * poisoned and the surrounding transaction marked rollback-only, so a retry inside the same
   * transaction would fail for a reason entirely unrelated to the collision it is retrying.
   *
   * @return the stored link, or <strong>empty when the code was already taken</strong> — which is a
   *     normal, expected outcome and not a failure
   */
  /**
   * @param expiresAt UTC instant after which the link stops resolving, or {@code null} for a link
   *     that never expires. Set once, at creation; never mutated (A-13).
   */
  Optional<Link> insert(ShortCode code, Destination destination, Instant expiresAt);

  Optional<Link> findByCode(ShortCode code);

  /**
   * Records one successful redirect against a code.
   *
   * <p>A single atomic statement, never load-modify-write: the latter loses counts under
   * concurrency, and guarding it with a version makes concurrent redirects of one link collide
   * instead — so the failure rate would rise with a link's popularity, inverting NFR-08.
   *
   * @return false when no link has that code
   */
  boolean recordRedirect(ShortCode code);
}
