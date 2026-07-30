package com.smartlink.infrastructure.resilience;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Decides whether a failure is worth trying again.
 *
 * <p>The classification matters more than the retry count. Retrying something that will never
 * succeed converts one failed request into two or three, doubles the latency the caller waits
 * through, and holds a thread for the duration — all to reach the same answer.
 *
 * <p>The dangerous mistake here is over-inclusion, and it is invisible until an outage: a
 * classifier that treats everything as transient looks perfectly correct on a healthy system and
 * amplifies load precisely when the dependency is already struggling.
 */
final class TransientFailures {

  private TransientFailures() {}

  static boolean isTransient(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      // Checked first, and it is a deliberate carve-out from the rule below rather than an
      // oversight: QueryTimeoutException *is* a TransientDataAccessException, so without this it
      // would be retried.
      //
      // A timeout does not mean "the database hiccuped", it means "the database is too slow right
      // now". Retrying sends a second copy of the same expensive query to a dependency that has
      // already demonstrated it cannot keep up, and makes the caller wait the full budget twice
      // to be told the same thing. This is not theoretical: with the retry in place, a 10 s query
      // produced a >20 s request in SlowDependencyIT before this carve-out existed.
      //
      // It is exactly the over-inclusion failure described above — invisible on a healthy system,
      // load-amplifying during the outage it was meant to survive.
      if (current instanceof org.springframework.dao.QueryTimeoutException) {
        return false;
      }
      if (current instanceof TransientDataAccessException
          // Spring files connection-acquisition failures under *non*-transient, which is
          // arguably right for a dead database and wrong for a reset connection. A single
          // retry is the correct reading for the case the spec actually names: "a brief
          // connection reset". A dead database costs one extra attempt and then fails.
          || current instanceof DataAccessResourceFailureException
          || current instanceof RecoverableDataAccessException
          // Raised when a transaction cannot be opened because no connection is
          // available. That is a connection failure wearing a transaction-shaped name,
          // and it deserves the same single retry as any other reset connection.
          || current instanceof CannotCreateTransactionException) {
        return true;
      }
    }
    return false;
  }
}
