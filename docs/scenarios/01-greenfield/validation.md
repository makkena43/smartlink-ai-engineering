# Greenfield Validation

Evidence that v1 does what [`engineering-spec.md`](engineering-spec.md) says it does.

**Status:** T1–T9 complete. T10 (packaging, architecture overview, final review) outstanding.

```
mvn verify        232 tests, 0 failures     line 91.8%   branch 77.7%
smoke-test.sh      25 checks, 0 failures    against docker compose
```

---

## 1. Requirement traceability

Every requirement maps to at least one automated test. A requirement with no test is not done,
regardless of whether the code exists.

| Requirement | Verified by | Result |
|---|---|---|
| GF-01 create link | `CreateLinkUseCaseTest`, `CreateLinkIT`, `SmartLinkEndToEndIT` | pass |
| GF-02 canonical short URL | `SmartLinkEndToEndIT.createRedirectAndReadUsage` | pass |
| GF-03 anonymous creation | `SmartLinkEndToEndIT` — no credential sent anywhere | pass |
| GF-04 independent links | `CreateLinkUseCaseTest.neverLooksUpByDestination`, `sameDestinationYieldsTwoLinks` | pass |
| GF-05 one code, one destination | `SchemaConstraintsIT.shortCodeIsUnique`, `ShortLinkRepositoryIT.duplicateShortCodeIsRejectedByDatabase` | pass |
| GF-06 concurrency correctness | `ShortLinkRepositoryIT.concurrentInsertsOfSameCodeProduceOneRow`, `CreateLinkIT.forcedConcurrentCollisionHasOneWinner` | pass |
| GF-07 exact destination | `RedirectControllerTest.locationIsByteIdentical`, `ShortLinkRepositoryIT.roundTripsDestinationByteIdentical` | pass |
| GF-08 standards-compliant redirect | `RedirectControllerTest.returns302WithLocation`, end-to-end over real HTTP | pass |
| GF-09 unknown code → 404 | `RedirectControllerTest`, `SmartLinkEndToEndIT.unknownCodeReturns404` | pass |
| GF-10 input validation | `DestinationPolicyTest` (68 cases across 6 groups) | pass |
| GF-11 analytics | `SmartLinkEndToEndIT.createRedirectAndReadUsage` | pass |
| GF-12 unauthenticated analytics | `SmartLinkEndToEndIT` | pass |
| GF-13 health information | `SmartLinkApplicationIT`, **`DependencyOutageIT`** | pass |
| GF-14 scheme allowlist | `DestinationPolicyTest$Schemes` (12) | pass |
| GF-15 blocked address ranges | `DestinationPolicyTest$BlockedRanges` (22) | pass |
| GF-16 notation evasion | `DestinationPolicyTest$NotationTable` (13) | pass |
| GF-17 length bounds | `DestinationPolicyTest$BoundsAndStorage` | pass |
| GF-18 control chars / header integrity | `DestinationPolicyTest$ControlCharacters` (11), **`HeaderInjectionIT`** (7) | pass |
| GF-19 validated-at-creation integrity | `ShortLinkEntity` immutability, `CreateLinkUseCaseTest.storesDestinationVerbatim` | pass |
| NFR-01 durability | `CreateLinkIT.createsAndPersists`, `ShortLinkRepositoryIT` | pass |
| NFR-02 fail safely, never unverified | **`DependencyOutageIT.redirectFailsSafely`**, `ResolveLinkUseCaseTest.lookupFailurePropagates` | pass |
| NFR-03 bounded retries | `BoundedRetryTest` (15) | pass |
| NFR-04 safe errors | `ErrorContractTest$NoLeakage`, `DependencyOutageIT.outageResponseIsSafe` | pass |
| NFR-05 browser agnostic | Standard HTTP only; end-to-end over real HTTP | pass |
| NFR-06 horizontal scaling | `LayeringTest.noStaticMutableState` — stateless by construction | pass |
| NFR-07 / NFR-08 workload, hot keys | `SmartLinkEndToEndIT.concurrentRedirectsLoseNoCounts` · **hot-key contention measured**: p95 ≈ 2× and throughput ≈ 0.66× versus spread load, ratio stable across 3 runs | measured, not extrapolated |
| NFR-09 abuse prevention | Documented only (spec §8.2); out of scope per requirements §6 | n/a |
| NFR-10 SLI/SLO defined | Spec §9 · measured in `RESULTS.md` | pass |
| NFR-11 automated coverage | This document | pass |
| NFR-12 repeatable setup | `smoke-test.sh` against `docker compose` | pass |
| NFR-13 privacy | `SchemaConstraintsIT.hasNoPersonalDataColumn`, `SmartLinkEndToEndIT.analyticsCarriesNoPersonalData` | pass |
| NFR-14 injection resistance / log hygiene | **`LogHygieneIT`** (5), parameterised queries throughout | pass |
| NFR-15 validation placement | `LayeringTest.domainIsFrameworkFree` — policy cannot be bypassed | pass |
| NFR-16 fail closed | `DestinationPolicyTest$Resolution.unresolvableHostFailsClosed` | pass |

---

## 2. The named suite

The task decomposition named nine tests as the ones guarding decisions invisible in the code they
protect. Actual class names differ in three cases; the mapping is recorded rather than the files
renamed, because the test's identity is what it asserts.

| Named in decomposition | Implemented as | Guards |
|---|---|---|
| `AnalyticsFailureIT` | `AnalyticsFailureIT` | Fail-open, against a **real** database refusal |
| `ConcurrentRedirectIT` | `SmartLinkEndToEndIT.concurrentRedirectsLoseNoCounts` · `ShortLinkRepositoryIT.concurrentIncrementsLoseNoCounts` | Atomic increment |
| `DatastoreUnavailableIT` | `DependencyOutageIT` | 503, never stale or guessed |
| `RetryPolicyTest` | `BoundedRetryTest` | Retry **upper** bound and refusals |
| `ConcurrentCreateIT` | `CreateLinkIT.concurrentCreatesProduceDistinctCodes` | GF-06 |
| `ForcedCollisionIT` | `CreateLinkIT.forcedConcurrentCollisionHasOneWinner` | Insert-and-retry under contention |
| `DestinationPolicyTest` | `DestinationPolicyTest` | Notation table |
| `HeaderInjectionTest` | `HeaderInjectionIT` | Both CRLF defences, independently |
| `ErrorReflectionTest` | `ErrorContractTest$NoLeakage` | No raw input echoed |

---

## 3. Test inventory

| Suite | Tests |
|---|---:|
| `DestinationPolicyTest` (6 groups) | 68 |
| `CodeGeneratorTest` | 20 |
| `SmartLinkEndToEndIT` | 17 |
| `BoundedRetryTest` | 15 |
| `CorrelationIdFilterTest` | 13 |
| `CreateLinkUseCaseTest` | 11 |
| `RedirectControllerTest` | 11 |
| `ErrorContractTest` (3 groups) | 11 |
| `ShortLinkRepositoryIT` | 9 |
| `ResolveLinkUseCaseTest` | 7 |
| `HeaderInjectionIT` | 7 |
| `DependencyOutageIT` | 6 |
| `SchemaConstraintsIT` | 6 |
| `SmartLinkApplicationIT` | 6 |
| `ShortLinkEntityTest` | 6 |
| `AnalyticsFailureIT` | 5 |
| `LogHygieneIT` | 5 |
| `LayeringTest` | 4 |
| **Total** | **232** |

## 4. Quality gates

| Gate | Threshold | Result |
|---|---|---|
| Build | zero errors | ✅ |
| Format — Spotless | zero violations | ✅ |
| Unit + controller | 100 % pass | ✅ 166 |
| Integration — real PostgreSQL | 100 % pass | ✅ 66 |
| Coverage — line | ≥ 85 % | ✅ 91.8 % |
| Coverage — branch | ≥ 75 % | ✅ 77.7 % |
| Architecture — ArchUnit | dependency rule holds | ✅ |
| Smoke — `docker compose` | all checks | ✅ 25/25 |
| Static analysis — SpotBugs (HIGH) | zero findings | ✅ 0 |
| Dependency scan — Trivy | no HIGH/CRITICAL | ✅ 0 (was 22) |
| Secret scan — Trivy | zero | ✅ 0 |
| Container image scan — Trivy | no HIGH/CRITICAL | ✅ 0 (was 4) |
| Performance | method and limits reported | ✅ see `scripts/performance-test/RESULTS.md` |

**Branch coverage sits at 77.7 % against a 75 % gate.** A thin margin, and worth stating plainly:
it has been earned by writing tests, never by lowering the threshold. `AddressPolicy` holds many
range branches and is the main contributor to the remainder.

---

## 5. Bugs found by testing, not by review

The genuine value of the suite is here. Each of these passed code review — mine — and was caught
only by something executing.

| Found by | Defect |
|---|---|
| `SmartLinkApplicationIT.actuatorSurfaceIsNarrow` | A catch-all handler swallowed `NoResourceFoundException`, returning **500 for every unmatched path**. Written to guard actuator exposure; found a logic regression in another task |
| `DependencyOutageIT.readinessGoesDown` | **Readiness reported UP with the database unreachable** — Spring's default readiness group never consults `db`. The T1 readiness test passed throughout, because the database was up |
| `DependencyOutageIT.createFailsSafely` | **Create returned 500 while redirect returned 503** for the same outage. `CannotCreateTransactionException` descends from `TransactionException`, not `DataAccessException` |
| `CreateLinkIT` (3 tests) | `@Transactional(REQUIRES_NEW)` with the catch *inside* leaves the transaction rollback-only; the collision was handled correctly and the request still failed with `UnexpectedRollbackException` |
| `LogHygieneIT` | Five **framework** loggers emit the destination at DEBUG. None from `com.smartlink` — closed by pinning framework log levels |
| `HeaderInjectionIT` | `TestRestTemplate` follows redirects, so every redirect assertion written with it was describing the destination site's response, not this service's |
| `DestinationPolicyTest$NotationTable` | Two fixtures were arithmetically wrong. Their failure demonstrated the policy genuinely evaluates hosts rather than reflexively refusing unusual ones |

---

## 6. Not proven

Stated plainly, because the alternative is letting a reviewer find them.

1. **No SLO is demonstrated.** Spec §9 targets are design intent. Measurement is T9, and a laptop measurement is a regression signal, not evidence of production capacity.
2. **NFR-07 / NFR-08 are partial.** Concurrency correctness under load is proven (120 concurrent redirects on one row, all counted). Throughput, latency and hot-key *performance* are not.
3. **Single instance.** Horizontal scalability is a property of the design — no node-local state, asserted by ArchUnit — not something a single process demonstrates.
4. **TOCTOU stands open (R-1b).** Destinations are validated at creation; a hostname re-pointed afterwards is not detected. Not fixable at creation time.
5. **No abuse controls.** Rate limiting is scenario 03 and bounded even there.
6. **Homograph domains not addressed (R-1c).** A phishing control, deliberately not attempted.
