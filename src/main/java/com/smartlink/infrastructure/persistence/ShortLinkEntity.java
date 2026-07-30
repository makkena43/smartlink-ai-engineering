package com.smartlink.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Persistence mapping for {@code short_link}.
 *
 * <p>An infrastructure type, not a domain type. It exists to talk to the database and nothing else,
 * which is why it carries JPA annotations that the domain layer is forbidden from importing
 * (enforced by {@code LayeringTest}).
 *
 * <p><strong>There is no {@code @Version} field, and its absence is deliberate.</strong> {@code
 * totalRedirects} is written on every redirect. Optimistic locking would turn that into a
 * load-modify-save cycle, so two concurrent redirects of the same link would collide and one would
 * fail or retry — meaning the failure rate rises with a link's popularity, which is precisely
 * backwards from NFR-08. The counter is updated by an atomic statement in {@link
 * ShortLinkJpaRepository#incrementRedirects(long)} instead.
 *
 * <p>This is easy to reintroduce by accident, because adding {@code @Version} reads as diligence.
 * {@code SchemaConstraintsIT} fails the build if the column ever reappears.
 */
@Entity
@Table(name = "short_link")
public class ShortLinkEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "short_code", nullable = false, length = 16, updatable = false)
  private String shortCode;

  /**
   * Stored exactly as submitted and accepted (GF-19).
   *
   * <p>{@code updatable = false} is a second line of defence behind the fact that no use case
   * mutates it. A short link is a stable public handle: if a stored destination could change, every
   * holder of an already-printed link would be silently redirected somewhere they never agreed to,
   * with no way to detect the substitution.
   */
  @Column(name = "destination_url", nullable = false, length = 2048, updatable = false)
  private String destinationUrl;

  /**
   * Populated by the database default, then read back.
   *
   * <p>{@code @Generated(INSERT)} is what makes the database clock authoritative while still
   * leaving the in-memory entity correct after a save. Setting this application-side would give as
   * many clocks as there are instances.
   */
  @Generated(event = EventType.INSERT)
  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  /**
   * Never written through this field.
   *
   * <p>Mapped read-only on purpose: {@code updatable = false} makes it impossible to increment the
   * counter by loading, mutating and flushing, which is the implementation this design rules out.
   * Writes go through the atomic statement in the repository.
   */
  @Column(name = "total_redirects", nullable = false, insertable = false, updatable = false)
  private long totalRedirects;

  protected ShortLinkEntity() {
    // Required by JPA.
  }

  public ShortLinkEntity(String shortCode, String destinationUrl) {
    this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
    this.destinationUrl = Objects.requireNonNull(destinationUrl, "destinationUrl");
  }

  public Long getId() {
    return id;
  }

  public String getShortCode() {
    return shortCode;
  }

  public String getDestinationUrl() {
    return destinationUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public long getTotalRedirects() {
    return totalRedirects;
  }

  /**
   * Identity is the short code, which is immutable and unique.
   *
   * <p>Using the surrogate {@code id} would make a not-yet-persisted instance unequal to itself
   * after saving, which is the classic JPA equality trap.
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof ShortLinkEntity entity && Objects.equals(shortCode, entity.shortCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(shortCode);
  }

  /**
   * Deliberately omits {@code destinationUrl}.
   *
   * <p>{@code toString()} ends up in logs and exception messages by accident more often than by
   * design. Destination URLs are attacker-controlled and routinely carry credentials in query
   * strings — reset tokens, signed URLs, session identifiers — so including it here would leak
   * those into every log sink the service touches (NFR-14).
   */
  @Override
  public String toString() {
    return "ShortLinkEntity{shortCode=" + shortCode + ", totalRedirects=" + totalRedirects + "}";
  }
}
