# SmartLink Engineering Constitution

**Status:** Ratified · **Version:** 1.0 · **Owner:** Srinivas Makkena (engineer of record)

This document governs how work is done in this repository. It is written before any
specification and before any code, and every later artifact — spec, plan, task, commit,
test, document — is subordinate to it.

It exists because the assignment's central claim is not "AI can write a URL shortener."
It is that **an engineer can direct AI through a disciplined process and remain
accountable for the result.** A process that is not written down cannot be audited, and a
process that cannot be audited cannot be defended in review. So it is written down first.

---

## Article 0 — Terms

| Term | Meaning in this repo |
|---|---|
| **Engineer of record** | The single human accountable for correctness, maintainability and production readiness. Named above. Not transferable to a tool. |
| **AI assistant** | Claude (Opus 5) via Claude Code, used inside tasks. Never an autonomous orchestrator. |
| **Artifact** | Any committed file. Specs, plans, tasks, ADRs, code, tests, docs and ledgers are all artifacts and all reviewable. |
| **Gate** | A named checkpoint that requires explicit human approval before the next phase may begin. |
| **Scenario** | A self-contained unit of work exercised end-to-end through the workflow: greenfield, brownfield, or ambiguous. |

---

## Article I — Specification precedes implementation

**I.1** No production code is written before a `spec.md` for that scenario exists, is
committed, and is approved at Gate A.

**I.2** A spec states **what** and **why**. It must not contain class names, framework
choices, table layouts, or library selections. Those belong in the plan. This separation
is enforced at review: a spec that names a framework is defective and is sent back.

**I.3** Every spec carries testable acceptance criteria. "The service should be fast" is
not an acceptance criterion. "p99 redirect latency ≤ 50 ms at 500 rps on the reference
hardware, measured by the k6 script in `perf/`" is.

**I.4** A spec that cannot be satisfied is amended by a new revision, not by silently
drifting the implementation away from it. Drift between spec and code is a defect of the
same severity as a failing test.

---

## Article II — Ambiguity is surfaced, never absorbed

**II.1** Requirements arrive incomplete. That is normal and is not a reason to stop.
The failure mode this article prevents is the *silent* resolution of ambiguity — where an
engineer or an AI quietly picks an interpretation and the choice becomes invisible in the
diff, discoverable only in production.

**II.2** Every spec contains an **Ambiguity Register**. Each entry records:

- the ambiguous statement, quoted from the source requirement;
- why more than one reading is defensible;
- the readings considered;
- the reading chosen, **with its rationale**;
- the blast radius if the choice is wrong;
- whether the decision is *reversible*, *costly to reverse*, or *one-way*.

**II.3** One-way decisions (data model semantics, public API contracts, anything a
client can come to depend on) are escalated to the engineer of record before
implementation. They are never resolved by the AI assistant alone.

**II.4** Where an assumption is load-bearing and cheap to guard, it is encoded as an
executable assertion — a test, a validation rule, or a startup check — so that a wrong
assumption fails loudly rather than quietly.

---

## Article III — Decomposition is explicit and ordered

**III.1** Work is decomposed into tasks in a `tasks.md`. Every task declares:

- **Intent** — the outcome, not the keystrokes.
- **Constraints** — what it may not do or break.
- **Acceptance criteria** — how completion is proven, mechanically.
- **Technical context** — the files, contracts and invariants it touches.
- **Dependencies** — the task IDs that must land first.

**III.2** A task is sized so that its diff can be reviewed in a single sitting. A task
whose diff cannot be held in one head is split before it is started, not after.

**III.3** Task ordering is a dependency graph, not a wish list. Tasks that can run in
parallel are marked as such; tasks that cannot are sequenced with the reason stated.

**III.4** Vertical slices beat horizontal layers. A task delivers a thin working path
through the system in preference to a complete layer that nothing yet calls.

---

## Article IV — AI is directed, not consulted

**IV.1** The AI assistant is invoked with a **task envelope** — intent, constraints,
acceptance criteria, technical context — taken from `tasks.md`. Prompts of the form
"write me a URL shortener" are prohibited: they transfer design authority to the tool.

**IV.2** Output is refined iteratively against the acceptance criteria. The first
response is treated as a draft, never as a deliverable.

**IV.3** The engineer reads every line before it is committed. Code that is committed
unread is, by the definition in Article VIII, unowned.

**IV.4** **Rejection is a first-class outcome and must be recorded.** A ledger that
contains no rejections is evidence that review was not happening. Rejections with their
rationale are the strongest available proof of engineering judgment, and are therefore
recorded with more care than acceptances.

**IV.5** AI is used across the full lifecycle — implementation, debugging, refactoring,
test generation, documentation, and review preparation — but its role in each is
*assistive within a task the engineer has already framed.*

---

## Article V — Traceability

**V.1** Every scenario maintains a traceability ledger at
`docs/ai-engineering/ledger-<scenario>.md`, classifying each material AI contribution as:

| Class | Meaning |
|---|---|
| `GENERATED` | Accepted substantially as produced. |
| `EDITED` | Accepted after human modification. The modification and its reason are recorded. |
| `REJECTED` | Discarded. The reason and the alternative taken are recorded. |

**V.2** Requirements are traceable forward and backward: every acceptance criterion maps
to at least one test, and every non-trivial module maps back to a requirement ID. A module
that traces to no requirement is scope creep and is challenged in review.

**V.3** Commits reference the task ID they discharge. History is a record of reasoning,
not just of bytes changed.

---

## Article VI — Quality gates

No change merges unless all of the following pass. These run in CI and are not
waivable by the author acting alone.

| Gate | Mechanism | Threshold |
|---|---|---|
| Build | `./mvnw verify` | Zero errors |
| Format | Spotless (google-java-format) | Zero violations |
| Static analysis | Error Prone + SpotBugs | Zero HIGH findings |
| Unit tests | JUnit 5 | 100 % pass |
| Integration tests | Testcontainers (real Postgres + Redis) | 100 % pass |
| Coverage | JaCoCo, on domain and service layers | ≥ 85 % line, ≥ 75 % branch |
| Dependency security | OWASP Dependency-Check | Zero CVSS ≥ 7 |
| API contract | OpenAPI spec diffed against previous revision | No unannounced breaking change |
| Performance | k6 smoke on the redirect path | Meets the spec's stated budget |

**VI.1** Coverage is a floor, not a target. A high number over weak assertions is worse
than a lower number over strong ones, because it is actively misleading. Tests assert
behaviour, not implementation shape.

**VI.2** A gate that is failing is fixed or explicitly waived in writing by the engineer
of record with an expiry. A silently disabled gate is a defect.

---

## Article VII — Secure AI usage

**VII.1** No secret, credential, token, customer record or production dataset is ever
placed in a prompt. Fixtures are synthetic. This is absolute and is not subject to
convenience.

**VII.2** AI-suggested dependencies are verified to exist, to be actively maintained, and
to carry a compatible licence before adoption. Hallucinated or typosquatted packages are a
known and documented supply-chain attack vector against AI-assisted development, and this
repository treats every AI-proposed dependency as untrusted until checked.

**VII.3** AI-generated code touching authentication, authorization, input validation,
redirect targets, or data egress is reviewed against the relevant OWASP guidance before
merge — not merely tested. Tests confirm the cases you imagined; review catches the ones
you did not.

**VII.4** Generated code is assumed to reflect common patterns in training data, which
includes common *vulnerabilities*. Plausibility is not correctness.

---

## Article VIII — Human ownership and sign-off

**VIII.1** The engineer of record owns every artifact in this repository regardless of
which keystrokes were typed by a tool. "The AI wrote it" is not available as an
explanation for a defect, and this document exists partly to make that excuse structurally
impossible.

**VIII.2** **High-impact changes require explicit written sign-off** before merge.
High-impact means any of: a public API contract change; a data model or migration change;
anything touching authentication, authorization or rate limiting; a dependency addition;
a change to a quality gate; or anything the engineer judges hard to reverse.

**VIII.3** Gates requiring approval before the next phase begins:

| Gate | Between | Approves |
|---|---|---|
| **A** | Spec → Plan | Problem is correctly understood; ambiguities are registered |
| **B** | Plan → Tasks | Architecture and trade-offs are sound |
| **C** | Tasks → Implementation | Decomposition is complete, ordered, and testable |
| **D** | Implementation → Merge | Code is read, gates are green, risks are documented |

---

## Article IX — Change safety and reversibility

**IX.1** Prefer reversible changes. Where a decision is one-way, say so out loud in the
plan and justify why it is being taken now rather than deferred.

**IX.2** Behaviour-changing work ships behind a configuration flag with a documented
default and a documented rollback, unless the change is provably additive.

**IX.3** Database migrations are forward-only, additive-first, and separated from the code
that depends on them by at least one deploy — expand, migrate, contract.

**IX.4** Every scenario documents its failure modes and their detection *before*
implementation begins, in the plan's Risk Register. A risk discovered only after an
incident was not managed; it was survived.

---

## Article X — Amendment

This constitution is amended by pull request that states the article changed, the reason,
and the consequence for existing artifacts. Amendments are versioned. Silent edits are
prohibited.

---

## The workflow this constitution produces

```
   ┌──────────┐   Gate A   ┌──────────┐   Gate B   ┌──────────┐   Gate C   ┌────────────────┐   Gate D
   │  SPECIFY │ ─────────▶ │   PLAN   │ ─────────▶ │  TASKS   │ ─────────▶ │   IMPLEMENT    │ ────────▶ merge
   │  what/why│            │   how    │            │ ordered  │            │ + VALIDATE     │
   └──────────┘            └──────────┘            └──────────┘            └────────────────┘
        │                       │                       │                          │
        ▼                       ▼                       ▼                          ▼
  Ambiguity Register     ADRs + Risk Register    Dependency graph        Traceability ledger
                                                                          + quality gates
```

Each arrow is a human decision. That is the entire point.
