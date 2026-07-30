package com.smartlink.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Entity identity semantics. No Spring, no database — this is plain object behaviour.
 *
 * <p>Worth testing directly rather than incidentally, because JPA entity equality has a specific
 * and well-known trap: keying on the surrogate {@code id} makes an instance unequal to itself
 * across a save, since the id is null before the insert and populated after. Anything that held the
 * instance in a {@link Set} or used it as a map key before saving then silently loses it.
 *
 * <p>This entity keys on {@code shortCode} instead, which is assigned at construction and immutable
 * thereafter. These tests are what stop someone "fixing" it back to {@code id} later.
 */
class ShortLinkEntityTest {

  private static final String DESTINATION = "https://example.com/x";

  @Test
  @DisplayName("entities with the same short code are equal")
  void equalBySharedShortCode() {
    ShortLinkEntity first = new ShortLinkEntity("aB92xK7", DESTINATION);
    ShortLinkEntity second = new ShortLinkEntity("aB92xK7", "https://example.com/different");

    // Equal despite different destinations: the short code is the identity, and it is unique
    // by database constraint, so two rows can never disagree in practice.
    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("entities with different short codes are not equal")
  void notEqualByDifferentShortCode() {
    assertThat(new ShortLinkEntity("aB92xK7", DESTINATION))
        .isNotEqualTo(new ShortLinkEntity("zZ11yY2", DESTINATION));
  }

  @Test
  @DisplayName("an entity is equal to itself and unequal to null or a foreign type")
  void reflexiveAndTypeSafe() {
    ShortLinkEntity entity = new ShortLinkEntity("aB92xK7", DESTINATION);

    assertThat(entity).isEqualTo(entity).isNotEqualTo(null).isNotEqualTo("aB92xK7");
  }

  @Test
  @DisplayName(
      "identity survives being held in a set, which is the trap id-based equality falls into")
  void identityIsStableInsideACollection() {
    ShortLinkEntity entity = new ShortLinkEntity("aB92xK7", DESTINATION);
    Set<ShortLinkEntity> set = new HashSet<>();
    set.add(entity);

    // With id-based equality the entity's hash would change the moment it was persisted, and
    // this lookup would start failing after a save while passing here. Keying on the code
    // means there is no before-and-after to get wrong.
    assertThat(set).contains(new ShortLinkEntity("aB92xK7", "https://example.com/other"));
  }

  @Test
  @DisplayName("construction rejects null, so an unusable row can never be attempted")
  void rejectsNullArguments() {
    assertThatThrownBy(() -> new ShortLinkEntity(null, DESTINATION))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("shortCode");

    assertThatThrownBy(() -> new ShortLinkEntity("aB92xK7", null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("destinationUrl");
  }

  @Test
  @DisplayName("toString omits the destination but keeps the code (NFR-14)")
  void toStringOmitsDestination() {
    ShortLinkEntity entity =
        new ShortLinkEntity("aB92xK7", "https://example.com/reset?token=secret-token-value");

    assertThat(entity.toString()).contains("aB92xK7").doesNotContain("secret-token-value");
  }
}
