package com.smartlink.support;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A single PostgreSQL container shared by every integration test in the suite.
 *
 * <p>Deliberately the singleton pattern rather than JUnit's {@code @Testcontainers} lifecycle. That
 * annotation starts and stops a container per test class, which on a suite of any size dominates
 * the runtime — and a slow suite is one that stops being run before every commit, at which point
 * the tests have stopped doing their job regardless of what they assert.
 *
 * <p>The container is never stopped explicitly. Ryuk terminates it when the JVM exits, so an
 * aborted run cannot leave it behind.
 *
 * <p>A real PostgreSQL is used rather than an in-memory substitute because most of what these tests
 * assert is database behaviour: that a unique constraint arbitrates a race, that {@code UPDATE …
 * SET x = x + 1} serialises at row level, that Flyway and Hibernate agree on the schema. Every one
 * of those would be tested against a fiction under H2.
 */
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  protected static PostgreSQLContainer<?> postgres() {
    return POSTGRES;
  }
}
