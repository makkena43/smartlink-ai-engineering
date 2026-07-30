package com.smartlink.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartlink.application.exception.DependencyUnavailableException;
import com.smartlink.domain.CodeGenerator;
import com.smartlink.domain.DestinationPolicy;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.HostResolver;
import com.smartlink.domain.port.LinkRepository;
import com.smartlink.domain.port.TimeSource;
import com.smartlink.support.AbstractPostgresIT;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * T5 acceptance against a real database.
 *
 * <p>The unit tests prove the use case's logic with a fake repository. These prove the parts a fake
 * cannot: that a rejected insert can actually be retried, and that concurrent creates are decided
 * by a database constraint rather than by luck.
 *
 * <p>The policy is built here with a stubbed resolver rather than autowired. The container's
 * resolver performs real DNS, which would make these tests fail on a train.
 */
@SpringBootTest
class CreateLinkIT extends AbstractPostgresIT {

  private static final String VALID_URL = "https://example.com/campaign";

  /**
   * Fixed clock. Brownfield (scenario 02) added a {@link TimeSource} dependency; these Greenfield
   * tests are unaffected by expiry, so a constant instant keeps them deterministic and keeps every
   * assertion below exactly as it was.
   */
  private static final TimeSource FIXED_CLOCK = () -> Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private LinkRepository repository;

  private CreateLinkUseCase useCaseWith(CodeGenerator generator) {
    return new CreateLinkUseCase(
        new DestinationPolicy(stubResolver()), generator, repository, FIXED_CLOCK);
  }

  @Test
  @DisplayName("a link is created and is durable")
  void createsAndPersists() {
    Link link = useCaseWith(new CodeGenerator()).create(VALID_URL);

    assertThat(repository.findByCode(link.code()))
        .as("NFR-01: the mapping must survive as stored, not merely be returned")
        .isPresent()
        .get()
        .satisfies(found -> assertThat(found.link().destination().value()).isEqualTo(VALID_URL));
    assertThat(link.createdAt()).as("assigned by the database clock").isNotNull();
  }

  @Test
  @DisplayName("a rejected insert can be retried — the REQUIRES_NEW proof")
  void retryAfterRejectedInsertSucceeds() {
    // The whole point of this test. A constraint violation poisons the persistence context
    // and marks the transaction rollback-only. If insert() did not run in its own
    // transaction, this second attempt would fail with a rollback error rather than
    // succeeding - and the retry loop would look broken for a reason that has nothing at all
    // to do with collisions.
    ScriptedGenerator scripted = new ScriptedGenerator("collide", "survive");

    useCaseWith(new ScriptedGenerator("collide")).create(VALID_URL);
    Link second = useCaseWith(scripted).create("https://example.com/second");

    assertThat(second.code().value()).isEqualTo("survive");
    assertThat(scripted.issued).containsExactly("collide", "survive");
  }

  @Test
  @DisplayName("exhausting candidates against a real constraint reports 503")
  void exhaustionAgainstRealConstraint() {
    useCaseWith(new ScriptedGenerator("takenAA")).create(VALID_URL);

    assertThatThrownBy(
            () ->
                useCaseWith(new ScriptedGenerator("takenAA", "takenAA", "takenAA"))
                    .create("https://example.com/other"))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("concurrent creates all succeed with distinct codes (GF-06)")
  void concurrentCreatesProduceDistinctCodes() throws Exception {
    int threads = 16;
    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    Set<String> codes = ConcurrentHashMap.newKeySet();

    try {
      List<Callable<Void>> work =
          IntStream.range(0, threads)
              .<Callable<Void>>mapToObj(
                  i ->
                      () -> {
                        startLine.await();
                        codes.add(
                            useCaseWith(new CodeGenerator())
                                .create("https://example.com/concurrent-" + i)
                                .code()
                                .value());
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

    // Every request succeeded and no two share a code. A check-then-insert would let several
    // threads observe the same code as free, and the resulting corruption would depend on
    // timing - reproducible only occasionally, which is the worst kind to chase.
    assertThat(codes).hasSize(threads);
  }

  @Test
  @DisplayName("a forced collision between concurrent creates leaves exactly one winner")
  void forcedConcurrentCollisionHasOneWinner() throws Exception {
    // Every thread is handed the same first candidate, then a unique fallback. The database
    // must arbitrate: one takes the contested code, the rest quietly move on. This is
    // insert-and-retry under genuine contention rather than in sequence.
    int threads = 8;
    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    Set<String> codes = ConcurrentHashMap.newKeySet();

    try {
      List<Callable<Void>> work =
          IntStream.range(0, threads)
              .<Callable<Void>>mapToObj(
                  i ->
                      () -> {
                        startLine.await();
                        CodeGenerator contested =
                            new ScriptedGenerator("CONTEST", "fall" + String.format("%03d", i));
                        codes.add(
                            useCaseWith(contested)
                                .create("https://example.com/contested-" + i)
                                .code()
                                .value());
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

    assertThat(codes).as("every request still succeeded").hasSize(threads);
    assertThat(codes).as("exactly one thread won the contested code").contains("CONTEST");
    assertThat(codes.stream().filter(code -> code.startsWith("fall")).count())
        .as("the losers each fell back to their own candidate")
        .isEqualTo(threads - 1L);
  }

  // ---------------------------------------------------------------------------------------

  private static final class ScriptedGenerator extends CodeGenerator {

    private final Deque<String> queued = new ArrayDeque<>();
    private final List<String> issued = new ArrayList<>();

    ScriptedGenerator(String... codes) {
      queued.addAll(List.of(codes));
    }

    @Override
    public ShortCode next() {
      String code = queued.poll();
      if (code == null) {
        throw new AssertionError("use case requested more codes than the test scripted");
      }
      issued.add(code);
      return ShortCode.of(code);
    }
  }

  private static HostResolver stubResolver() {
    return hostname -> {
      try {
        return List.of(InetAddress.getByName("93.184.216.34"));
      } catch (UnknownHostException e) {
        throw new IllegalStateException(e);
      }
    };
  }
}
