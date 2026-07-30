package com.smartlink.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartlink.application.exception.DependencyUnavailableException;
import com.smartlink.application.exception.InvalidDestinationException;
import com.smartlink.domain.CodeGenerator;
import com.smartlink.domain.Destination;
import com.smartlink.domain.DestinationPolicy;
import com.smartlink.domain.Link;
import com.smartlink.domain.ResolvedLink;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.HostResolver;
import com.smartlink.domain.port.LinkRepository;
import com.smartlink.domain.port.TimeSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T5 acceptance: the create-link use case.
 *
 * <p>No Spring, no database. Fakes rather than mocks, because the interesting assertions here are
 * about <em>sequences</em> — how many codes were attempted, and whether a destination lookup ever
 * happened — and a fake that records what it was asked expresses that far more directly than a
 * stack of verify() calls.
 */
class CreateLinkUseCaseTest {

  private static final String VALID_URL = "https://example.com/campaign";

  /**
   * Fixed clock. Brownfield (scenario 02) added a {@link TimeSource} dependency; these Greenfield
   * tests are unaffected by expiry, so a constant instant keeps them deterministic and keeps every
   * assertion below exactly as it was.
   */
  private static final TimeSource FIXED_CLOCK = () -> Instant.parse("2026-01-01T00:00:00Z");

  private final FakeRepository repository = new FakeRepository();
  private final QueuedCodeGenerator generator = new QueuedCodeGenerator();
  private final DestinationPolicy policy = new DestinationPolicy(publicResolverFor("example.com"));

  private CreateLinkUseCase useCase() {
    return new CreateLinkUseCase(policy, generator, repository, FIXED_CLOCK);
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a valid destination becomes a stored link (GF-01, GF-02)")
  void createsLinkForValidDestination() {
    generator.willProduce("aB92xK7");

    Link link = useCase().create(VALID_URL);

    assertThat(link.code().value()).isEqualTo("aB92xK7");
    assertThat(link.destination().value()).isEqualTo(VALID_URL);
    assertThat(link.totalRedirects()).isZero();
  }

  @Test
  @DisplayName("the destination is stored byte-identical, never normalised (GF-19)")
  void storesDestinationVerbatim() {
    generator.willProduce("aB92xK7");
    String url = "https://example.com/A%20b?z=1&a=%2F#Frag";

    assertThat(useCase().create(url).destination().value()).isEqualTo(url);
  }

  @Test
  @DisplayName("the same destination twice yields two independent links (GF-04)")
  void sameDestinationYieldsIndependentLinks() {
    generator.willProduce("aaaaaaa", "bbbbbbb");

    Link first = useCase().create(VALID_URL);
    Link second = useCase().create(VALID_URL);

    assertThat(first.code()).isNotEqualTo(second.code());
    assertThat(repository.stored).hasSize(2);
  }

  @Test
  @DisplayName("no lookup by destination ever happens — the absence IS the GF-04 implementation")
  void neverLooksUpByDestination() {
    generator.willProduce("aaaaaaa", "bbbbbbb");

    useCase().create(VALID_URL);
    useCase().create(VALID_URL);

    // A dedup check would be an easy, well-meaning addition: it looks like an optimisation.
    // It would silently merge two campaigns into one analytics bucket, irreversibly, because
    // the per-campaign figures were never recorded separately in the first place.
    assertThat(repository.lookupsByCode)
        .as("the create path must not query storage before inserting")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a taken code is retried with a fresh candidate (GF-05, GF-06)")
  void retriesOnCollision() {
    repository.reserve("taken01");
    generator.willProduce("taken01", "free001");

    Link link = useCase().create(VALID_URL);

    assertThat(link.code().value()).isEqualTo("free001");
    assertThat(generator.issued).containsExactly("taken01", "free001");
  }

  @Test
  @DisplayName("collision retry is bounded at three candidates")
  void collisionRetryIsBounded() {
    repository.reserve("aaaaaaa", "bbbbbbb", "ccccccc");
    generator.willProduce("aaaaaaa", "bbbbbbb", "ccccccc", "ddddddd");

    assertThatThrownBy(() -> useCase().create(VALID_URL))
        .isInstanceOf(DependencyUnavailableException.class);

    // Bounded, not merely "eventually stops". An unbounded loop against a database that is
    // rejecting every insert would spin on the request thread until something else gave out.
    assertThat(generator.issued).hasSize(CreateLinkUseCase.MAX_CODE_ATTEMPTS);
  }

  @Test
  @DisplayName("exhausting candidates reports 503, not 500")
  void exhaustionIsRetryableNotUnexpected() {
    repository.reserve("aaaaaaa", "bbbbbbb", "ccccccc");
    generator.willProduce("aaaaaaa", "bbbbbbb", "ccccccc");

    // DependencyUnavailableException maps to 503. Reporting 500 would put a routine, safely
    // retryable outcome into the channel reserved for "nobody predicted this", and an
    // operator uses exactly that channel to decide whether to investigate.
    assertThatThrownBy(() -> useCase().create(VALID_URL))
        .isInstanceOf(DependencyUnavailableException.class)
        .hasMessageContaining("candidates");
  }

  @Test
  @DisplayName("a genuine dependency failure is not swallowed by the collision loop")
  void dependencyFailurePropagates() {
    repository.failWith(new IllegalStateException("connection reset"));
    generator.willProduce("aaaaaaa");

    // The loop treats "empty" as a collision. Anything else must propagate untouched,
    // otherwise a database outage would be retried three times and then misreported as an
    // exhausted keyspace.
    assertThatThrownBy(() -> useCase().create(VALID_URL))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("connection reset");
    assertThat(generator.issued).hasSize(1);
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a refused destination throws before anything is stored")
  void refusedDestinationStoresNothing() {
    generator.willProduce("aB92xK7");

    assertThatThrownBy(() -> useCase().create("javascript:alert(1)"))
        .isInstanceOf(InvalidDestinationException.class);

    assertThat(repository.stored).isEmpty();
    assertThat(generator.issued).as("no code should be consumed for a refused URL").isEmpty();
  }

  @Test
  @DisplayName("the rejection names the violated rule and never quotes the submitted URL")
  void rejectionNamesRuleWithoutEchoingInput() {
    String hostile = "javascript:alert(document.cookie)";

    assertThatThrownBy(() -> useCase().create(hostile))
        .isInstanceOf(InvalidDestinationException.class)
        .satisfies(
            thrown -> {
              InvalidDestinationException ex = (InvalidDestinationException) thrown;
              assertThat(ex.violatedRule()).isEqualTo("destination.scheme");
              // Even the operator-facing message stays clear of the payload: it reaches logs,
              // and a destination is attacker-controlled.
              assertThat(ex.getMessage()).doesNotContain("alert(document.cookie)");
              assertThat(ex.safeDetail()).doesNotContain("alert");
            });
  }

  @Test
  @DisplayName("a blocked address is refused before storage (GF-15)")
  void blockedAddressIsRefused() {
    assertThatThrownBy(() -> useCase().create("http://169.254.169.254/latest/meta-data/"))
        .isInstanceOf(InvalidDestinationException.class)
        .satisfies(
            thrown ->
                assertThat(((InvalidDestinationException) thrown).violatedRule())
                    .isEqualTo("destination.address-range"));
    assertThat(repository.stored).isEmpty();
  }

  // ---------------------------------------------------------------------------------------

  /** Records what it was asked, so tests can assert on absence as well as presence. */
  private static final class FakeRepository implements LinkRepository {

    private final Map<String, Link> stored = new HashMap<>();
    private final List<String> lookupsByCode = new ArrayList<>();
    private RuntimeException failure;

    void reserve(String... codes) {
      for (String code : codes) {
        stored.put(code, link(code, VALID_URL));
      }
    }

    void failWith(RuntimeException failure) {
      this.failure = failure;
    }

    @Override
    public Optional<Link> insert(ShortCode code, Destination destination, Instant expiresAt) {
      if (failure != null) {
        throw failure;
      }
      if (stored.containsKey(code.value())) {
        return Optional.empty(); // taken - a normal outcome, not an error
      }
      Link created = link(code.value(), destination.value());
      stored.put(code.value(), created);
      return Optional.of(created);
    }

    @Override
    public Optional<ResolvedLink> findByCode(ShortCode code) {
      lookupsByCode.add(code.value());
      return Optional.ofNullable(stored.get(code.value()))
          .map(link -> new ResolvedLink(link, FIXED_CLOCK.now()));
    }

    @Override
    public boolean recordRedirect(ShortCode code) {
      return stored.containsKey(code.value());
    }

    private static Link link(String code, String destination) {
      return new Link(
          ShortCode.of(code), Destination.ofStoredValue(destination), Instant.EPOCH, 0L);
    }
  }

  /** Emits a scripted sequence of codes, so collisions are exercised deterministically. */
  private static final class QueuedCodeGenerator extends CodeGenerator {

    private final Deque<String> queued = new ArrayDeque<>();
    private final List<String> issued = new ArrayList<>();

    void willProduce(String... codes) {
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

  /** Resolves the named hosts to a public address; everything else fails to resolve. */
  private static HostResolver publicResolverFor(String... hostnames) {
    Map<String, List<InetAddress>> table = new HashMap<>();
    for (String hostname : hostnames) {
      try {
        table.put(hostname, List.of(InetAddress.getByName("93.184.216.34")));
      } catch (UnknownHostException e) {
        throw new IllegalStateException(e);
      }
    }
    return hostname -> table.getOrDefault(hostname, List.of());
  }
}
