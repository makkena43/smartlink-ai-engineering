# Testing Strategy

Coverage is a **floor, not a target**. A high number over weak assertions is worse than a
lower number over strong ones, because it actively misleads — it converts "we did not check"
into "we checked and it was fine". Tests here assert behaviour, not implementation shape.

---

## 1. Levels

| Level | Covers | Runs | Speed |
|---|---|---|---|
| **Unit** | Code generation, alias policy, URL policy, expiry rule (v2), error mapping | `mvn test` (Surefire) | milliseconds — no Spring, no database |
| **Integration** | Persistence, Flyway migration, unique-code behaviour, idempotency | `mvn verify` (Failsafe, `*IT.java`) | seconds — real PostgreSQL via Testcontainers |
| **Controller** | Status codes, headers, `Location`, problem+json shape, auth | `mvn test` (MockMvc) | fast — no container |
| **Fault injection** | Analytics down, datastore down, readiness transitions | `mvn verify` | seconds |
| **Smoke** | Full reviewer path against the running stack | `./scripts/smoke-test.sh` | seconds |
| **Performance** | Redirect path under bounded local load | `scripts/performance-test/` | minutes, run deliberately |

The split is not decorative. The domain layer imports no framework, so the tests that matter
most — the ones covering the rules that are genuinely tricky — run without a container. That
is what makes running the suite on every save realistic, which is what makes the suite
actually get run.

---

## 2. What each level is *for*

**Unit tests carry the load.** Alias charset, reserved words, URL scheme policy, address-range
rejection including decimal/octal/IPv6-mapped encodings, code shape. These are the cases with
the highest branch density and the lowest cost to test exhaustively.

**Integration tests exist for the things unit tests structurally cannot prove.** Chiefly: that
a unique index actually arbitrates concurrent inserts, and that Flyway's schema and
Hibernate's expectations agree. Mocking a database would test the mock.

**Fault-injection tests exist to stop a refactor from silently undoing a design decision.**
Two matter most:

| Test | Asserts | Why it must be a test and not a convention |
|---|---|---|
| `AnalyticsFailureIT` | Counter write fails → redirect still returns 302 with correct `Location` (AC-5.4) | The fail-open posture in ADR-004 is invisible in the code. A well-meaning refactor that wraps resolution in one transaction would reverse it, and nothing else would notice |
| `DatastoreUnavailableIT` | Database down → 503, never a stale or guessed destination (AC-6.4) | "Never redirect wrongly" is a property, and properties are only real when something enforces them |

**Performance tests exist to measure an accepted trade-off**, not to produce a headline
number. Scenario A (load spread over 1 000 codes) versus scenario B (load on one hot code)
isolates the hot-row contention that ADR-004 knowingly accepted. The *delta* is the result.

---

## 3. Traceability

Every acceptance criterion maps to at least one test; every test maps back to an AC. The
matrix lives in each scenario's `validation.md`.

- An AC with no test **is not done**, regardless of whether the code exists.
- A test mapping to no AC is challenged in review as scope creep — it is either testing
  something nobody asked for, or an AC is missing.

---

## 4. Quality gates

Run in CI. Not waivable by the author acting alone.

| Gate | Mechanism | Threshold |
|---|---|---|
| Build | `mvn verify` | zero errors |
| Format | Spotless (google-java-format) | zero violations |
| Unit | JUnit 5 | 100 % pass |
| Integration | Testcontainers + real PostgreSQL | 100 % pass |
| Coverage — line | JaCoCo | ≥ 85 % |
| Coverage — branch | JaCoCo | ≥ 75 % |
| Smoke | `scripts/smoke-test.sh` | all checks pass |
| Performance | bounded local run | method, machine and sample size reported; **no extrapolated claims** |

### A known hole, stated rather than hidden

`jacoco:check` **skips silently when no execution data exists.** With an empty test suite the
coverage gate therefore passes vacuously — it reports success while checking nothing. That is
tolerable only while there is no production logic to cover.

From T-02 onward the gate becomes load-bearing, and a suite that stops producing exec data
must be treated as a **failure, not a skip**. This is recorded here, in `pom.xml`, and as risk
R-5, because a silently disabled gate is a defect — and the one thing worse than no gate is a
gate that reports green without looking.

---

## 5. What is deliberately not tested

- **Framework behaviour.** Spring's request mapping and Hibernate's SQL generation are not
  this project's to verify.
- **Third-party libraries.** Verified by adoption, not by re-testing.
- **Production capacity.** Cannot be established here (see `tradeoffs-and-risks.md` §4), so
  no test pretends to.
- **Generated OpenAPI content.** Its *existence and accessibility* is asserted (AC-6.6);
  its rendering is springdoc's concern.

---

## 6. Running the suite

```bash
# unit + controller only — fast feedback
mvn test

# everything, including Testcontainers integration tests
mvn verify

# end-to-end against the running stack
docker compose up -d --build
./scripts/smoke-test.sh
```

Integration tests need a Docker daemon. On macOS with Colima rather than Docker Desktop,
Testcontainers cannot find the socket by default:

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
```

That is captured here rather than left as tribal knowledge, because "works on my machine"
is a documentation defect before it is anything else.
