package com.smartlink.infrastructure.persistence;

import com.smartlink.domain.Destination;
import com.smartlink.domain.Link;
import com.smartlink.domain.ResolvedLink;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
import com.smartlink.infrastructure.resilience.BoundedRetry;
import com.smartlink.infrastructure.time.JdbcInstants;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** JPA adapter for {@link LinkRepository}. Translates between domain types and rows. */
@Component
public class JpaLinkRepository implements LinkRepository {

  private static final Logger log = LoggerFactory.getLogger(JpaLinkRepository.class);

  private final ShortLinkJpaRepository jpa;
  private final TransactionTemplate isolatedAttempt;
  private final BoundedRetry retry;

  public JpaLinkRepository(
      ShortLinkJpaRepository jpa, PlatformTransactionManager transactions, BoundedRetry retry) {
    this.jpa = jpa;
    this.retry = retry;
    this.isolatedAttempt = new TransactionTemplate(transactions);
    this.isolatedAttempt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * {@inheritDoc}
   *
   * <p><strong>A {@code TransactionTemplate} rather than {@code @Transactional}, and the reason is
   * not stylistic.</strong> The obvious implementation — annotate the method, catch the violation
   * inside it — does not work, and fails in a way that points nowhere near the cause.
   *
   * <p>A constraint violation marks the transaction rollback-only the moment it is raised.
   * Swallowing the exception inside the method therefore returns normally into a transaction that
   * can no longer commit, and Spring raises {@code UnexpectedRollbackException} at the boundary:
   * "transaction silently rolled back because it has been marked as rollback-only". The collision
   * has been handled correctly and the request still fails, with an error naming neither the
   * collision nor the retry.
   *
   * <p>The catch has to sit <em>outside</em> the transaction boundary, which is what this shape
   * achieves: the template commits or rolls back, then the exception surfaces here with the
   * transaction already settled, and the next attempt starts on clean ground.
   *
   * <p>{@code saveAndFlush}, not {@code save}: the constraint must fire now. With a deferred flush
   * the violation would surface at commit — past the catch, as an unhandled failure.
   */
  @Override
  public Optional<Link> insert(ShortCode code, Destination destination, Instant expiresAt) {
    try {
      // The retry sees a constraint violation as non-transient and rethrows it immediately,
      // so the collision reaches the catch below on the first attempt. That separation is
      // what keeps the two allowances independent: a database outage cannot consume the
      // use case's three collision candidates, and a collision cannot be mistaken for one.
      ShortLinkEntity saved =
          retry.execute(
              () ->
                  isolatedAttempt.execute(
                      status ->
                          jpa.saveAndFlush(
                              new ShortLinkEntity(code.value(), destination.value(), expiresAt))));
      return Optional.of(toDomain(saved));
    } catch (DataIntegrityViolationException e) {
      // short_code carries the only unique constraint on this table, and destination_url has
      // no constraint that could fire, so this is a collision. If another constraint is ever
      // added, this catch must narrow with it - otherwise a genuine data error would be
      // silently reported as "code taken" and retried three times before surfacing.
      log.debug("Insert rejected by a database constraint; treating as a code collision");
      return Optional.empty();
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Retried, because this is the one read whose failure the caller cannot work around: a
   * redirect cannot be served without it, and NFR-02 forbids guessing.
   *
   * <p>Deliberately not annotated {@code @Transactional} here. Spring Data opens a transaction per
   * repository call, so each retry attempt gets a clean one — whereas an outer transaction would be
   * marked rollback-only by the first failure and the retry would fail against that instead of
   * against the database.
   */
  @Override
  public Optional<ResolvedLink> findByCode(ShortCode code) {
    return retry.execute(
        () -> jpa.findRowWithClock(code.value()).map(JpaLinkRepository::toResolved));
  }

  private static ResolvedLink toResolved(ShortLinkJpaRepository.LinkRowWithClock row) {
    ShortCode code =
        ShortCode.parse(row.getShortCode())
            .orElseThrow(() -> new IllegalStateException("stored short code is not well-formed"));

    Link link =
        new Link(
            code,
            Destination.ofStoredValue(row.getDestinationUrl()),
            JdbcInstants.toInstant(row.getCreatedAt()),
            row.getTotalRedirects(),
            JdbcInstants.toInstant(row.getExpiresAt()));

    return new ResolvedLink(link, JdbcInstants.toInstant(row.getObservedAt()));
  }

  /**
   * {@inheritDoc}
   *
   * <p><strong>Not retried, on purpose.</strong> The counter is fail-open: if this fails, the
   * redirect is served anyway and the caller never learns of it. Retrying would therefore buy
   * nothing a visitor can perceive, while adding latency to the path that carries the entire load —
   * and doing so at exactly the moment the database is already struggling.
   *
   * <p>Instrumentation must not compete with the product for a failing dependency's capacity.
   */
  @Override
  @Transactional
  public boolean recordRedirect(ShortCode code) {
    return jpa.incrementRedirectsByCode(code.value()) > 0;
  }

  private static Link toDomain(ShortLinkEntity entity) {
    ShortCode code =
        ShortCode.parse(entity.getShortCode())
            .orElseThrow(
                () ->
                    // Unreachable unless a row was written outside the application. Failing
                    // loudly beats silently serving a code the domain considers malformed:
                    // a code the application would not have issued is a code nobody can
                    // account for.
                    new IllegalStateException("stored short code is not well-formed"));

    return new Link(
        code,
        Destination.ofStoredValue(entity.getDestinationUrl()),
        entity.getCreatedAt(),
        entity.getTotalRedirects(),
        entity.getExpiresAt());
  }
}
