# Final Engineering Summary

**Scenario 01 (Greenfield) complete.** Scenarios 02 (brownfield: expiration) and 03 (ambiguous:
reliability) are specified but not implemented — see §8.

```
mvn verify          232 tests, 0 failures        line 91.8 %   branch 77.7 %
smoke-test.sh        25 checks, 0 failures       against docker compose
trivy                 0 HIGH/CRITICAL            deps · secrets · image
spotbugs              0 findings at HIGH
clean-clone           verified end to end        see §5
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

Nine ADRs in [`decisions.md`](decisions.md). The four that shaped the most code:

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
| Production code | `src/main/java` | 2 292 lines, 34 classes |
| Test suite | `src/test/java` | 3 503 lines, 232 tests |
| Generated API contract | `/v3/api-docs`, `/swagger-ui.html` | ✅ generated, never hand-maintained |
| Schema | `V1__create_short_link.sql` | ✅ Flyway, forward-only |
| Smoke test | `scripts/smoke-test.sh` | ✅ 25/25 |
| Performance harness + results | `scripts/performance-test/` | ✅ 3 runs, ratio analysis |
| Requirements · spec · tasks · validation | `docs/scenarios/01-greenfield/` | ✅ complete |
| Architecture | `docs/architecture-overview.md` | ✅ written from the built system |
| ADRs | `docs/decisions.md` | ✅ 9 |
| AI traceability ledger | `docs/ai-assisted-engineering.md` | ✅ 72 entries — 13 generated, 34 edited, **25 rejected** |

---

## 4. Validation outcomes

| Gate | Result |
|---|---|
| Unit + controller tests | ✅ 166 |
| Integration tests (real PostgreSQL) | ✅ 66 |
| Coverage — line / branch | ✅ 91.8 % / 77.7 % |
| Architecture rule (ArchUnit) | ✅ enforced, not documented |
| Static analysis (SpotBugs, HIGH) | ✅ 0 |
| Dependency vulnerabilities | ✅ 0 HIGH/CRITICAL (was 22) |
| Secrets | ✅ 0 |
| Container image | ✅ 0 HIGH/CRITICAL (was 4) |
| Smoke, against compose | ✅ 25/25 |
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

---

## 5. Reviewer path, verified

Rehearsed from a fresh `git clone` into an empty directory:

```bash
git clone <repo> && cd smartlink-ai-engineering
cp .env.example .env
docker compose up --build -d
./scripts/smoke-test.sh          # 25/25
mvn verify                       # 232/232
```

| Check | Result |
|---|---|
| Files a reviewer receives | 100 |
| Secrets, build output, IDE files committed | none |
| `mvn verify` from scratch | ✅ 232/232 |
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

1. **Scenarios 02 and 03 are not implemented.** Requirements are written; expiration and the reliability normalisation are not built. This is the largest gap between what the repository promises and what it delivers.
2. **No SLO is proven.** Identical code measured p95 55.7 ms and 507 ms in different runs, driven by unrelated desktop load. Only the contention *ratio* survived, and only that is reported as a finding.
3. **Single instance, single AZ.** Horizontal scalability is a property of the design — no node-local state, asserted by ArchUnit — not something demonstrated.
4. **No caching, replicas, async analytics, circuit breaking or rate limiting.** Each is real work; none can be *validated* here, and shipping unvalidated reliability machinery improves the appearance of reliability rather than reliability.
5. **Anonymous by design.** No identity, so no per-creator quota has a subject.
6. **`apk upgrade` makes the image non-reproducible.** Deliberate: a reproducible image full of known-vulnerable libraries is reproducibly vulnerable.

---

## 9. What I would do next

In priority order, which is itself a judgment worth stating:

1. **Scenario 02 (expiration)** — the brownfield exercise is where impact analysis, migration safety and backward compatibility actually get demonstrated, and it is the biggest missing piece.
2. **Scenario 03 (reliability)** — normalise the ambiguous requirement, and be explicit about what was deliberately excluded.
3. **Abuse controls before any public exposure.** A public shortener without them is a phishing platform with extra steps.
4. **Key issuance and rotation** — the largest gap between this and something deployable.
5. **Measure before optimising.** The cache and async analytics remain deferred on the grounds that no measurement justifies them. That claim is now testable rather than merely asserted, and it should be tested rather than trusted.

---

## 10. Sign-off

The engineer of record owns every artifact in this repository regardless of which keystrokes were
typed by a tool. Every line was read before commit; every material AI contribution is classified in
[`ai-assisted-engineering.md`](ai-assisted-engineering.md), including **25 rejections** with their
reasoning — among them a version verified against a stale search index, a transaction annotation
that could not work, a mocked failure that would have proven nothing, and a test client that
silently followed the redirects it was meant to be asserting on.

A ledger with no rejections would be evidence that review was not happening.

**Engineer of record:** Srinivas Makkena
**Signed:** _________________  **Date:** __________
