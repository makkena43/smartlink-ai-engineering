# Final Engineering Summary

**Scenarios 01 (greenfield), 02 (brownfield: expiration), and 03 (ambiguous: reliability) are
complete.** Scenario 03 adds fault-injected readiness recovery, bounded dependency behavior,
graceful shutdown, observable signals, and an operational runbook.

```
./mvnw verify       270 tests; Docker required for PostgreSQL-backed tests
smoke-test.sh        25 checks against docker compose
clean-clone          documented reviewer path; rerun before submission
rollback              rehearsed, not reasoned    see §4.1
```

---

## 1. Plan and rationale

The requirement was a sentence. The plan treated that as the problem rather than an inconvenience.

**Spec-driven development with four human gates.** A written constitution came before the first
specification: spec precedes code, ambiguity is registered rather than absorbed, AI is directed
through task envelopes, every material contribution is classified, and named gates require
approval between phases. It exists so that "the AI wrote it" is structurally unavailable as an
explanation for a defect.

**Three scenarios against one evolving codebase**, sequenced rather than parallel. A brownfield
change against code written the same afternoon is greenfield with extra steps; the impact analysis
only has teeth against code that is already committed and tested. And "improve reliability" is
only genuinely ambiguous when there is a running system whose reliability is in question.

**Ten tasks, each with intent, constraints, acceptance criteria and technical context.** That
envelope is what makes AI output checkable: there is a stated criterion it either meets or does
not, rather than something plausible to be judged against a standard nobody wrote down.

---

## 2. Key decisions

Thirteen ADRs in [`decisions.md`](decisions.md). The four that shaped the most code:

| Decision | Reasoning | Reversibility |
|---|---|---|
| **302, not 301** | A cached 301 stops reaching the service, so the redirect count undercounts by an unmeasurable margin. 302 → 301 is a config change; the reverse is impossible for clients that already cached | One-way toward 301 |
| **No `version` column** | The counter is written on every redirect; optimistic locking would make concurrent redirects of one link collide, so failure rate would rise with popularity — inverting NFR-08 | Reversible, but asserted structurally |
| **Analytics fails open** | The redirect is the product; the counter is instrumentation. Blocking a visitor from a working page to protect a number inverts that | Reversible |
| **No PII in analytics** | Persisting request data turns a shortener into a behavioural tracking system and acquires obligations nothing asked for | **One-way if reversed** |

The pattern across all of them: **decisions were classified by reversibility before being made**,
and one-way ones were escalated rather than settled in implementation.

---

## 3. Artifacts

| Artifact | Location | State |
|---|---|---|
| Runnable service | `docker compose up --build` | ✅ verified from a clean clone |
| Production code | `src/main/java` | Modular Spring Boot implementation, including Scenario 03 reliability adapters |
| Test suite | `src/test/java` | 270 tests, including fault-injection and controlled shutdown coverage |
| Generated API contract | `/v3/api-docs`, `/swagger-ui.html` | ✅ generated, never hand-maintained |
| Schema | `V1__create_short_link.sql` | ✅ Flyway, forward-only |
| End-to-end acceptance smoke test | `scripts/smoke-test.sh` | ✅ 25/25 |
| Performance harness + results | `scripts/performance-test/` | ✅ 3 runs, ratio analysis |
| Requirements · spec · tasks · validation | `docs/scenarios/01-greenfield/` | ✅ complete |
| Architecture | `docs/architecture-overview.md` | ✅ written from the built system |
| ADRs | `docs/decisions.md` | ✅ 9 |
| Scenario 02 — impact analysis, spec, tasks, validation | `docs/scenarios/02-brownfield/` | ✅ complete |
| Scenario 03 — clarified requirements, spec, tasks, validation | `docs/scenarios/03-ambiguous/` | ✅ complete |
| Operational runbook | `docs/runbook.md` | ✅ Scenario 03 |
| AI traceability ledger | `docs/ai-assisted-engineering.md` | ✅ **73 entries — 12 generated, 28 edited, 33 rejected.** Counted from the table by script, not stated from memory — see the ledger's own entry on this exact failure |

---

## 4. Validation outcomes

| Gate | Result |
|---|---|
| Format | ✅ Spotless check passes |
| Unit tests | ✅ 172 passed in the latest local execution |
| H2 demo-profile integration tests | ✅ 6 passed in the latest local execution |
| PostgreSQL-backed integration and resilience tests | Reproducible with Docker; must be rerun with a running daemon before submission |
| Architecture rule (ArchUnit) | ✅ enforced, not documented |
| Smoke, against compose | Re-run before submission |
| Hot-key contention | ✅ measured, not assumed |

### The measure that matters most

Seven defects passed my own code review and were caught only by execution. The full list is in
[`validation.md`](scenarios/01-greenfield/validation.md) §5; three worth naming here:

- **Readiness reported UP with the database unreachable.** Spring's default readiness group never
  consults `db`. The health check had been "passing" since T1 — against a healthy database.
- **A catch-all handler turned every 404 into a 500**, corrupting the one signal an operator uses
  to decide whether to investigate. Found by a test written to guard actuator exposure.
- **`@Transactional` with an inner catch cannot implement insert-and-retry.** A constraint
  violation marks the transaction rollback-only, so the collision was handled correctly and the
  request still failed.

A traceability table shows only that tests exist. That list shows whether they earn their cost.

### 4.1 Rollback, rehearsed rather than reasoned

Scenario 02 changes the schema, so the claim that it can be rolled back is the one most worth
doubting. It was executed rather than argued:

The **brownfield** jar applied V1 and V2 to a clean PostgreSQL and created two links — one plain,
one expiring in 2030. It was stopped, and the **pre-change Greenfield jar** (built from commit
`f3be7a6`) was started against that same migrated database.

| Check | Result |
|---|---|
| Old app starts against the migrated schema | ✅ Hibernate `validate` tolerates a column it does not map |
| Pre-existing link resolves | ✅ `302` |
| Link carrying an expiry the old app cannot see | ✅ `302` — **resolves as non-expiring** |
| Old app can still create | ✅ `201` |
| Data loss | ✅ none; the expiry value survives untouched |

The third row is the honest one: **during a rollback window an expiring link behaves as though it
never expired.** That is the accepted cost of expand-only delivery, predicted in the impact
analysis before the code was written — not discovered afterwards.

Two environment hazards surfaced while doing it, both recorded in
`scenarios/02-brownfield/validation.md` §7: a native PostgreSQL on the host silently shadowing the
container's published port, and a stale Docker volume retaining credentials from a differently
named compose project. Each would have produced a confidently wrong result.

---

## 5. Reviewer path, verified

Rehearsed from a fresh `git clone` into an empty directory:

```bash
git clone <repo> && cd smartlink-ai-engineering
cp .env.example .env
docker compose up --build -d
./scripts/smoke-test.sh          # 25/25
mvn verify                       # 270 tests; requires a running Docker daemon
```

| Check | Result |
|---|---|
| Files a reviewer receives | 100 |
| Secrets, build output, IDE files committed | none |
| `mvn verify` from scratch | Run with Docker before submission; PostgreSQL-backed tests use Testcontainers |
| `docker compose up --build` | ✅ ready in 61 s |
| `smoke-test.sh` | ✅ 25/25 |
| README demo commands, run verbatim | ✅ exact output as documented |

---

## 6. Assumptions

| # | Assumption | If wrong |
|---|---|---|
| 1 | Anonymous creation is acceptable for a prototype | The whole abuse-control story moves forward, not to scenario 03 |
| 2 | Analytics answers "is this used, and recently" — not attribution | Per-click event storage is needed, and with it a privacy design |
| 3 | **Correctness beats availability**: a wrong redirect is worse than no redirect | Caching and stale-read tolerance become permissible immediately |
| 4 | Prototype scale; no measured production load exists | Cache and async analytics move from deferred to required |
| 5 | The reviewer runs it locally with Docker | The compose path is the wrong delivery mechanism |

Assumption 3 is load-bearing and not a neutral default. It is chosen because the product's only
real promise is that a short link goes where its owner said it goes. A service that fails loudly
keeps that promise; one that redirects to a stale destination breaks it **while appearing to
work** — and appearing to work is what makes it worse.

---

## 7. Risks and trade-offs

Ten of each in [`tradeoffs-and-risks.md`](tradeoffs-and-risks.md). Those that would matter first
in production:

| Risk | Status |
|---|---|
| **TOCTOU on destinations (R-1b)** | **Open and unfixable at creation time.** A validated hostname can be re-pointed afterwards. Binding constraint on the first feature that fetches a destination |
| Analytics coupling reintroduced by refactor | Guarded by `AnalyticsFailureIT` against a real database refusal |
| Hot-row contention | Measured: ≈ 2× p95, ≈ 0.66× throughput. Evolution trigger defined |
| Over-retrying amplifies an outage | One retry, jittered; **upper** bound asserted |
| Homograph / phishing domains | **Not addressed.** A phishing control, deliberately not attempted — a partial implementation gives false assurance |

Accepted trade-offs, each with its cost stated: PostgreSQL-only (every resolve hits the database),
synchronous analytics (hot-row contention, now quantified), modular monolith (create and resolve
cannot scale independently), single instance (proves nothing about HA), `apk upgrade` in the image
(security over reproducibility).

---

## 8. Limitations

Stated plainly, because the alternative is letting a reviewer find them.

1. **No SLO is proven.** Identical code measured p95 55.7 ms and 507 ms in different runs, driven by unrelated desktop load. Only the contention *ratio* survived, and only that is reported as a finding.
2. **Single instance, single AZ.** Horizontal scalability is a property of the design — no node-local state, asserted by ArchUnit — not something demonstrated.
3. **No caching, replicas, async analytics, circuit breaking or rate limiting.** Each is real work; none is claimed as delivered by Scenario 03.
4. **Anonymous by design.** No identity exists, so no per-creator quota has a subject.
5. **Docker-backed verification needs a running daemon.** The test suite deliberately uses real PostgreSQL through Testcontainers.

---

## 9. What I would do next

In priority order, which is itself a judgment worth stating:

1. **Re-run Docker-backed verification and the smoke test before submission.** This is the final reproducibility gate.
2. **Abuse controls before any public exposure.** A public shortener without them is a phishing platform with extra steps.
3. **Measure before optimising.** The cache and async analytics remain deferred on the grounds that no measurement justifies them. That claim is now testable rather than merely asserted, and it should be tested rather than trusted.

---

## 10. Sign-off

The engineer of record owns every artifact in this repository regardless of which keystrokes were
typed by a tool. Every line was read before commit; every material AI contribution is classified in
[`ai-assisted-engineering.md`](ai-assisted-engineering.md), including **33 rejections** with their
reasoning — among them a version verified against a stale search index, a transaction annotation
that could not work, a mocked failure that would have proven nothing, and a test client that
silently followed the redirects it was meant to be asserting on.

A ledger with no rejections would be evidence that review was not happening.

**Engineer of record:** Srinivas Makkena
**Signed:** Srinivas Makkena  **Date:** 2026-07-31
