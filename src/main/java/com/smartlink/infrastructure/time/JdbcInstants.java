package com.smartlink.infrastructure.time;

import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * Converts whatever temporal type a JDBC driver returned for a {@code timestamp with time zone}
 * into an {@link Instant}.
 *
 * <p>This exists because the type is not fixed by the SQL. PostgreSQL's driver returns {@link
 * java.sql.Timestamp}; H2's returns {@link OffsetDateTime}. Code that declares either one directly
 * works on one database and throws on the other, which is precisely the defect this class was
 * extracted to fix — it was present in two places at once, the redirect projection and the create
 * path's clock.
 *
 * <p>Both accepted types are unambiguous points on the timeline, so neither conversion assumes a
 * zone. An unrecognised type fails loudly rather than being coerced: a wrong zone assumption here
 * would not crash, it would quietly expire links in the wrong hour, which is far worse.
 */
public final class JdbcInstants {

  private JdbcInstants() {}

  public static Instant toInstant(Object raw) {
    return switch (raw) {
      case null -> null;
      case Instant i -> i;
      case OffsetDateTime o -> o.toInstant();
      case java.sql.Timestamp ts -> ts.toInstant();
      default ->
          throw new IllegalStateException(
              "unsupported temporal type from the JDBC driver: " + raw.getClass().getName());
    };
  }
}
