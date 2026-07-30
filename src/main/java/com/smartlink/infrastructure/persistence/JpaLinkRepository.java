package com.smartlink.infrastructure.persistence;

import com.smartlink.domain.Destination;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
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

  public JpaLinkRepository(ShortLinkJpaRepository jpa, PlatformTransactionManager transactions) {
    this.jpa = jpa;
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
  public Optional<Link> insert(ShortCode code, Destination destination) {
    try {
      ShortLinkEntity saved =
          isolatedAttempt.execute(
              status -> jpa.saveAndFlush(new ShortLinkEntity(code.value(), destination.value())));
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

  @Override
  @Transactional(readOnly = true)
  public Optional<Link> findByCode(ShortCode code) {
    return jpa.findByShortCode(code.value()).map(JpaLinkRepository::toDomain);
  }

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
        entity.getTotalRedirects());
  }
}
