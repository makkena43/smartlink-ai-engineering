# Scenario 02 — Brownfield · Impact Analysis

**Task B1.** Produced by reading the committed Greenfield code, not by recalling what it was
meant to do. Every class, endpoint and test named below was located in the repository.

**Baseline before any change:** `./mvnw verify` → **232 tests, 0 failures**, line 91.8 %,
branch 77.7 %, SpotBugs 0. That number is what BC-5 is measured against.

---

## 1. Impacted modules

| Layer | Component | Change | Blast radius |
|---|---|---|---|
| domain | `Link` (record) | Add `expiresAt` component | **Highest — see §5.** Constructed directly in 3 test files and 1 production file |
| domain | `LinkLifecycle` *(new)* | Active/expired rule as a pure function | None; new type |
| domain | `port/TimeSource` *(new)* | Authoritative-time port | None; new type. **No clock abstraction exists today** |
| domain | `ResolvedLink` *(new)* | A link paired with the database's reading of "now" | None; new type. Added after review — see §8 |
| domain | `port/LinkRepository` | `insert` gains an expiry argument | 1 implementation, 2 test fakes |
| application | `CreateLinkUseCase` | Validate and pass expiry through | Create path only |
| application | `ResolveLinkUseCase` | Lifecycle check between lookup and increment | **Public redirect path** |
| application | `ReadAnalyticsUseCase` | Returns `ResolvedLink` so status uses the same clock as the redirect | None externally; see §8 |
| application | `exception/LinkExpiredException` *(new)* | 410 mapping | None; new type |
| application | `exception/InvalidExpiryException` *(new)* | 400 mapping | None; new type |
| api | `ErrorCode` | Add `LINK_EXPIRED` (410), `INVALID_EXPIRY` (400) | Additive; existing 6 constants unchanged |
| api | `ApiExceptionHandler` | Two handlers | Additive |
| api | `dto/CreateLinkRequest` | Optional `expiresAt` | **Additive — omission must behave as before** |
| api | `dto/CreateLinkResponse` | Add `expiresAt` | Additive |
| api | `dto/AnalyticsResponse` | Add `expiresAt`, `status` | Additive |
| api | `LinkController` | Map the new fields | Create + analytics |
| api | `RedirectController` | **Unchanged.** Lifecycle is decided in the use case | None |
| infrastructure | `ShortLinkEntity` | Nullable `expires_at` column | Mapping only |
| infrastructure | `JpaLinkRepository` | Persist and read expiry | Mapping only |
| infrastructure | `time/DatabaseTimeSource` *(new)* | The database's clock, create path only | Additive |
| infrastructure | `time/JdbcInstants` *(new)* | Normalises driver temporal types | Additive; see §8 |
| database | `V2__add_expires_at.sql` *(new)* | Nullable column | **Expand-only** |

---

## 2. Data-flow impact

| Flow | Changes? | Detail |
|---|---|---|
| `POST /api/v1/links` | **Yes** | New optional field, new validation, new persisted column |
| `GET /{code}` — active link | **No observable change** | Still `302`, same `Location`, same `Cache-Control`, still increments |
| `GET /{code}` — expired link | **New** | `410`, no `Location`, **no increment** |
| `GET /{code}` — unknown / malformed | **No change** | Still `404`. Must stay indistinguishable from each other |
| `GET …/analytics` | **Additive** | Two fields added; `totalRedirects` unchanged in name, type and meaning |
| Datastore unavailable | **No change** | Still `503`, never a guess |
| Counter write fails | **No change** | Still fails open — the redirect is still served |

The ordering inside resolve is the part that carries risk: **verified lookup → lifecycle check
→ increment → emit `Location`**. Putting the lifecycle check after the increment would count
redirects that never happened; putting it before the lookup would make an expired link
indistinguishable from an unknown one.

---

## 3. Schema impact

| | |
|---|---|
| Migration | `V2__add_expires_at.sql`, forward-only |
| Statement | `ALTER TABLE short_link ADD COLUMN expires_at timestamp with time zone` |
| Nullability | Nullable. `NULL` means non-expiring, so **existing rows need no backfill** |
| Expand/migrate/contract | Expand only. No contract step in this release |
| Locking | `ADD COLUMN` with no default and no rewrite — metadata-only in PostgreSQL 11+ |
| Existing rows | Untouched |

---

## 4. API contract impact

Every change is additive, which is what keeps the service on `/api/v1`:

| Element | Change | Breaking? |
|---|---|---|
| `destinationUrl` request field | none | no |
| `expiresAt` request field | **added, optional** | no — omission preserves current behaviour |
| `code`, `shortUrl`, `destinationUrl`, `createdAt` responses | none | no |
| `expiresAt` response field | **added** | no — additive |
| `totalRedirects` | none | no |
| `status` analytics field | **added** | no — additive |
| `302` on active resolve | none | no |
| `404` on unknown code | none | no |
| **`410` on expired resolve** | **new status for a new state** | **no** — no previously-reachable state changes its status. A link that would have returned `302` yesterday only returns `410` if someone gave it an expiry |

---

## 5. Test impact — and the decision it forced

**`Link` is a record, and 3 test files construct it directly:**
`RedirectControllerTest`, `CreateLinkUseCaseTest`, `ResolveLinkUseCaseTest`
(plus `JpaLinkRepository` in production).

Adding a component to a record changes its canonical constructor, so the naive change breaks
all three at **compile time**. BC-5 says the Greenfield suite must pass *untouched*, and a
suite that no longer compiles has not passed.

There are two honest readings, and they are not equivalent:

- A **mechanical** edit — adding `null` to a constructor call — is a signature change to an
  internal type. It is not a behaviour change.
- A changed **assertion** would be a behaviour change, and BC-5 exists to catch exactly that.

Rather than argue the distinction, the design avoids needing it:

> **Decision: `Link` keeps a 4-argument constructor overload that defaults `expiresAt` to
> `null`.** The canonical constructor gains the component; the old arity still compiles and
> means "non-expiring", which is the correct default anyway. `CreateLinkUseCase.create` keeps a
> single-argument overload for the same reason.

### Correction — this analysis initially over-claimed

The first version of this section concluded *"BC-5 holds literally; no Greenfield test file is
edited at all."* **That was wrong, and the compiler said so.**

The record overload did work — no `new Link(...)` call site broke. But two other signatures
also had to change, and neither is reachable by an overload:

| Change | Why an overload cannot save it | Files affected |
|---|---|---|
| `LinkRepository.insert` gains `expiresAt` | Test fakes *implement* the interface. A new abstract method must be implemented by every implementor; an overload does not remove that obligation | `CreateLinkUseCaseTest`, `ResolveLinkUseCaseTest` |
| Use cases gain a `TimeSource` constructor argument | Tests construct them directly. A convenience overload would have to invent a clock, hiding the dependency the change exists to make explicit | `CreateLinkUseCaseTest`, `ResolveLinkUseCaseTest`, `CreateLinkIT` |

> **Superseded in part by §8.** The P1 fix removed `TimeSource` from `ResolveLinkUseCase`
> entirely — the redirect path now receives the database's clock with the row — and widened the
> repository port to `Optional<ResolvedLink>`. The same three test files were touched again, and
> again only their wiring. The count of edited files did not change; the reason for one of them
> did.

**Actual outcome: 3 Greenfield test files edited, and every edit is wiring.** A fixed
`TimeSource` constant was added and two fake `insert` signatures were widened. **Not one
assertion changed, and not one test was deleted or weakened.**

That distinction is the whole point of BC-5, so it is worth being precise rather than tidy: the
requirement exists to catch a *behaviour* change smuggled in as a test edit. A constructor
argument is not that. Claiming zero edits would have been a nicer sentence and a false one — and
the failure mode BC-5 guards against is exactly the temptation to keep the claim rather than the
guarantee.

### Existing tests, classified

| Test | Expected outcome | Classification |
|---|---|---|
| `SmartLinkEndToEndIT` (17) | pass unchanged | regression guard |
| `SmartLinkApplicationIT` (6) | pass unchanged | regression guard |
| `AnalyticsFailureIT` (5) | pass unchanged | **fail-open must survive the new branch** |
| `HeaderInjectionIT` (7) | pass unchanged | regression guard |
| `RedirectControllerTest` (11) | pass unchanged | protected by the `Link` overload — **0 edits** |
| `CreateLinkUseCaseTest` (11) | pass; **wiring edited** | fixed clock + fake `insert` arity. No assertion touched |
| `ResolveLinkUseCaseTest` (7) | pass; **wiring edited** | fixed clock + fake `insert` arity. No assertion touched |
| `CreateLinkIT` (5) | pass; **wiring edited** | fixed clock passed to the constructor. No assertion touched |
| `ErrorContractTest` (11) | pass unchanged | the 6 existing codes are untouched |
| `SchemaConstraintsIT` (6) | **expected to fail — column list** | ⚠️ **intentional behaviour change, see below** |
| remainder (151) | pass unchanged | regression guard |

**`SchemaConstraintsIT.schemaHasExactlyTheExpectedColumns` asserts the exact column set and
will fail once `expires_at` exists.** That is the test doing precisely its job: it was written
so that a column cannot appear without someone noticing. Adding the expected column to that
assertion is an *approved schema change*, recorded here, not a test being quietly bent to fit.

Its two sibling assertions — no `version` column, no personal-data column — must keep passing
untouched.

---

## 6. Risks introduced

| # | Risk | Treatment |
|---|---|---|
| BR-1 | Lifecycle check placed after the increment → expired attempts counted | Ordering asserted by test; analytics must not move on a `410` |
| BR-2 | Expired links leak existence, or unknown links start returning `410` | `404` and `410` cover disjoint states; unknown-code behaviour tested unchanged |
| BR-3 | Boundary flakiness from wall-clock time | `Clock` port; tests fix the instant rather than sleeping |
| BR-4 | Timezone ambiguity on input | UTC instants only; ISO-8601 with an offset required |
| BR-5 | The new branch swallows the fail-open guarantee | `AnalyticsFailureIT` runs unchanged and must still pass |
| BR-6 | Someone later adds a cache with a TTL outliving an expiry | Out of scope; recorded in the spec's risk table. A stale cache entry would resurrect an expired link |

---

## 7. Rollback

The case that actually happens is **application rolled back, migration left in place** — and
it is safe:

| Scenario | Result |
|---|---|
| Migration applied, new app | Expiry works |
| Migration applied, **old app** | Old app never selects `expires_at`; every link behaves as non-expiring. No error |
| Migration not applied, new app | Startup fails at Hibernate schema validation — loud, immediate, before serving traffic |

No `DROP` is issued in this release, so there is no destructive step to reverse. Any link
created with an expiry during the rolled-back window would resolve as non-expiring until the
new version returns — which is a **known and accepted** consequence of expand-only rollout,
not an oversight.

---

## 8. Revisions made after review

This section is appended rather than folded into the sections above, because the difference
between *what was planned* and *what review changed* is the part worth keeping.

Review of the completed implementation raised three contract mismatches. All three were valid.

### P1 — the clock contradicted the requirement it cited (§1, §6 BR-3)

The plan called for a `TimeSource` port over `Clock.systemUTC()`, and BR-3 justified it as
protection against "boundary flakiness from wall-clock time". That framing was the error, and it
propagated straight into the code: it treats the clock as a *testability* problem. A-12 asks for
something else — **one authoritative clock across instances**. `Clock.systemUTC()` gives each
instance its own, which is precisely the failure ADR-011 named in its own justification before
implementing it anyway.

251 tests passed over this. Every one injected a fixed clock, so the suite asserted the port was
*used* and never that the clock behind it was *shared*. A test that supplies the dependency it is
verifying cannot see this class of defect.

**Changed:** a native projection now selects `CURRENT_TIMESTAMP` alongside the row, returning
`ResolvedLink(link, observedAt)`. The redirect path pays **no extra round trip** — the query it was
already issuing carries the clock. `DatabaseTimeSource` covers the create path only. Analytics
returns `ResolvedLink` so its reported status cannot disagree with the redirect's. Recorded as
[ADR-012](../../decisions.md#adr-012), superseding ADR-011.

### P2a — `410` was implemented but never published

`RedirectController` returned `410` and no `@ApiResponse` documented it, so the generated OpenAPI
document — which this repository designates as the authoritative contract — did not mention the
new status at all. A contract that omits a reachable status is wrong in the way that matters:
integrators generate clients from it.

**Changed:** `@ApiResponse(responseCode = "410", ...)` added, plus a test asserting `/v3/api-docs`
actually contains it. Documenting it without asserting it would have re-created the same failure
one release later.

### P2b — malformed expiry returned the wrong error code

`CreateLinkRequest.expiresAt` was typed `Instant`, so Jackson rejected a malformed or zone-less
value during deserialisation and the response was `MALFORMED_REQUEST` — before any application
code ran. BF-03 specifies `INVALID_EXPIRY`. The caller could not distinguish "your JSON is broken"
from "your timestamp is unusable".

**The test that should have caught it did not, because it was written too loosely:** it accepted
`isIn(BAD_REQUEST, UNPROCESSABLE_ENTITY)` and asserted nothing about the body. A test permissive
enough to pass under two different designs is not testing the design.

**Changed:** the field is now `String` and parsed in `CreateLinkUseCase`. The assertion was
tightened to exactly `400` **and** a body containing `INVALID_EXPIRY`, and a fourth case
(`2026-13-45T00:00:00Z` — well-formed shape, impossible date) was added.

### P3 — found while verifying the P1 fix: the demo profile was broken and untested

Not raised by review. Found by starting the jar under the `h2` profile by hand after the P1 fix,
which is not something the plan called for.

The native projection introduced by P1 used PostgreSQL's `statement_timestamp()`, which **does not
exist in H2** — so every redirect under the demo profile returned `503`. Fixing that exposed a
second layer: the projection declared `Instant`, which the PostgreSQL driver satisfies via
`java.sql.Timestamp` and H2 does not, throwing `UnsupportedOperationException`. And `statement_timestamp()`
appeared a *second* time in `DatabaseTimeSource`, so every create carrying an expiry also `503`ed.

**None of it was visible to 252 passing tests.** Every integration test here runs against real
PostgreSQL — the right default, since most of them assert PostgreSQL behaviour — which left the
demo profile, the first thing a reviewer without Docker runs, with **zero automated coverage**.

**Changed:** `CURRENT_TIMESTAMP` (standard SQL) in both places; `JdbcInstants` normalises whatever
temporal type the driver returned and fails loudly on an unrecognised one; and `DemoProfileIT`
covers create, resolve, expiry, `410`, analytics agreement and migration portability on H2 —
needing no Docker to run.

The reusable lesson is not about H2. It is that **a suite's blind spots are shaped by its
fixtures**, and a path no fixture exercises is a path nobody is watching.
