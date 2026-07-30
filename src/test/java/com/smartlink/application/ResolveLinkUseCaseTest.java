package com.smartlink.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartlink.application.exception.LinkNotFoundException;
import com.smartlink.domain.Destination;
import com.smartlink.domain.Link;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

/**
 * T6 acceptance: resolution, and the two failure postures that pull against each other.
 *
 * <p>A lookup failure must reach the caller. A counter failure must not. Almost every test here is
 * about which of those two applies.
 */
class ResolveLinkUseCaseTest {

  private static final String DESTINATION = "https://example.com/campaign?utm=1#frag";

  private final FakeRepository repository = new FakeRepository();
  private final ResolveLinkUseCase useCase = new ResolveLinkUseCase(repository);

  @Test
  @DisplayName("a known code resolves to its exact destination (GF-07)")
  void resolvesKnownCode() {
    repository.store("aB92xK7", DESTINATION);

    Link link = useCase.resolve("aB92xK7");

    assertThat(link.destination().value())
        .as("byte-identical, query string and fragment intact")
        .isEqualTo(DESTINATION);
  }

  @Test
  @DisplayName("resolution records exactly one redirect")
  void recordsOneRedirect() {
    repository.store("aB92xK7", DESTINATION);

    useCase.resolve("aB92xK7");

    assertThat(repository.recorded).containsExactly("aB92xK7");
  }

  @Test
  @DisplayName("an unknown code is not found (GF-09)")
  void unknownCodeIsNotFound() {
    assertThatThrownBy(() -> useCase.resolve("aB92xK7")).isInstanceOf(LinkNotFoundException.class);
    assertThat(repository.recorded)
        .as("nothing is counted for a link that does not exist")
        .isEmpty();
  }

  @Test
  @DisplayName("a malformed code is indistinguishable from an unknown one")
  void malformedCodeLooksLikeUnknown() {
    // Two different internal outcomes, one external answer. Reporting them differently would
    // let a caller narrow the namespace by reading which error came back - and with anonymous
    // creation and unauthenticated analytics, the code is the only access control there is.
    assertThatThrownBy(() -> useCase.resolve("short")).isInstanceOf(LinkNotFoundException.class);
    assertThatThrownBy(() -> useCase.resolve("has/slash"))
        .isInstanceOf(LinkNotFoundException.class);
    assertThatThrownBy(() -> useCase.resolve("")).isInstanceOf(LinkNotFoundException.class);
    assertThatThrownBy(() -> useCase.resolve(null)).isInstanceOf(LinkNotFoundException.class);
  }

  // ---------------------------------------------------------------------------------------

  @Test
  @DisplayName("a counter failure does NOT fail the redirect (the fail-open guarantee)")
  void counterFailureDoesNotFailTheRedirect() {
    repository.store("aB92xK7", DESTINATION);
    repository.failOnRecordWith(new QueryTimeoutException("counter write timed out"));

    Link link = useCase.resolve("aB92xK7");

    // The visitor reaches a page that was always available. Blocking them to protect a number
    // inverts the priority between the product and its instrumentation - and they could not
    // even tell why they were stopped.
    assertThat(link.destination().value()).isEqualTo(DESTINATION);
  }

  @Test
  @DisplayName("a lookup failure DOES fail the request — never a guess (NFR-02)")
  void lookupFailurePropagates() {
    repository.failOnLookupWith(new QueryTimeoutException("read timed out"));

    // The mapping could not be verified, so there is nothing safe to serve. This is the one
    // place where failing is the correct behaviour: a guessed or stale destination would
    // break the only promise the product actually makes.
    assertThatThrownBy(() -> useCase.resolve("aB92xK7")).isInstanceOf(QueryTimeoutException.class);
  }

  @Test
  @DisplayName("the fail-open catch covers the counter only, not the lookup")
  void failOpenScopeIsNarrow() {
    // Stated as its own test because the two behaviours above are one refactor apart. Widening
    // the try/catch by a single line - or wrapping resolve and increment in one transaction -
    // silently converts a 503 into a redirect to an unverified destination.
    repository.failOnLookupWith(new QueryTimeoutException("read timed out"));
    assertThatThrownBy(() -> useCase.resolve("aB92xK7")).isInstanceOf(QueryTimeoutException.class);

    repository.reset();
    repository.store("aB92xK7", DESTINATION);
    repository.failOnRecordWith(new QueryTimeoutException("write timed out"));
    assertThat(useCase.resolve("aB92xK7")).isNotNull();
  }

  // ---------------------------------------------------------------------------------------

  private static final class FakeRepository implements LinkRepository {

    private final Map<String, Link> links = new HashMap<>();
    private final java.util.List<String> recorded = new java.util.ArrayList<>();
    private RuntimeException lookupFailure;
    private RuntimeException recordFailure;

    void store(String code, String destination) {
      links.put(
          code,
          new Link(ShortCode.of(code), Destination.ofStoredValue(destination), Instant.EPOCH, 0L));
    }

    void failOnLookupWith(RuntimeException failure) {
      this.lookupFailure = failure;
    }

    void failOnRecordWith(RuntimeException failure) {
      this.recordFailure = failure;
    }

    void reset() {
      links.clear();
      recorded.clear();
      lookupFailure = null;
      recordFailure = null;
    }

    @Override
    public Optional<Link> insert(ShortCode code, Destination destination) {
      throw new UnsupportedOperationException("not part of the resolve path");
    }

    @Override
    public Optional<Link> findByCode(ShortCode code) {
      if (lookupFailure != null) {
        throw lookupFailure;
      }
      return Optional.ofNullable(links.get(code.value()));
    }

    @Override
    public boolean recordRedirect(ShortCode code) {
      if (recordFailure != null) {
        throw recordFailure;
      }
      recorded.add(code.value());
      return true;
    }
  }
}
