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
}
