package com.smartlink.infrastructure.time;

import com.smartlink.domain.port.TimeSource;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The database's clock, which is the only one every instance already agrees on (A-12).
 *
 * <p>Replaces an earlier {@code Clock.systemUTC()} implementation. That version made the boundary
 * testable — the point of the port — but quietly substituted a *different guarantee* than the one
 * the requirement approved: several stateless instances each reading their own clock can still
 * disagree about whether a link near its expiry is live.
 *
 * <p><strong>Used on the create path only.</strong> Creation is low-volume, so one extra query to
 * validate "is this expiry in the future" is affordable. The redirect path never calls this: it
 * receives the database's observation alongside the row it was fetching anyway ({@code
 * ResolvedLink}), because adding a round trip to the path that carries the entire load would be
 * paying for correctness twice over.
 */
@Component
public class DatabaseTimeSource implements TimeSource {

  private final JdbcTemplate jdbc;

  public DatabaseTimeSource(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Instant now() {
    // CURRENT_TIMESTAMP, not PostgreSQL's statement_timestamp(): it is standard SQL and so also
    // runs under the H2 demo profile, which statement_timestamp() broke outright. This call runs
    // in its own short transaction, so the extra precision statement_timestamp() would buy inside
    // a long transaction is not observable here, and portability is worth more.
    //
    // Object, not Timestamp: the driver decides the returned type. See JdbcInstants.
    return JdbcInstants.toInstant(jdbc.queryForObject("select CURRENT_TIMESTAMP", Object.class));
  }
}
