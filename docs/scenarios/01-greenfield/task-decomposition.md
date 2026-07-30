# Scenario 01 — Greenfield · Task Decomposition

Each task carries **intent, constraints, acceptance criteria and technical context** —
the task envelope used to direct the AI assistant. A prompt of the form "write me a URL
shortener" is prohibited; it transfers design authority to the tool.

Tasks are sized so their diff can be reviewed in one sitting.

---

## Dependency graph

```
T-01 scaffold ──┬─▶ T-02 domain: code + policy ──┬─▶ T-05 create use case ──┬─▶ T-08 create API
                │                                 │                          │
                ├─▶ T-03 schema (Flyway) ────────┼─▶ T-06 resolve use case ─┼─▶ T-09 resolve API
                │                                 │                          │
                └─▶ T-04 persistence adapter ────┴─▶ T-07 stats use case ───┴─▶ T-10 stats API
                                                                                    │
                        T-11 error model + correlation ID ◀─────────────────────────┤
                        T-12 API key auth ◀─────────────────────────────────────────┤
                        T-13 fault-injection tests (AC-5.4, AC-6.4) ◀───────────────┘
                        T-14 performance harness
                        T-15 smoke test + docs
```

T-02, T-03 and T-04 are parallelisable once T-01 lands. T-05…T-07 are not — they share the
port definitions T-04 introduces, and racing them produces merge conflicts in exactly the
interfaces that most need a single author.

---

## Tasks

### T-01 — Project scaffold and quality gates ✅ *done*
**Intent** A buildable skeleton with every gate wired before any logic exists, so the first
line of business code is already governed.
**Constraints** No business logic. Gates must actually fail on violation, not warn.
**Acceptance** `mvn verify` green; Spotless rejects misformatted input; coverage gate present.
**Context** `pom.xml`, `SmartLinkApplication`, `application.yml`, `Dockerfile`, `docker-compose.yml`.
**Note** The format gate fired on its first run against AI-generated Javadoc — recorded in the ledger as evidence the gate is load-bearing rather than decorative.

### T-02 — Domain: short code, alias policy, URL policy
**Intent** The rules that are worth testing exhaustively, isolated from Spring and the database.
**Constraints** Zero framework imports in `com.smartlink.domain`. No I/O.
**Acceptance** AC-1.5, AC-3.4, AC-3.5, AC-4.1, AC-4.3, AC-4.4. Property-style tests over the alias charset; explicit cases for decimal, octal and IPv6-mapped address encodings.
**Context** `domain/ShortCode`, `domain/Alias`, `domain/Destination`, `domain/CodeGenerator`.

### T-03 — Schema migration
**Intent** The `links` table per engineering-spec §3.2, additive-first.
**Constraints** Forward-only. No `NOT NULL` that presumes v2's expiry is absent. Unique index on `code`.
**Acceptance** Flyway migrates from empty; Hibernate `ddl-auto: validate` agrees with the migration.
**Context** `src/main/resources/db/migration/V1__create_links.sql`.

### T-04 — Persistence adapter and ports
**Intent** Domain-facing ports plus their JPA implementation.
**Constraints** Domain must not import JPA. Collision handled by unique-violation retry, never check-then-insert.
**Acceptance** NFR-5 forced-collision test passes; concurrent insert of the same code yields exactly one row.
**Context** `application/port/LinkRepository`, `infrastructure/persistence/*`.

### T-05 — Create use case
**Intent** Orchestrate validation, code allocation, idempotency and persistence.
**Constraints** Idempotency is explicit only (A-02) — no implicit dedup by destination.
**Acceptance** AC-1.1, AC-1.3, AC-1.4, AC-3.1, AC-3.2, AC-3.3.
**Context** `application/CreateLinkUseCase`.

### T-06 — Resolve use case
**Intent** Look up a code and return its destination, recording the resolution.
**Constraints** **Counter failure must not fail resolution** (A-05). Datastore failure must surface as 503, never a guess (AC-6.4).
**Acceptance** AC-2.1, AC-2.3, AC-2.4, AC-5.2, AC-5.5.
**Context** `application/ResolveLinkUseCase`.

### T-07 — Stats use case
**Intent** Owner-scoped read of the aggregate counters.
**Constraints** Non-owner must be indistinguishable from non-existent (AC-5.3).
**Acceptance** AC-5.1, AC-5.3.
**Context** `application/ReadStatsUseCase`.

### T-08 / T-09 / T-10 — HTTP surface
**Intent** Translate transport to use cases and back; nothing more.
**Constraints** No business logic in controllers. `Cache-Control: no-store` on redirects (AC-2.2). Resolution mounted at root; management under `/api/v1`.
**Acceptance** AC-1.2, AC-2.2, AC-2.5, AC-6.6.
**Context** `api/LinkController`, `api/RedirectController`, `api/StatsController`.

### T-11 — Error model and correlation ID
**Intent** RFC 9457 `problem+json` for every error path; correlation ID on every response.
**Constraints** Never echo raw user input unescaped (AC-4.5). Never leak stack traces.
**Acceptance** AC-4.5, AC-6.3, AC-6.5 — including a test asserting no destination URL and no API key appears in logs at INFO.
**Context** `api/ProblemDetailAdvice`, `api/CorrelationIdFilter`.

### T-12 — API key authentication
**Intent** Authenticate creation and stats; leave resolution anonymous (A-08).
**Constraints** Constant-time comparison. Keys from environment only, never committed.
**Acceptance** AC-1.6, AC-2.5.
**Context** `infrastructure/security/*`.

### T-13 — Fault-injection tests
**Intent** Prove the two failure postures structurally rather than by convention.
**Constraints** Must fail if a future refactor couples the redirect to the counter.
**Acceptance** AC-5.4 (analytics down → redirect still 302), AC-6.4 (datastore down → 503, never a wrong destination).
**Context** `src/test/java/.../resilience/*IT.java`.

### T-14 — Performance harness
**Intent** Measure the A-05 trade-off instead of asserting it.
**Constraints** Report method, machine, sample size. **No extrapolated scale claims.**
**Acceptance** Scenario A (spread) vs scenario B (single hot code) both measured; results committed with environment stated.
**Context** `scripts/performance-test/`.

### T-15 — Smoke test and documentation
**Intent** A reviewer can go clean-clone → running → verified in one command.
**Acceptance** `scripts/smoke-test.sh` green against compose; README demo path accurate.

---

## Gate C — approval required

- [ ] Decomposition is complete — every AC in `engineering-spec.md` is claimed by some task.
- [ ] Ordering is correct and the parallelism claims hold.
- [ ] Each task is reviewable in one sitting.

**Approved by:** _________________  **Date:** __________
