-- Scenario 02 (brownfield): optional link expiration.
--
-- EXPAND ONLY. This release adds a column and does nothing else. No column is dropped,
-- renamed, retyped or backfilled, and there is no contract step - those belong to a later
-- release, after every deployed instance is known to be running code that tolerates the new
-- shape. Doing expand and contract in one release is how a rollback turns into an outage.
--
-- Rollback posture: an application rolled back to the Greenfield version never selects this
-- column, so every link simply behaves as non-expiring. The migration therefore needs no
-- down-step, and none is provided - a destructive down-migration is a loaded gun kept next
-- to a panic button.

ALTER TABLE short_link
    -- Nullable, and NULL carries meaning: the link does not expire.
    --
    -- That is what makes existing rows correct without touching them. A NOT NULL column with
    -- a sentinel "far future" default would have required rewriting every existing row, taken
    -- a lock proportional to table size, and left the schema lying about the domain - there
    -- is a real difference between "expires in the year 9999" and "does not expire".
    --
    -- No DEFAULT is declared for the same reason: a default would make every future insert
    -- expiring-by-accident if the application ever forgot to set it.
    ADD COLUMN expires_at timestamp with time zone;

-- Deliberately no index.
--
-- Expiry is evaluated on a row already fetched by short_code, so no query filters or sorts by
-- this column. An index would add write cost on every insert to serve a query nobody makes.
-- It becomes justified only if a retention job is added later, and that job does not exist.

COMMENT ON COLUMN short_link.expires_at IS
    'UTC instant after which the link stops resolving. NULL means it never expires. '
    'Set at creation only; never mutated (A-13).';
