package com.smartlink.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Data access for {@code short_link}.
 *
 * <p>Every query here is parameterised (NFR-14). No method takes a fragment of query text, so there
 * is no path by which caller input could reach the query as SQL rather than as a value.
 */
@Repository
public interface ShortLinkJpaRepository extends JpaRepository<ShortLinkEntity, Long> {

  /**
   * Redirect-path lookup. Served by the index behind the unique constraint on {@code short_code}.
   */
  Optional<ShortLinkEntity> findByShortCode(String shortCode);

  boolean existsByShortCode(String shortCode);

  /**
   * Increments the redirect counter as a single atomic statement.
   *
   * <p>This is the whole reason the entity maps {@code totalRedirects} read-only. The obvious
   * alternative — load the entity, add one, flush — is wrong in two separate ways under
   * concurrency:
   *
   * <ul>
   *   <li><strong>Lost updates.</strong> Two requests both read 41, both write 42, and one redirect
   *       goes unrecorded. Nothing fails, no error is logged, and the number is simply quietly low
   *       forever.
   *   <li><strong>Popularity-proportional failure.</strong> Add {@code @Version} to prevent the
   *       lost update and the two requests collide instead, so the more traffic a link gets the
   *       more often it errors — exactly inverting NFR-08.
   * </ul>
   *
   * <p>A single {@code UPDATE … SET x = x + 1} avoids both: the database serialises the increment
   * at row level, no version is needed, and nothing is lost. Verified by {@code
   * ShortLinkRepositoryIT.concurrentIncrementsLoseNoCounts}, which fails under either alternative
   * implementation.
   *
   * @return rows affected — 0 when no link has that id, which the caller uses to distinguish a
   *     missing link from a successful increment without issuing a second query
   */
  @Modifying
  @Query("update ShortLinkEntity s set s.totalRedirects = s.totalRedirects + 1 where s.id = :id")
  int incrementRedirects(@Param("id") long id);

  /**
   * The same atomic increment, addressed by code.
   *
   * <p>Exists so the redirect path can record a hit without a second round trip to fetch the id. On
   * the path that carries the entire load, one query beats two — and the return value already
   * distinguishes "no such link" from "counted", so nothing is lost by not having the row.
   */
  @Modifying
  @Query(
      "update ShortLinkEntity s set s.totalRedirects = s.totalRedirects + 1 "
          + "where s.shortCode = :code")
  int incrementRedirectsByCode(@Param("code") String code);

  /**
   * The redirect-path lookup, returning the database's own clock alongside the row.
   *
   * <p>This is how A-12's "single authoritative clock, database-side" is honoured without paying
   * for it twice. Querying {@code now()} separately would double the round trips on the path that
   * carries the entire load; here it rides along in a result set already being fetched.
   *
   * <p>Native rather than JPQL because a clock function cannot be selected alongside an entity in
   * JPQL. The cost is that column names appear literally, which is the trade for keeping the hot
   * path at one query.
   *
   * <p>{@code CURRENT_TIMESTAMP} rather than PostgreSQL's {@code statement_timestamp()}: it is
   * standard SQL and therefore also runs under the H2 demo profile. The first version used {@code
   * statement_timestamp()} and broke that profile outright — invisible to this suite, because every
   * integration test here runs against real PostgreSQL. Portable SQL costs nothing and is still the
   * database's clock, which is the whole of what A-12 requires.
   */
  @Query(
      value =
          "select s.short_code as shortCode, s.destination_url as destinationUrl, "
              + "s.created_at as createdAt, s.total_redirects as totalRedirects, "
              + "s.expires_at as expiresAt, CURRENT_TIMESTAMP as observedAt "
              + "from short_link s where s.short_code = :code",
      nativeQuery = true)
  Optional<LinkRowWithClock> findRowWithClock(@Param("code") String code);

  /** Projection for {@link #findRowWithClock(String)}. */
  interface LinkRowWithClock {
    String getShortCode();

    String getDestinationUrl();

    long getTotalRedirects();

    /**
     * The three instant-valued columns are declared as {@code Object}, not {@code Instant}, and
     * normalised by the caller.
     *
     * <p>This is not laziness. A native query returns whatever the JDBC driver chose: the
     * PostgreSQL driver yields {@link java.sql.Timestamp} for {@code timestamptz}, while H2 yields
     * {@link java.time.OffsetDateTime}. Spring Data's projection converter handles the first and
     * throws {@code UnsupportedOperationException} on the second, so any single declared type makes
     * this query work on one database and fail on the other. Declaring {@code Object} takes the raw
     * value and converts it where both shapes can be handled explicitly.
     *
     * <p>Found by running the H2 demo profile by hand — every integration test in this repository
     * runs against real PostgreSQL, so the test suite could not have caught it.
     */
    Object getCreatedAt();

    Object getExpiresAt();

    /** Database clock at read time — the authoritative instant for every expiry decision. */
    Object getObservedAt();
  }
}
