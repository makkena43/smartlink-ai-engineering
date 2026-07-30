# Task Plan

Programme-level view across the three scenarios. Per-scenario decomposition, with task
envelopes, lives in each `scenarios/*/task-decomposition.md`.

---

## Workflow

Every scenario runs the same loop, and each arrow is a human decision:

```
   ┌──────────┐  Gate A  ┌──────────┐  Gate B  ┌──────────┐  Gate C  ┌───────────────┐  Gate D
   │ REQUIRE- │ ───────▶ │ ENGINEER-│ ───────▶ │  TASK    │ ───────▶ │  IMPLEMENT    │ ──────▶ merge
   │  MENTS   │          │ ING SPEC │          │  DECOMP  │          │  + VALIDATE   │
   └──────────┘          └──────────┘          └──────────┘          └───────────────┘
   what / why            how                   ordered, sized        evidence
   ambiguity register    design + ACs          dependency graph      traceability matrix
```

| Gate | Approves |
|---|---|
| **A** | The problem is correctly understood; ambiguities are registered, not absorbed |
| **B** | Architecture and trade-offs are sound |
| **C** | Decomposition is complete, ordered and testable |
| **D** | Code is read, gates are green, risks documented |

---

## Programme status

| # | Scenario | Requirement | Stage | State |
|---|---|---|---|---|
| 01 | Greenfield | *"Build a URL shortener with redirect and basic analytics."* | Gate D | **complete** |
| 02 | Brownfield | *"Add expiration so campaigns can stop redirecting after a defined time."* | Gate D | **complete** |
| 03 | Ambiguous | *"Improve reliability."* | Gate D | **complete** |

Scenario 01 tasks and its Gate D evidence are complete. Scenario-specific artifacts remain the
source of truth for detailed execution and validation.

---

## Why this order

The three are sequenced, not parallel, and the sequence is the point:

**01 must exist before 02 can be brownfield at all.** A brownfield scenario against a
codebase written the same afternoon by the same person is not brownfield — it is greenfield
with extra steps. The value of 02 is that it lands against code that is already committed,
already tested, and already documented, so the impact analysis has something real to
analyse and the backward-compatibility question has genuine teeth.

**02 must exist before 03 is honest.** "Improve reliability" is only ambiguous if there is a
running system whose reliability is in question. Answering it against a system that does not
yet exist would make the disambiguation hypothetical, and the whole exercise is about
disambiguating something real.

---

## Scenario 02 — Brownfield, delivered shape

Expiration was chosen over larger candidates deliberately. It is compact but genuinely
cross-cutting: it touches persistence, the creation API, redirect logic, backward
compatibility, migration, documentation and tests — **demonstrating codebase reasoning
without creating a second product.** A bigger feature would have produced more code and less
evidence of judgment.

Delivered impact, evidenced in `02-brownfield/impact-analysis.md` and its validation record:

| Area | Expected change |
|---|---|
| Schema | additive nullable `expires_at`; forward-only migration |
| Domain | expiry rule as a pure function of link + clock |
| Create API | optional field; absent means never expires |
| Resolve | expired → `410 Gone`, distinct from `404` |
| Compatibility | every existing link and every existing client keeps working untouched |
| Docs | ADR, API overview, architecture evolution log |

The `410`-versus-`404` question is the interesting one, and it is a public contract decision:
`404` means "never existed", `410` means "existed, deliberately ended". That distinction is
what lets an operator tell a typo from a finished campaign without a database query.

---

## Scenario 03 — Ambiguous, delivered shape

*"Improve reliability"* is not a requirement; it is a direction. The engineering work is
normalising it into something with an acceptance criterion, and then being explicit about
what was deliberately left out.

Delivered normalisation — a **bounded** interpretation:

- liveness and readiness that reflect real dependency state;
- safe failure on the resolve path: `503`, never a guessed destination;
- connection and request timeouts, so one slow dependency cannot exhaust the thread pool;
- documented SLIs and SLOs, clearly marked as design targets rather than proven properties;
- an operational runbook.

Explicitly deferred, with reasons: circuit breaking, read-through caching, multi-AZ
deployment, multi-region recovery. Each is genuine reliability work; none can be
*demonstrated* by a laptop prototype, and claiming them would be the exact failure this
project is meant to avoid.

---

## Cross-cutting, closed at the end

| Deliverable | Depends on |
|---|---|
| `final-engineering-summary.md` | all three scenarios |
| Performance results | 01 T-14 |
| Clean-clone rehearsal | all |
| AI traceability ledger | continuous — written as work lands, never reconstructed afterwards |
