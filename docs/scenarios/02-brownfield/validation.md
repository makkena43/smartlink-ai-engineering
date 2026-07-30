# Scenario 02 — Brownfield · Validation

Evidence that optional link expiration works **and that the existing service did not silently
change**. The second half is the point of a brownfield scenario.

```
./mvnw verify     251 tests, 0 failures     (was 232 before this change)
                  172 unit + 79 integration
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

## 6. Not done

1. **B8 quality gates and B9 rollout documentation are not complete.** Build, tests and SpotBugs are green; the dependency/secret/image rescan and the performance re-run after this change have not been executed.
2. **No rollback rehearsal was performed.** The rollback posture is reasoned in `impact-analysis.md` §7 and is structurally sound — the old application never selects the new column — but it was not demonstrated by actually running the previous jar against the migrated schema.
3. **No expiry mutation** (A-13), no retention or cleanup of expired rows, no cache interaction. All deliberate.
4. **`expires_at` is unindexed.** Correct while nothing queries by it; it becomes wrong the day a retention job exists.
