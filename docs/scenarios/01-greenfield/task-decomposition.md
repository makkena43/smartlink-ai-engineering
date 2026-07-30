# Greenfield Task Decomposition

Every task carries **intent, constraints, acceptance criteria and technical context** — the
envelope used to dispatch work. Open-ended instructions are not used, because they transfer
design authority away from the engineer and produce output with no stated criterion to check
it against.

Tasks are sized so their diff can be reviewed in one sitting.

- **Requirements:** [`requirements.md`](requirements.md)
- **Design:** [`engineering-spec.md`](engineering-spec.md)
- **Status:** Gate C — awaiting approval

---

## Dependency graph

```
T-01 scaffold ✅ ──┬─▶ T-02 domain: code + destination policy ──┐
                   ├─▶ T-03 schema (Flyway) ───────────────────┤
                   └─▶ T-04 persistence adapter + ports ───────┤
                                                               │
        ┌──────────────────────────────────────────────────────┘
        ├─▶ T-05 create use case   ──▶ T-08 create API
        ├─▶ T-06 resolve use case  ──▶ T-09 resolve API
        └─▶ T-07 stats use case    ──▶ T-10 stats API
                                          │
              T-11 problem+json + correlation ID ◀┤
              T-12 retry policy + timeouts        ◀┤
              T-13 fault-injection suite          ◀┘
              T-14 performance harness (A / B)
              T-15 smoke test + docs
```

T-02, T-03 and T-04 parallelise once T-01 lands. T-05…T-07 do not — they share the port
definitions introduced by T-04, and racing them produces conflicts in exactly the interfaces
that most need a single author.

---

## T-01 — Scaffold and quality gates ✅ *complete*

**Intent** A buildable skeleton with every gate wired before any logic exists, so the first
line of business code is already governed.
**Constraints** No business logic. Gates must fail on violation, not warn.
**Acceptance** `mvn verify` green · Spotless rejects misformatted input · coverage gate present.
**Context** `pom.xml`, `SmartLinkApplication`, `application.yml`, `Dockerfile`, `docker-compose.yml`.
**Outcome** The format gate rejected the first generated Javadoc before a human saw it —
recorded in the ledger as evidence the gate is load-bearing rather than decorative.

---

## T-02 — Domain: short code and destination policy

**Intent** The rules with the highest branch density in the system, isolated from Spring and
from the database so they can be tested exhaustively and fast.

**Constraints**
- **Zero framework imports** in `com.smartlink.domain`. No Spring, no JPA, no I/O.
- DNS resolution is reached through a domain-owned port, so the policy stays unit-testable
  with a stubbed resolver — the rule must be provable without a network.
- Normalise **before** evaluating (spec §9.1). A validator that decides before normalising is
  checking a string the rest of the system never sees.
- Fail closed: unparseable, unresolvable, or ambiguous input is rejected (NFR-16).
- Store verbatim — normalisation is for evaluation only, never rewrites the stored value.

**Acceptance** GF-14 · GF-15 · GF-16 · GF-17 · GF-18 (control characters) · GF-19 · NFR-15 ·
NFR-16, plus code format per spec D-02.
- Scheme allowlist: `http`/`https` accepted; `javascript:`, `data:`, `file:`, `vbscript:`, `blob:` rejected.
- **Table-driven** rejection across every notation in spec §9.1.3 — decimal, octal, hex, mixed, IPv6-mapped, credential-embedded — each rejected identically to its plain form.
- Every resolved address checked, not merely the first.
- Length bounds enforced before parsing.
- CR / LF / NUL rejected.
- Generated codes: 7 chars base62, not derivable from an adjacent code.

**Context** `domain/Destination`, `domain/ShortCode`, `domain/CodeGenerator`,
`domain/port/HostResolver`. Spec §9.1, §4.

**Review focus** The encoding table. Evasion bugs are found by enumerating notations, not by
reasoning about them — the failure is always an encoding nobody considered, so the table must
be trivial to extend.

---

## T-03 — Schema migration

**Intent** The `links` table per spec §7, additive-first.
**Constraints** Forward-only. Unique index on `code`. No `NOT NULL` that presumes Scenario
02's expiry column is absent. No column capable of holding personal data (NFR-13).
**Acceptance** NFR-01 · GF-05. Flyway migrates from empty; Hibernate `ddl-auto: validate`
agrees with the migration.
**Context** `src/main/resources/db/migration/V1__create_links.sql`.

---

## T-04 — Persistence adapter and ports

**Intent** Domain-facing ports and their JPA implementation.
**Constraints** Domain must not import JPA. Collision resolved by unique-violation retry,
**never** check-then-insert — the latter is a race, not a slower correct answer. Parameterised
queries only (NFR-14).
**Acceptance** GF-05 · GF-06 · NFR-01. Forced-collision test recovers; concurrent inserts of
one code yield exactly one row.
**Context** `application/port/LinkRepository`, `infrastructure/persistence/*`.

---

## T-05 — Create use case

**Intent** Orchestrate validation, code allocation and persistence.
**Constraints** **No lookup by destination anywhere in this path** — that absence is what
satisfies GF-04. Collision allowance (3 attempts) kept separate from the transient-failure
allowance (1 retry), so an outage cannot consume the collision budget.
**Acceptance** GF-01 · GF-02 · GF-04 · GF-06 · NFR-03. Exhausted collisions → 503, not 500.
**Context** `application/CreateLinkUseCase`. Spec §5.1, §8.3.

---

## T-06 — Resolve use case

**Intent** Look up a code, record the resolution, return the destination.
**Constraints** **Counter failure must not fail the redirect** (spec D-06). Datastore failure
surfaces as 503, never a guess (NFR-02). At most one retry, jittered (spec §8.3).
**Acceptance** GF-07 · GF-09 · GF-19 · NFR-02 · NFR-03.
**Context** `application/ResolveLinkUseCase`. Spec §5.2.

**Review focus** The fail-open branch is invisible in the code — it looks like an ordinary
try/catch. T-13 is what keeps it true.

---

## T-07 — Stats use case

**Intent** Read the aggregate counter.
**Constraints** No authentication (GF-12). No personal data in the response (NFR-13).
**Acceptance** GF-11 · GF-12. Unknown code → 404.
**Context** `application/ReadStatsUseCase`.

---

## T-08 / T-09 / T-10 — HTTP surface

**Intent** Translate transport to use cases and back. Nothing more.
**Constraints** No business logic in controllers. `302` + `Cache-Control: no-store` on
redirect. Resolution at root, management under `/api/v1`, **route matching takes precedence
over code resolution**. `Location` emitted through the framework's header API, never by
string concatenation (GF-18, NFR-14).
**Acceptance** GF-02 · GF-07 · GF-08 · GF-09 · GF-11 · NFR-05.
**Context** `api/LinkController`, `api/RedirectController`, `api/StatsController`. Spec §6.

---

## T-11 — Error model and correlation ID

**Intent** RFC 9457 `problem+json` on every error path; a correlation ID on every response.
**Constraints** Never emit a stack trace, SQL state, database message, internal hostname or
connection detail. Never echo raw input unescaped. Name the violated rule.
**Acceptance** NFR-04 · GF-18 · acceptance criterion 14, including a test asserting no
destination URL appears in logs at INFO.
**Context** `api/ProblemDetailAdvice`, `api/CorrelationIdFilter`. Spec §9.2.

---

## T-12 — Retry policy and timeouts

**Intent** The asymmetric policy in spec §8.3, as a reusable, testable component.
**Constraints** Resolve path: **at most one** retry, jittered. Never retry validation errors,
not-found, or constraint violations. Create path: 3 collision attempts, 1 transient retry.
`503` for dependency unavailability; `500` reserved for genuinely unexpected failures.
**Acceptance** NFR-03 · NFR-02.
**Context** `infrastructure/resilience/*`, `application.yml`.

**Review focus** The dangerous bug here is **over**-retrying, and it stays invisible until an
outage — at which point retries amplify load against a failing dependency and delay the 503
the client needs in order to fail fast. Assert the *upper* bound, not just that a retry happens.

---

## T-13 — Fault-injection suite

**Intent** Prove the failure postures structurally rather than by convention.
**Constraints** Must fail the build if a future refactor recouples the redirect to the counter.
**Acceptance**
- Analytics write fails → redirect still `302` with correct `Location`.
- Datastore unavailable → `503`, never stale or guessed.
- Readiness reflects dependency state; liveness does not.
- Retry exhaustion → `503`, bounded attempt count asserted.

**Context** `src/test/java/.../resilience/*IT.java`. Spec §11.2.

---

## T-14 — Performance harness

**Intent** Measure the accepted trade-off instead of asserting it.
**Constraints** Report machine, cores, JVM, container runtime, co-location and sample size.
**No extrapolated scale claims.**
**Acceptance** Scenario A (spread) and scenario B (single hot code) both measured; the A/B
delta quantifies the row contention D-06 accepted, converting NFR-08 from a claim into a number.
**Context** `scripts/performance-test/`. Spec §10.3.

---

## T-15 — Smoke test and documentation

**Intent** Clean clone → running → verified, in one documented command.
**Acceptance** NFR-12 · acceptance criterion 8. `scripts/smoke-test.sh` green against compose;
README demo path accurate; `architecture-overview.md` promoted from placeholder to the final
artifact, written from the system that actually got built.

---

## Coverage check

Every requirement is claimed by at least one task. A requirement claimed by none is unbuilt;
a task claiming none is scope creep.

| Requirement | Task |
|---|---|
| GF-01, GF-02 | T-05, T-08 |
| GF-03 | T-08 (absence of auth) |
| GF-04 | T-05 |
| GF-05, GF-06 | T-03, T-04 |
| GF-07, GF-08 | T-06, T-09 |
| GF-09 | T-06, T-09 |
| GF-10, GF-14…GF-17 | T-02 |
| GF-11, GF-12 | T-07, T-10 |
| GF-13 | T-13 |
| GF-18 | T-02, T-09, T-11 |
| GF-19 | T-02, T-06 |
| NFR-01 | T-03, T-04 |
| NFR-02 | T-06, T-12, T-13 |
| NFR-03 | T-12, T-13 |
| NFR-04 | T-11 |
| NFR-05 | T-09 |
| NFR-06 | T-01 (stateless by construction) |
| NFR-07, NFR-08 | T-14 (measured), spec §8.5 (documented) |
| NFR-09 | spec §8.6 — documented, not implemented per requirements §6 |
| NFR-10 | spec §10, T-14 |
| NFR-11 | T-02…T-13 |
| NFR-12 | T-15 |
| NFR-13 | T-03, T-07 |
| NFR-14 | T-04, T-09, T-11 |
| NFR-15, NFR-16 | T-02 |

---

## Gate C — approval required

- [ ] Every requirement is claimed by a task, and the coverage table above is accurate.
- [ ] Ordering is correct and the parallelism claims hold.
- [ ] Each task is reviewable in one sitting.

**Approved by:** _________________  **Date:** __________
