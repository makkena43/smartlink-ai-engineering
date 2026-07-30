package com.smartlink.support;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes the database <strong>slow</strong> rather than absent, on demand.
 *
 * <p>{@code DependencyOutageIT} already covers the database being *gone*, which is the easy case:
 * the connection is refused immediately and the failure is loud. A dependency that answers, just
 * far too slowly, is the harder and more common production failure — it holds a request thread for
 * as long as it feels like, and a service with no time budget will hold every thread it has and
 * stop serving the requests that could have succeeded.
 *
 * <p><strong>Mechanism.</strong> The real table is renamed aside and replaced by a view of the same
 * name whose {@code WHERE} clause calls {@code pg_sleep}. Every read of {@code short_link}
 * therefore blocks inside PostgreSQL, on a real connection, in a real query — which is the point. A
 * mocked repository that returns late would exercise a sleep in the test JVM and prove nothing
 * about whether the *database* interaction is bounded.
 *
 * <p>The view is auto-updatable, so writes continue to work and the fault stays confined to
 * latency.
 *
 * <p><strong>This mutates shared state.</strong> The suite uses one container for every test class,
 * so {@link #restore()} must run even when an assertion fails. Callers use {@code @AfterEach}, and
 * {@link #restore()} is written to be safe to call when {@link #install(int)} never ran.
 */
public final class SlowDatabase {

  private final JdbcTemplate jdbc;

  public SlowDatabase(DataSource dataSource) {
    // A fresh template, deliberately not the application's: administering the fault through the
    // same bounded path being tested would make the fault injection itself time out.
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(0);
  }

  /** Makes every read of {@code short_link} take at least {@code seconds}. */
  public void install(int seconds) {
    jdbc.execute(
        """
        create or replace function smartlink_slow_probe() returns boolean as $$
        begin
          perform pg_sleep(%d);
          return true;
        end;
        $$ language plpgsql
        """
            .formatted(seconds));
    jdbc.execute("alter table short_link rename to short_link_actual");
    jdbc.execute(
        "create view short_link as select * from short_link_actual where smartlink_slow_probe()");
  }

  /** Undoes {@link #install(int)}. Safe to call unconditionally. */
  public void restore() {
    // `drop view if exists` is NOT sufficient: when the fault was never installed, short_link is
    // a table, and PostgreSQL fails with "short_link is not a view" rather than skipping it. The
    // IF EXISTS guard covers absence, not the wrong relation kind. Found by this very method
    // erroring in @AfterEach on a test that had already restored.
    jdbc.execute(
        """
        do $$
        begin
          if exists (select 1 from information_schema.views
                     where table_schema = current_schema() and table_name = 'short_link') then
            execute 'drop view short_link';
          end if;

          if exists (select 1 from information_schema.tables
                     where table_schema = current_schema() and table_name = 'short_link_actual') then
            execute 'alter table short_link_actual rename to short_link';
          end if;
        end $$
        """);
    jdbc.execute("drop function if exists smartlink_slow_probe()");
  }
}
