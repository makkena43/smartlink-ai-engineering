-- SmartLink initial schema.
--
-- Forward-only and additive-first. Scenario 02 adds expiration as a nullable column, so
-- nothing here declares NOT NULL in a way that presumes expiry's absence.
--
-- Written in standard SQL where PostgreSQL offers a shorthand. `timestamp with time zone`
-- rather than `timestamptz` is the same type to PostgreSQL and the only spelling H2 accepts,
-- which is what lets the `h2` demo profile run this exact migration instead of a parallel
-- copy that could drift from it.

CREATE TABLE short_link (
    id              bigserial     PRIMARY KEY,

    -- The public handle. varchar(16) against a 7-character format leaves room for a format
    -- change without a type migration, at no cost.
    short_code      varchar(16)   NOT NULL,

    -- Stored byte-identical to what was submitted and accepted (GF-19). Never normalised:
    -- rewriting a stored destination would silently break signed URLs and tracking
    -- parameters, and the breakage would be invisible until a campaign underperformed.
    destination_url varchar(2048) NOT NULL,

    -- Database clock, not application clock. With multiple stateless instances the app-side
    -- alternative is a set of clocks that disagree, and disagreeing timestamps are the kind
    -- of bug that is only ever noticed long after the data is already wrong.
    created_at      timestamp with time zone NOT NULL DEFAULT now(),

    -- Updated by a single atomic statement on the redirect path, never read-modify-write.
    total_redirects bigint        NOT NULL DEFAULT 0,

    -- Uniqueness is the DATABASE's job, not the application's (GF-05, GF-06). An
    -- application-side check-then-insert is a race: two concurrent requests can both observe
    -- a code as free before either writes. Letting this constraint arbitrate makes the
    -- collision impossible rather than unlikely, and turns collision handling into
    -- insert-and-retry against a guarantee instead of a probability argument.
    --
    -- PostgreSQL backs a UNIQUE constraint with an index, so this also serves the lookup on
    -- the redirect path. A separate index would be redundant.
    CONSTRAINT uq_short_link_short_code UNIQUE (short_code),

    -- A negative redirect count is unreachable through the application. The constraint
    -- exists so that if it ever becomes reachable - a bad migration, a manual correction, a
    -- future async aggregation path - the database refuses rather than silently storing
    -- nonsense that later gets reported as fact.
    CONSTRAINT ck_short_link_total_redirects_non_negative CHECK (total_redirects >= 0)
);

-- Deliberately absent, and both absences are load-bearing:
--
--   * NO `version` COLUMN. An optimistic-lock version here would be actively harmful.
--     total_redirects is written on every redirect, so a load-modify-save guarded by a
--     version makes two concurrent redirects of the same link collide - meaning the failure
--     rate would rise with a link's popularity, exactly inverting NFR-08. This is easy to
--     reintroduce by accident, because adding @Version looks like diligence.
--
--   * NO PERSONAL DATA COLUMN. No IP address, geography, user agent, referrer or device
--     (NFR-13). There is nowhere to put one, so no future change can start collecting
--     personal data without a migration a reviewer would see. Enforcing this in the schema
--     rather than in a code review is the difference between a rule and a habit.

COMMENT ON TABLE short_link IS
    'Short code to destination mappings. Aggregate counters only; no personal data.';
COMMENT ON COLUMN short_link.short_code IS
    'Public handle. Cryptographically random; never sequential (enumeration resistance).';
COMMENT ON COLUMN short_link.destination_url IS
    'Stored exactly as submitted. Never normalised.';
COMMENT ON COLUMN short_link.total_redirects IS
    'Incremented atomically on the redirect path. Best-effort: a failure here must not fail the redirect.';
