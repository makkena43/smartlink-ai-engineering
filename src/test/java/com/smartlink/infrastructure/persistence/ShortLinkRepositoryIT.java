package com.smartlink.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartlink.support.AbstractPostgresIT;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/** T3 acceptance: persistence behaviour, asserted against a real PostgreSQL. */
@SpringBootTest
class ShortLinkRepositoryIT extends AbstractPostgresIT {

  @Autowired private ShortLinkJpaRepository repository;
  @Autowired private TransactionTemplate transactions;

  private ShortLinkEntity persist(String code, String destination) {
    return transactions.execute(
        status -> repository.saveAndFlush(new ShortLinkEntity(code, destination)));
  }

  @Test
  @DisplayName("a saved mapping round-trips with its destination byte-identical")
  void roundTripsDestinationByteIdentical() {
    // Query string, fragment, encoded characters and a trailing space are all preserved.
    // Normalising any of them would silently break signed URLs and tracking parameters
    // (GF-07, GF-19), and the breakage would surface as a campaign that underperformed.
    String destination = "https://example.com/a%20b/c?x=1&y=%2F&z=a+b#frag";
    persist("rt00001", destination);

    ShortLinkEntity found = repository.findByShortCode("rt00001").orElseThrow();

    assertThat(found.getDestinationUrl()).isEqualTo(destination);
    assertThat(found.getCreatedAt()).isNotNull();
    assertThat(found.getTotalRedirects()).isZero();
  }

  @Test
  @DisplayName("created_at is populated by the database and read back into the entity")
  void createdAtIsPopulatedByDatabase() {
    ShortLinkEntity saved = persist("rt00002", "https://example.com/clock");

    assertThat(saved.getCreatedAt())
        .as("@Generated(INSERT) must read the database default back")
        .isNotNull();
  }

  @Test
  @DisplayName("a duplicate short code is rejected by the database, not by the application")
  void duplicateShortCodeIsRejectedByDatabase() {
    persist("dup0001", "https://example.com/first");

    assertThatThrownBy(() -> persist("dup0001", "https://example.com/second"))
        .as("the unique constraint is the collision authority (GF-05)")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("concurrent inserts of the same code produce exactly one row (GF-06)")
  void concurrentInsertsOfSameCodeProduceOneRow() throws Exception {
    int threads = 12;
    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    try {
      List<Callable<Boolean>> attempts =
          IntStream.range(0, threads)
              .<Callable<Boolean>>mapToObj(
                  i ->
                      () -> {
                        startLine.await();
                        try {
                          persist("race001", "https://example.com/attempt-" + i);
                          return true;
                        } catch (DataIntegrityViolationException expected) {
                          return false;
                        }
                      })
              .toList();

      List<Future<Boolean>> futures = attempts.stream().map(pool::submit).toList();
      startLine.countDown(); // release every thread at once

      long successes = 0;
      for (Future<Boolean> future : futures) {
        if (future.get(30, TimeUnit.SECONDS)) {
          successes++;
        }
      }

      // Exactly one winner. This is what makes insert-and-retry correct: a check-then-insert
      // would let several threads observe the code as free and all proceed, and the resulting
      // corruption would depend on timing - the worst kind of bug to reproduce.
      assertThat(successes).isEqualTo(1);
      assertThat(repository.findByShortCode("race001")).isPresent();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("concurrent increments lose no counts (the atomic-increment proof)")
  void concurrentIncrementsLoseNoCounts() throws Exception {
    ShortLinkEntity link = persist("hot0001", "https://example.com/viral");
    long id = link.getId();

    int threads = 16;
    int perThread = 25;
    int expected = threads * perThread;

    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    try {
      List<Callable<Void>> work =
          IntStream.range(0, threads)
              .<Callable<Void>>mapToObj(
                  i ->
                      () -> {
                        startLine.await();
                        for (int n = 0; n < perThread; n++) {
                          transactions.execute(status -> repository.incrementRedirects(id));
                        }
                        return null;
                      })
              .toList();

      List<Future<Void>> futures = work.stream().map(pool::submit).toList();
      startLine.countDown();
      for (Future<Void> future : futures) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    long actual = repository.findByShortCode("hot0001").orElseThrow().getTotalRedirects();

    // This assertion is the executable form of the "no @Version" constraint, and it fails
    // under BOTH alternative implementations:
    //   * read-modify-write  -> lost updates, actual < expected, silently
    //   * @Version guarded   -> OptimisticLockException, the test errors outright
    // Only a single atomic UPDATE ... SET x = x + 1 satisfies it.
    assertThat(actual).as("400 concurrent increments must all be recorded").isEqualTo(expected);
  }

  @Test
  @DisplayName("incrementing an unknown id affects no rows rather than failing")
  void incrementOfUnknownIdAffectsNoRows() {
    Integer affected = transactions.execute(status -> repository.incrementRedirects(-999L));

    // The caller distinguishes "no such link" from "counted" without a second query. That
    // matters on the redirect path, where an extra round trip is paid on every request.
    assertThat(affected).isZero();
  }

  @Test
  @DisplayName("an unknown code resolves to empty, never to a guess")
  void unknownCodeResolvesToEmpty() {
    assertThat(repository.findByShortCode("nosuch1")).isEmpty();
  }

  @Test
  @DisplayName("toString does not leak the destination URL (NFR-14)")
  void toStringDoesNotLeakDestination() {
    // toString reaches logs and exception messages by accident far more often than by design.
    // Destination query strings routinely carry reset tokens and signed URLs.
    ShortLinkEntity link =
        new ShortLinkEntity("log0001", "https://example.com/reset?token=super-secret-value");

    assertThat(link.toString()).doesNotContain("super-secret-value").contains("log0001");
  }

  @Test
  @DisplayName("propagation is honoured: a rolled-back insert leaves nothing behind")
  void rolledBackInsertLeavesNothing() {
    transactions.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
    try {
      transactions.execute(
          status -> {
            repository.saveAndFlush(new ShortLinkEntity("rbk0001", "https://example.com/rollback"));
            status.setRollbackOnly();
            return null;
          });
    } finally {
      transactions.setPropagationBehavior(Propagation.REQUIRED.value());
    }

    // NFR-01 is about durability of what was committed. Its converse matters just as much:
    // a create that failed must leave no code claimed, or the namespace leaks on every error.
    assertThat(repository.findByShortCode("rbk0001")).isEmpty();
  }
}
