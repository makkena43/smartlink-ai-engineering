package com.smartlink.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartlink.support.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * T3 acceptance: the shape of the schema itself, asserted against a migrated database.
 *
 * <p>These are structural tests. They exist because two of T3's most important properties are
 * *absences*, and an absence is exactly what a behavioural test cannot notice: nothing fails when
 * an extra column appears, it just quietly starts being used.
 *
 * <p>The two guarded here are the ones most likely to be reintroduced in good faith — adding
 * {@code @Version} reads as diligence, and adding an IP column reads as useful analytics.
 */
@SpringBootTest
class SchemaConstraintsIT extends AbstractPostgresIT {

  @Autowired private JdbcTemplate jdbc;

  private List<String> columnNames() {
    return jdbc.queryForList(
        "select column_name from information_schema.columns where table_name = 'short_link'",
        String.class);
  }

  @Test
  @DisplayName("migration produces exactly the expected columns, and no others")
  void schemaHasExactlyTheExpectedColumns() {
    // `expires_at` added by scenario 02 (V2). This assertion failing on that migration is the
    // test working, not the test being wrong: it exists so a column cannot appear without
    // someone deciding it should. The addition is recorded as an approved schema change in
    // 02-brownfield/impact-analysis.md §5, which is the difference between updating an
    // expectation and quietly bending a test to fit.
    //
    // The two sibling assertions below - no version column, no personal-data column - are
    // untouched and still pass.
    assertThat(columnNames())
        .containsExactlyInAnyOrder(
            "id", "short_code", "destination_url", "created_at", "total_redirects", "expires_at");
  }

  @Test
  @DisplayName("no optimistic-lock version column exists (NFR-08)")
  void hasNoVersionColumn() {
    // A version column would make total_redirects a load-modify-save, so concurrent redirects
    // of one link would collide - failure rate rising with popularity, inverting NFR-08.
    // Asserted structurally because the mistake looks like good practice from the inside.
    assertThat(columnNames()).doesNotContain("version", "opt_lock", "lock_version");
  }

  @Test
  @DisplayName("no column can hold personal data (NFR-13)")
  void hasNoPersonalDataColumn() {
    // Privacy enforced by the schema rather than by review. With nowhere to put an IP address,
    // no future change can start collecting one without a migration a reviewer would see -
    // which converts "we agreed not to" into "we cannot".
    assertThat(columnNames())
        .doesNotContain(
            "ip_address",
            "client_ip",
            "user_agent",
            "referrer",
            "referer",
            "country",
            "region",
            "city",
            "device",
            "device_type",
            "session_id",
            "user_id");
  }

  @Test
  @DisplayName("short_code carries a unique constraint, which is the collision authority")
  void shortCodeIsUnique() {
    List<String> uniqueConstraints =
        jdbc.queryForList(
            """
            select tc.constraint_name
            from information_schema.table_constraints tc
            join information_schema.key_column_usage kcu
              on tc.constraint_name = kcu.constraint_name
            where tc.table_name = 'short_link'
              and tc.constraint_type = 'UNIQUE'
              and kcu.column_name = 'short_code'
            """,
            String.class);

    assertThat(uniqueConstraints)
        .as("uniqueness must be enforced by the database, not by an application pre-check")
        .isNotEmpty();
  }

  @Test
  @DisplayName("created_at defaults to the database clock, not an application clock")
  void createdAtHasDatabaseDefault() {
    String defaultExpression =
        jdbc.queryForObject(
            """
            select column_default from information_schema.columns
            where table_name = 'short_link' and column_name = 'created_at'
            """,
            String.class);

    // With several stateless instances, an application-set timestamp means several clocks that
    // disagree. Disagreeing timestamps are only ever noticed long after the data is wrong.
    assertThat(defaultExpression).isNotNull().contains("now()");
  }

  @Test
  @DisplayName("a negative redirect count is refused by the database")
  void negativeCountIsRefused() {
    jdbc.update(
        "insert into short_link (short_code, destination_url) values (?, ?)",
        "chkneg1",
        "https://example.com/check-constraint");

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    jdbc.update(
                        "update short_link set total_redirects = -1 where short_code = ?",
                        "chkneg1")))
        .as("unreachable through the application, but the database must still refuse it")
        .isNotNull();
  }
}
