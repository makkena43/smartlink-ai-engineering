# Scenario 02 — Brownfield · Validation

Evidence that optional link expiration works **and that the existing service did not silently
change**. The second half is the point of a brownfield scenario.

```
./mvnw verify     258 tests, 0 failures     (was 232 before this change)
                  172 unit + 86 integration
SpotBugs          0 findings at HIGH
```

---

## 1. New requirement traceability

| Requirement | Verified by | Result |
|---|---|---|
| BF-01 optional expiry at creation | `LinkExpiryIT.futureExpiryIsAccepted` | pass |
| BF-02 valid future UTC instant | `LinkExpiryIT.pastExpiryIsRefused`, `malformedExpiryIsRefused` | pass |
| BF-03 omission preserves behaviour | `LinkExpiryIT.omittedExpiryPreservesGreenfieldBehaviour` | pass |
| BF-04 no expiry ⇒ always active | `LinkLifecycleTest$NoExpiry`, `LinkExpiryIT.futureExpiryStillRedirects` | pass |
| BF-05 expired links do not redirect | `LinkExpiryIT.expiredLinkReturns410WithoutLocation`, `expiredAttemptDoesNotIncrementCounter` | pass |
| BF-06 `410 LINK_EXPIRED`, safe body | `LinkExpiryIT.expiredResponseIsSafe` | pass |
| BF-07 analytics exposes expiry + status | `LinkExpiryIT.analyticsReportsLifecycle` | pass |

## 2. Backward compatibility — the part that matters

| ID | Requirement | Evidence | Result |
|---|---|---|---|
| BC-1 | Pre-existing links still resolve | `LinkExpiryIT.legacyRowWithNullExpiryStillResolves` — a row with `NULL expires_at`, exactly the shape of every pre-migration row | pass |
| BC-2 | Omitted expiry behaves as before | `LinkExpiryIT.omittedExpiryPreservesGreenfieldBehaviour` | pass |
| BC-3 | Response shapes only gain fields | `totalRedirects` untouched in name, type and meaning; asserted by 3 unmodified Greenfield tests | pass |
| BC-4 | Migration additive and forward-only | `V2` is a single `ADD COLUMN`, nullable, no default, no backfill, no down-step | pass |
| BC-5 | Greenfield suite passes | **232/232 pass. 3 files edited — wiring only, no assertion changed.** See §3 | pass, with the recorded exception |

## 3. BC-5 in detail — what was edited and why

| File | Edit | Behaviour change? |
|---|---|---|
| `CreateLinkUseCaseTest` | Fixed `TimeSource` constant; fake `insert` arity | **No** — no assertion touched |
| `ResolveLinkUseCaseTest` | Fixed `TimeSource` constant; fake `insert` arity | **No** — no assertion touched |
| `CreateLinkIT` | Fixed `TimeSource` passed to constructor | **No** — no assertion touched |
| `SchemaConstraintsIT` | `expires_at` added to the expected column list | **Yes — approved schema change** |
| Every other Greenfield test (10 files) | none | — |

`SchemaConstraintsIT.schemaHasExactlyTheExpectedColumns` **failed on first run**, which is the
test working exactly as designed: it exists so a column cannot appear without someone deciding
it should. Its two siblings — no `version` column, no personal-data column — still pass
untouched.

The three wiring edits were **not** anticipated correctly in the first draft of the impact
analysis, which claimed zero test edits. The correction is recorded in
[`impact-analysis.md`](impact-analysis.md) §5 rather than quietly dropped.

## 4. Boundary behaviour

| Instant relative to expiry | Result | Test |
|---|---|---|
| No expiry set, any time | ACTIVE | `LinkLifecycleTest.neverExpires` |
| 1 ns before | ACTIVE → `302` | `activeJustBefore` |
| **exactly at expiry** | **EXPIRED → `410`** | `expiredAtTheInstant` |
| 1 ns after | EXPIRED → `410` | `expiredAfter` |
| 1 year after | EXPIRED, still | `expiryIsMonotonic` |

The boundary is **inclusive**: a link expiring at midnight stops working *at* midnight. Testable
as a plain assertion only because the rule takes `now` as a parameter — the `TimeSource` port is
what removes the need to sleep.

## 5. Status semantics preserved

| Condition | Status | Unchanged from Greenfield? |
|---|---|---|
| Active link | `302` + `Location` + `no-store` | yes |
| Unknown code | `404` | yes |
| Malformed code | `404` | yes |
| **Expired link** | **`410`, no `Location`** | new state, new status |
| Datastore unavailable | `503` | yes |
| Counter write fails | `302` anyway | yes — `AnalyticsFailureIT` passes unchanged |

`404` and `410` stay disjoint. No previously reachable state changed its status, which is what
keeps this additive and off `/api/v2`.

## 6. Quality gates (B8)

| Gate | Result |
|---|---|
| Build | ✅ |
| Format — Spotless | ✅ |
| Unit + controller | ✅ 172 |
| Integration | ✅ 86 — 80 on real PostgreSQL, 6 on the H2 demo profile |
| Coverage — line / branch | ✅ 92.6 % / 78.7 % (**up** from 91.8 / 77.7) |
| Static analysis — SpotBugs HIGH | ✅ 0 |
| Dependency vulnerabilities | ✅ 0 HIGH/CRITICAL |
| Secrets | ✅ 0 |
| Architecture rule — ArchUnit | ✅ domain still framework-free |

Coverage rose rather than fell, which is the expected shape when a change arrives with its own
tests rather than being retrofitted onto them.

## 7. Rollback rehearsal (B8) — performed, not merely reasoned

The first draft of this document listed the rehearsal as *not done* and the posture as
*structurally sound but undemonstrated*. It has now been executed.

**Method.** A PostgreSQL 16 container on an isolated port. The **brownfield** jar applied V1 and
V2 and created two links — one without expiry, one expiring in 2030. The application was stopped
and the **pre-change Greenfield jar**, built from commit `f3be7a6`, was started against that same
migrated database.

| Check | Result |
|---|---|
| Old app starts against the migrated schema | ✅ 12 s — Hibernate `validate` tolerates a column it does not map |
| Pre-existing link (`expires_at NULL`) resolves | ✅ `302` |
| Link carrying an expiry the old app knows nothing about | ✅ `302` — **resolves as non-expiring** |
| Old app can still create links | ✅ `201` |
| Analytics returns the old shape | ✅ no `expiresAt`, no `status` |
| Data loss | ✅ none — 3 rows, 1 retaining its expiry |

The third row is the one worth stating plainly: **during a rollback window, a link with an
expiry resolves as though it had none.** That is the known and accepted consequence of
expand-only delivery, predicted in `impact-analysis.md` §7 — not a defect found late. The expiry
value survives untouched and takes effect again the moment the newer version returns.

### Environment findings from the rehearsal

Two, both worth recording because both would silently invalidate a result:

1. **A native PostgreSQL on the host held `127.0.0.1:5432`**, shadowing the container's published port. The first attempts were talking to an entirely different database, and reported `role "smartlink" does not exist` rather than anything pointing at the real cause. The rehearsal was moved to port 5433. This does not affect any other result in this repository: the compose application reaches PostgreSQL over the Docker network, and Testcontainers binds random ports.
2. **A stale Docker volume** from a differently-named compose project persisted old credentials. `POSTGRES_*` variables are honoured only when initialising an empty data directory, so the container ignored the values it was given.

## 8. Post-review verification

Review of the completed implementation raised three contract mismatches (P1, P2a, P2b). A fourth
(P3) was found while verifying the fix for the first. All four are described in
[`impact-analysis.md` §8](impact-analysis.md#8-revisions-made-after-review); this section records
the evidence.

### Suite after the fixes

```
Tests run: 172, Failures: 0, Errors: 0, Skipped: 0   (unit)
Tests run:  86, Failures: 0, Errors: 0, Skipped: 0   (integration)
All coverage checks have been met.
BugInstance size is 0
BUILD SUCCESS
```

**258 tests**, up from 251 at the pre-review sign-off: `+1` OpenAPI assertion, `+6` demo profile.

### Evidence per finding

| # | Finding | How it is now prevented from recurring |
|---|---|---|
| P1 | Clock was per-instance, contradicting A-12 | The port cannot be satisfied by a local clock on the redirect path — the instant arrives *with the row* from the database. There is no local clock left to get wrong |
| P2a | `410` implemented but absent from the published contract | `openApiDocumentsExpiredResponse` fetches `/v3/api-docs` and asserts it contains `410`. Documenting without asserting would have re-created the gap |
| P2b | Malformed expiry returned `MALFORMED_REQUEST`, not `INVALID_EXPIRY` | Assertion tightened from `isIn(400, 422)` with no body check, to exactly `400` **and** a body containing `INVALID_EXPIRY`; a fourth case added |
| P3 | Demo profile broken in three ways, invisible to the suite | `DemoProfileIT` — 6 tests covering create, resolve, future expiry, `410`, analytics agreement and migration portability, **on H2, needing no Docker** |

### Demo profile, verified by hand before the test was written

Run against `java -jar target/smartlink-1.0.0.jar --spring.profiles.active=h2`:

| Check | Result |
|---|---|
| Link with no expiry resolves | ✅ `302` |
| Link with a future expiry resolves | ✅ `302` |
| Same link after expiry | ✅ `410` |
| `Location` header on the `410` | ✅ absent |
| Redirect count after one live and one refused attempt | ✅ `1`, status `EXPIRED` |
| Expiry in the past | ✅ `INVALID_EXPIRY` |
| Zone-less expiry | ✅ `INVALID_EXPIRY` |

Before the fixes, rows 2–7 were `503` or `404`.

### A note on the environment, since it caused a false signal

During this work the Docker daemon died mid-session and `mvn verify` reported **74 errors** across
every PostgreSQL-backed test. Not one was a code defect — `AbstractPostgresIT` could not reach a
Docker environment, and the same build passed unchanged after `colima restart`. It is recorded
because the failure mode is worth recognising: **a whole-suite failure that arrives all at once,
in milliseconds per test, at class-initialisation, is infrastructure and not regression.** Reading
one stack trace distinguished the two in seconds; re-running the suite would not have.

---

## 9. Not done

1. **No performance re-run after this change.** The redirect path gained one in-memory comparison on an already-fetched row, so no measurable effect is expected — but "expected" is not "measured", and it is not claimed as such.
2. **No expiry mutation** (A-13), no retention or cleanup of expired rows, no cache interaction. All deliberate.
3. **`expires_at` is unindexed.** Correct while nothing queries by it; wrong the day a retention job exists.
4. **Contract step not performed.** This release only expands. Nothing is dropped or renamed, by design.

---

## 10. Gate D — engineer sign-off

Required by [`task-decomposition.md`](task-decomposition.md) B9: code is read, quality gates are
green, high-impact decisions are recorded, and documentation matches the delivered system.

| Condition | State |
|---|---|
| Code read before commit | Complete |
| Quality gates green | Complete — see §8 |
| High-impact decisions recorded | Complete — [ADR-010](../../decisions.md#adr-010), [ADR-011](../../decisions.md#adr-011) *(superseded)*, [ADR-012](../../decisions.md#adr-012) |
| Documentation matches the delivered system | Complete — corrected after review; see §8 |
| Limitations stated rather than omitted | Complete — see §9 |

**Engineer of record:** Srinivas Makkena  
**Gate D approved:** Srinivas Makkena (engineer of record)  **Date:** 2026-07-31
