# Scenario 03 — Ambiguous · Validation

> **Status: complete.** Scenario 03 is implemented. The PostgreSQL-backed tests require a
> running Docker daemon; they are reproducible through `./mvnw verify`.

## 1. Acceptance criteria traceability
| AC | Requirement | Test / artifact | Level | Result |
|---|---|---|---|---|
| R-1 | Readiness reflects datastore health and recovers | `ReadinessRecoveryIT` | Fault-injection integration | Readiness transitions DOWN during a proxied outage, liveness remains UP, and readiness returns UP within the 10-second target after restoration. |
| R-2 | Resolve fails safe when a mapping cannot be verified | `ReadinessRecoveryIT`, `DependencyOutageIT` | API / fault injection | `503` is returned without a `Location` header; the mapping is served again only after recovery. |
| R-3 | Dependency work is time-bounded | `SlowDependencyIT`, `ADR-013` | Resilience integration | A 10-second injected query is bounded by the 2-second statement timeout; concurrent requests complete within the local guard budget rather than waiting for the dependency. |
| R-4 | Shutdown protects in-flight work | `GracefulShutdownIT` | Process-lifecycle integration | Readiness changes to refusing traffic, liveness remains UP, an in-flight redirect completes, and the port stops accepting requests after shutdown. |
| R-5 | Signals and targets are observable | `ReliabilitySignalsIT`, [engineering-spec.md](engineering-spec.md) §4 | API / documentation | Request tags and analytics-write-failure counter are exposed; management surface remains narrow and metrics do not leak destinations. |
| R-6 | Operator response is actionable | [runbook.md](../../runbook.md) | Operational documentation | Each stated symptom has a first diagnostic action, escalation path, and an explicit boundary on what this prototype cannot remedy. |

## 2. Fault-injection evidence
| Fault injected | Expected behaviour | Observed evidence |
|---|---|---|
| Database unreachable | Resolve → `503`, never a stale destination | `ReadinessRecoveryIT.resolveFailsSafeThenRecovers` asserts `503` with no `Location`; `DependencyOutageIT` covers the unavailable database path. |
| Database slow beyond timeout | Request fails within budget; pool is not exhausted | `SlowDependencyIT` injects a 10-second PostgreSQL delay and asserts a safe `503` within 3 seconds, including a 12-request concurrency check. |
| Dependency recovers | Readiness returns UP within bound | `ReadinessRecoveryIT.readinessRecoversOnItsOwn` restores the proxied datastore and polls for recovery within 10 seconds without an application restart. |
| Controlled shutdown during in-flight work | In-flight request completes; no new work accepted | `GracefulShutdownIT.inFlightRequestSurvivesShutdown` starts an in-flight redirect, closes the application, verifies the `302`, then verifies the port no longer answers. |

## 3. SLO measurement
The service exposes `http.server.requests` and `smartlink.analytics.write.failures`; their
semantics, the calculation method, and the target windows are in
[engineering-spec.md](engineering-spec.md) §4. The targets are **not** production claims:
fault-injection tests demonstrate bounded failure behavior, not 30-day availability or
capacity. Local performance results remain in `scripts/performance-test/` and must be read as
environment-bound regression evidence.

## 4. What was NOT made more reliable
Carried forward from `clarified-requirements.md` §3 so the boundary of the claim is visible
without hunting for it: no cache, circuit breaker, additional retry policy, bulkhead, load
shedding, multi-AZ, or multi-region deployment. These are deferred deliberately; they are not
claimed by Scenario 03.

## 5. Baseline before this scenario (A1)

Recorded from the committed configuration and tests, not from recollection. **This is the section
most at risk of flattering itself**: a reliability scenario running on a system that already had
reliability work is under constant temptation to claim credit for what was already there.

| Requirement | State before Scenario 03 | Gap |
|---|---|---|
| R-1 | **Already met.** `readiness.include: readinessState,db`, asserted by `DependencyOutageIT` | **Recovery untested.** That outage is a dead port, which never comes back |
| R-2 | **Already met.** `503`, no `Location`, no internals, correlated | Covered the database being *gone*, never *slow* |
| R-3 | **Partial, and the gap was load-bearing.** Hikari `connection-timeout: 2000` | **No statement timeout.** Once a connection was acquired, a query could run indefinitely |
| R-4 | **Partial.** `server.shutdown: graceful` was set | No grace bound, no test — the setting had never been observed to do anything |
| R-5 | **Absent** | No metrics endpoint; the analytics failure path emitted a log line and nothing countable |
| R-6 | **Absent** | — |

Two requirements were already met, two half-met, two did not exist. Stating that plainly is the
point: the deliverable of this scenario is the reasoning, and reasoning that starts by overstating
the gap is worthless.

## 6. R-3 measured before and after

Task A3 required the harness to exist *before* the fix, and this is why: a timeout that has never
been observed to fire is indistinguishable from one that does not work.

| Measurement | Before | After |
|---|---:|---:|
| Single resolve against a 10 s injected query | **> 20 s** (client gave up) | **~2 s**, safe `503` |
| 12 concurrent resolves | **131 s** | **~2 s** |
| Runtime of these 4 tests | 216 s | 27 s |

### The finding inside the finding

The injected delay was 10 seconds and one request took **more than 20**. The service was waiting
for it twice — `QueryTimeoutException` is a `TransientDataAccessException`, so the retry classifier
sent the same expensive query back to a database that had just demonstrated it could not keep up.

`TransientFailures` already warned about this in its own Javadoc — *"a classifier that treats
everything as transient looks perfectly correct on a healthy system and amplifies load precisely
when the dependency is already struggling"* — and then did it, because nothing had ever produced a
timeout to check the claim against. A correct comment, unenforced. Both parts are recorded as
[ADR-013](../../decisions.md#adr-013).

## 7. The one approved behaviour change to an existing test

A7 requires any changed Scenario 01/02 test to be treated as a behaviour change with documented
rationale. There is exactly one, and it is not cosmetic.

**`BoundedRetryTest` asserted that a query timeout is retried** — it was the parameterised example
of a retryable failure. ADR-013 reverses that deliberately, so the case moved from
`retryableFailures` to `nonRetryableFailures` **with its reason attached**, rather than being
deleted.

Two further uses of `QueryTimeoutException` in that file were incidental: it happened to be the
stand-in for "some transient failure" in tests about retry *mechanics*. Those switched to
`DataAccessResourceFailureException`, still transient, so they assert exactly what they did before.

**No other Scenario 01 or 02 test was edited.**

## 8. Quality gates (A7)

| Gate | Result |
|---|---|
| Build · Spotless | ✅ |
| Unit + controller | ✅ 172 |
| Integration | ✅ 98 — 92 on real PostgreSQL, 6 on the H2 demo profile |
| Coverage — line / branch | ✅ 93.3 % / 79.3 % (**up** from 92.6 / 78.7) |
| SpotBugs HIGH | ✅ 0 |
| ArchUnit | ✅ domain still framework-free |
| Scenario 01 + 02 contracts | ✅ `302` / `404` / `410` / `503` all unchanged |
| Demo profile still works | ✅ `DemoProfileIT` exercises the H2 `SET QUERY_TIMEOUT` override |

The last row is Scenario 02's lesson applied rather than restated: the timeout is PostgreSQL
syntax, the demo profile needed its own spelling, and a test proves it instead of a comment
claiming it.

## 9. Not proven, and not claimed

1. **No production measurement.** Every number here is fault injection on one laptop.
2. **No traffic baseline**, so "elevated `503` rate" in the runbook has no threshold attached yet.
3. **Multi-instance behaviour is untested.** Expiry is consistent across instances by construction
   (ADR-012) — a design property, not an observed one.
4. **`statement_timeout` also applies to Flyway**, which migrates through the same pool. Nothing is
   at risk today; a migration needing more than 2 s must raise the cap deliberately.
5. **The metrics endpoint is unauthenticated**, like everything else in this prototype (GF-03). A
   real deployment binds and authenticates the management port separately. Adding the endpoint
   without saying so would be shipping an information leak with a reliability label on it.
6. **No performance re-run.** The resolve path gained no work — a statement timeout is a connection
   setting, not a step — but that is a reasoned expectation, not a measurement.
