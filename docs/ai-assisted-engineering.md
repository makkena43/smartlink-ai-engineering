# SmartLink Engineering Constitution

**Status:** Ratified · **Version:** 1.1 · **Owner:** Srinivas Makkena (engineer of record)

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

**I.1** No production code is written before a requirements baseline and engineering
specification for that scenario exist, are committed, and are approved at Gate A.

**I.2** Requirements state **what** and **why**. The engineering specification translates
approved requirements into a buildable design and may contain architecture, framework,
schema, API, library, quality-gate, and validation decisions. A plan/task decomposition then
states the ordered execution work. This separation is enforced at review: product requirements
must not silently contain implementation choices, and engineering specifications must trace
every material choice back to a requirement or recorded assumption.

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

**V.1** The repository maintains its traceability ledger at
`docs/ai-assisted-engineering.md`, with scenario/task references for each material AI
contribution. The ledger classifies each contribution as:

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

No change is ready for submission unless all of the following pass. They are runnable locally;
a production repository would enforce the same checks in CI and would not waive them by the
author acting alone.

| Gate | Mechanism | Threshold |
|---|---|---|
| Build | `./mvnw verify` | Zero errors |
| Format | Spotless (google-java-format) | Zero violations |
| Static analysis | Error Prone + SpotBugs | Zero HIGH findings |
| Unit tests | JUnit 5 | 100 % pass |
| Integration tests | Testcontainers (real PostgreSQL) | 100 % pass |
| Coverage | JaCoCo, on domain and service layers | ≥ 85 % line, ≥ 75 % branch |
| Dependency security | Trivy dependency scan, recorded as pre-submission evidence | Zero HIGH / CRITICAL findings |
| API contract | OpenAPI spec diffed against previous revision | No unannounced breaking change |
| Performance | Bounded local load run on the redirect path | Method, machine and sample size reported — **no extrapolated scale claims** |

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

---

## Amendment log

Article X requires that amendments state the article changed, the reason, and the
consequence for existing artifacts. Silent edits are prohibited, so this log exists to make
the edit history of the governing document itself auditable.

### v1.1 — Article VI (Quality gates)

**Changed.** Two rows of the gate table.

1. *Integration tests* — "Testcontainers (real Postgres + Redis)" → "Testcontainers (real
   PostgreSQL)".
2. *Performance* — "k6 smoke … meets the spec's stated budget" → "Bounded local load run …
   method, machine and sample size reported, no extrapolated scale claims".

**Reason.** The original v1.0 gates were written before the delivery constraints were fixed
and encoded two assumptions that did not survive contact with them.

The Redis reference presumed a caching tier in v1. The accepted trade-off is to read from
the system of record in v1 and introduce read-through caching only when measured demand
justifies it — so a gate demanding a Redis container would have been a gate enforcing
architecture the design had deliberately deferred. Gates must test the system that exists,
not the one originally imagined.

The performance row asserted a fixed latency budget as a merge gate. A prototype measured on
a single laptop cannot substantiate a production latency target, and a gate that appears to
prove one is worse than no gate at all: it manufactures false confidence and invites exactly
the unverified scale claim this project should not make. The gate is retained — performance
is still measured, and regressions are still visible — but it now gates on *honest reporting
of method and limits* rather than on hitting a number the environment cannot legitimately
produce.

**Consequence for existing artifacts.** None retroactive. No code existed at amendment time.
`docs/scenarios/01-greenfield/engineering-spec.md` §2 was written against v1.1 and separates
production design targets from what a laptop actually demonstrates. The deferred caching
tier is carried as an explicit trade-off with its evolution path, not as an omission.

---

# Part II — AI-assisted engineering in practice

Part I is the governing process. This part is what it looked like in use.

## 1. Where AI was used, and what the engineer did

| Activity | AI's role | Engineer's action | Evidence retained |
|---|---|---|---|
| **Requirement review** | Enumerate readings of an underspecified sentence and their consequences | Select the interpretation; own the rationale and the blast radius | Ambiguity registers — 10 entries in v1, 3 in v2, 5 assumptions in v3 |
| **Architecture** | Draft options and trade-offs | Decide; record the negative consequences, not just the positive | 9 ADRs in `decisions.md` |
| **Scaffolding and code** | Generate bounded components from explicit acceptance criteria | Read every line before commit; reject what does not fit | Ledger below; commit history per task |
| **Test design** | Propose edge cases — invalid scheme, collision, unknown code, expiry, database failure | Decide which are load-bearing; add the ones AI missed | Traceability matrices in each `validation.md` |
| **Debugging** | Hypothesise causes | Verify against actual output before acting | — |
| **Documentation** | Improve clarity | Retain factual ownership; correct inaccuracies | Docs in the engineer's voice |

## 2. The task envelope

Prompts of the form *"write me a URL shortener"* are prohibited by Article IV.1 — they
transfer design authority to the tool. Every task in `task-decomposition.md` instead carries
four fields, and the AI is invoked with them:

```
Intent        the outcome, not the keystrokes
Constraints   what it may not do or break
Acceptance    how completion is proven, mechanically
Context       the files, contracts and invariants it touches
```

The difference is not stylistic. An envelope makes the output *checkable* — there is a
stated criterion it either meets or does not — whereas an open prompt produces something
plausible that must then be evaluated against a standard nobody wrote down.

## 3. Traceability ledger

Article V. Every material AI contribution is classified `GENERATED` (accepted substantially
as produced), `EDITED` (accepted after modification), or `REJECTED` (discarded).

**A ledger with no rejections is evidence that review was not happening** (Article IV.4).
Rejections are recorded with more care than acceptances, because they are the strongest
available proof that judgment was applied rather than output accepted.

| ID | Artifact | Class | Summary |
|---|---|---|---|
| L-001 | `pom.xml` — dependency versions | **EDITED** | Versions were queried live against Maven Central rather than recalled from training data. Spring Boot 3.5.3, springdoc 2.8.6, Testcontainers 1.21.3 confirmed to exist before adoption (Article VII.2 — hallucinated and typosquatted packages are a documented supply-chain vector against AI-assisted development) |
| L-002 | `SmartLinkApplication.java` | **EDITED** | Generated Javadoc failed the Spotless gate on its first run. Fixed by the formatter. Recorded because it is direct evidence the gate is load-bearing rather than decorative — the first AI output into this repo was rejected by automation before a human saw it |
| L-003 | `scripts/smoke-test.sh` — analytics assertion | **REJECTED** | First version asserted an exact resolution count against a link that earlier steps had already probed, including two `curl -I` HEAD requests. The number would have been wrong the moment the script ran. Replaced with a dedicated link resolved a known number of times. The failure mode this avoids is worse than a broken test: an exact assertion that fails for an uninteresting reason gets "fixed" by loosening it until it proves nothing |
| L-004 | `docker-compose.yml` | **REJECTED** | Generated version set `SPRING_PROFILES_ACTIVE: docker`, referencing a profile that does not exist in the agreed structure. Spring would have silently ignored it, leaving a config file that appears to select a profile and does not. Removed; the default `application.yml` is already fully environment-driven |
| L-005 | Spec v1 §6 — performance targets | **REJECTED** | Original draft claimed `p99 ≤ 50 ms at 500 rps`. Unverifiable on the target environment and precisely the unsupported scale claim the assessment warns against. Replaced with an SLI/SLO table separating production design targets from what a laptop demonstrates. Prompted the Constitution v1.1 amendment |
| L-006 | Scenario split | **REJECTED** | Initial plan made analytics the brownfield change and expiry part of greenfield. Reversed: expiry is compact but genuinely cross-cutting — schema, API, redirect logic, compatibility, migration, docs, tests — which demonstrates codebase reasoning without creating a second product |
| L-007 | NFR-5 — collision handling | **EDITED** | AI framed the requirement as a collision *probability* bound. Rephrased around *behaviour under a forced collision*: probability arguments fail silently, a test that forces the collision and asserts recovery does not |
| L-008 | `application.yml` — Hikari timeouts | **GENERATED** | Connection timeout reduced from the 30 s default. Accepted: waiting 30 s to discover the database is gone converts one dependency outage into thread-pool exhaustion service-wide |

| L-009 | `01-greenfield/requirements.md` rev 2 — gap analysis | **REJECTED** | Engineer-authored requirements were reviewed for cases a compliant implementation could satisfy while still being wrong, and eight additions were proposed at Gate A. **None was adopted as a requirement.** The engineer declined the numbering wholesale, and the decision is recorded at L-013 rather than re-argued here. The proposal is classified REJECTED on that basis — not on whether its reasoning was sound, which is a different question and the reason this entry is worth keeping. Its substance re-entered by two routes neither of which was this one: four items with security consequence were re-expressed as **engineering decisions under requirements already stated** (L-013), and the address-range and length items later arrived as genuine requirements when the engineer independently directed that URL validation be covered (L-016) — landing as **GF-15** and **GF-17**, not the GF-14/GF-15 this entry proposed. The remaining four (routing precedence, analytics fail-open, correlation ID, no code reassignment) are implemented and tested without ever becoming requirement text: `RedirectController` route ordering, `AnalyticsFailureIT`, `CorrelationIdFilter`, and ADR-002 respectively. **Left PENDING far too long.** An entry that says "stands or falls at Gate A" is fine until Gate A has passed, after which it is an unresolved contribution sitting in a ledger whose entire purpose is that there are none — and it survived three scenarios because nothing checks that the ledger has no open rows. That is the finding, more than the classification is | Traced to GF-15, GF-17, L-013, L-016, and the four named tests | Engineer approved. |
| L-010 | Requirement tensions D-1, D-2 | **EDITED** | Two conflicts between engineer-authored requirements were surfaced rather than silently resolved in the engineering spec: retry-safety against GF-04's independent-link rule, and NFR-08's hot-key resilience against the obvious implementation of GF-11. Both recommendations argue **against** adding machinery — keep GF-04 strict, keep the counter synchronous and measure the contention. Recorded because the reflex on a tension like D-2 is to add a mechanism, and a prototype that batches counters before measuring contention has optimised against a guess |
| L-011 | ADR-003 — custom alias namespace | **REJECTED** | Withdrawn once requirements §6 placed custom aliases out of scope. Retained rather than deleted: the enumeration-oracle reasoning survives the feature, and the routing-safety half of it was promoted to GF-16, which applies whether or not aliases exist |

| L-012 | `01-greenfield/engineering-spec.md` §8.3 — retry policy | **GENERATED** | Asymmetric policy adopted as directed: resolve path capped at one jittered retry, create path given a separate 3-attempt collision allowance plus one transient-failure retry. Accepted because the reasoning is sound and load-bearing — the resolve path carries the entire load, so three retries per request under a database outage amplify load against a failing dependency, hold threads, and delay the `503` a client needs in order to fail fast. The retry cap is a load-shedding decision as much as a resilience one |
| L-013 | Requirements revision | **REJECTED** | Eight additions proposed at revision 2 (GF-14…GF-18, NFR-14…NFR-16) were not carried into the final engineer-authored requirements. Rather than re-argue them, the four with security consequence were re-expressed as *engineering* decisions under requirements already stated — SSRF/scheme policy under GF-10, analytics fail-open under NFR-02, log hygiene under NFR-04, unguessable non-reassigned codes under GF-05/GF-12 — and listed openly in spec §1.3 as decisions that exceed the literal requirement text. A spec that quietly widens its own mandate is how scope creep becomes invisible |
| L-014 | Reference material — caching and scale guidance | **EDITED** | Supplied guidance included a redirect flow reading from cache and read replicas. Adopted for §8.4/§8.5 as *documented production evolution* only, not prototype scope, because requirements §6 places production cache and read replicas out of scope — the guidance and the requirements conflicted, and the requirements won. Two properties were added that the source omitted: cache write-through at creation (a lagging replica would otherwise serve a false 404 on a just-created link), and the observation that once a cache exists a stale entry *is* a wrong redirect, making TTL a correctness bound under NFR-02 rather than a performance knob |
| L-015 | `architecture-overview.md` | **EDITED** | Demoted to a placeholder pointing at spec §3. It described a system that does not exist yet, and a reviewer cannot distinguish an aspirational architecture diagram from a real one |

| L-016 | Requirements GF-14…GF-19, NFR-14…NFR-16 — URL validation | **EDITED** | Engineer identified that no requirement covered injection-safe or unsafe destination types, and directed that it be added. Drafted as seven functional and three quality requirements. Two were added beyond the literal request because they are the same class of defect: **GF-18** (control characters / response-header integrity) and **NFR-16** (fail closed). GF-18 is the one specific to *being a redirect service* — the destination is written into a `Location` **response header**, so a `%0d%0a` payload is a response-splitting primitive, not merely an XSS one. Deliberately **not** added: homograph and confusable-domain detection, which is a phishing control rather than an injection control, and where a partial implementation gives false assurance — recorded as R-1c and left explicitly unaddressed |
| L-017 | Spec §9.1 — validation pipeline ordering | **EDITED** | Draft evaluated the scheme allowlist before host normalisation. Reordered: normalise first, then decide. A validator that decides before normalising is checking a string the rest of the system will never see, which is how `http://expected.com@169.254.169.254/` passes a substring check — everything before `@` is userinfo and is discarded by the parser |
| L-018 | Spec §9.1.6 — TOCTOU limitation | **GENERATED** | Recorded that creation-time DNS validation cannot survive a hostname being re-pointed afterwards, and that there is no fix at creation time. Kept as an accepted limitation with a binding constraint on the first feature that fetches a destination, rather than implying the control is complete. Claiming SSRF is "solved" when only half the window is covered is worse than stating the gap |
| L-019 | Spec §11.2 — `DestinationPolicyTest` | **EDITED** | Draft tested a handful of representative bad URLs. Restructured as a table over every notation in §9.1.3. Encoding-evasion bugs are found by enumeration, not by reasoning — the failure is always a notation nobody considered, so adding one must cost a single line |

| L-020 | Engineering spec review — `version` optimistic-lock column | **REJECTED** | The reviewed draft schema carried a `version` column for optimistic locking. Rejected: `total_redirects` is written on *every* redirect, so a load-modify-save cycle guarded by `@Version` makes two concurrent redirects of one link collide — the failure rate would rise with popularity, exactly inverting NFR-08. Replaced with an atomic `UPDATE … SET total_redirects = total_redirects + 1`, and `ConcurrentRedirectIT` added as the executable form of the constraint. This was the only finding in the review that was a defect rather than an omission |
| L-021 | Engineering spec review — error-rate SLI | **REJECTED** | Draft §9.1 defined error rate as 4xx **and** 5xx over total requests, contradicting its own §9.2. A `404` for an unknown code is correct behaviour, so folding 4xx into a reliability SLI makes it unusable — a burst of scanner traffic would breach the objective while the service works perfectly. Split into a 5xx reliability signal and a separately tracked 4xx product signal |
| L-022 | Engineering spec review — remaining findings | **EDITED** | Nine further gaps closed against the reviewed draft: missing destination-validation controls (GF-15/16/18/19, NFR-16); unspecified analytics-failure behaviour; missing `Cache-Control: no-store`; unspecified short-code length; undefined collision-exhaustion status; liveness/readiness absent from the API table; route precedence unstated; `400`/`422` not distinguished; and a `302` rationale citing destination mutation, which requirements §6 places out of scope — replaced with the analytics-completeness argument that actually holds |
| L-023 | `task-decomposition.md` §11 T4 dependencies | **EDITED** | Draft made the domain task depend on the OpenAPI contract and the schema. Corrected to depend only on the scaffold: the domain layer imports no framework and performs no I/O, so sequencing it behind them would delay the most exhaustively testable work in the project behind unrelated scaffolding |
| L-024 | Contract reconciliation across artifacts | **EDITED** | Adopting the reviewed spec's `/analytics` naming left committed artifacts inconsistent. `smoke-test.sh`, `api-overview.md`, `README.md`, `application.yml`, `application-local.yml`, `.env.example` and `docker-compose.yml` still carried `/stats`, `totalResolutions` and `X-API-Key` — the last contradicting GF-03 outright. All reconciled; the smoke test gained twelve destination-policy assertions and an error-reflection check |

| L-025 | `task-decomposition.md` — requirement coverage | **EDITED** | The reviewed decomposition claimed GF-01…GF-13 and NFR-01…NFR-13 but not the ten requirements added since: GF-14…GF-19 and NFR-14…NFR-16 appeared in no task. T4 covered only "unsupported schemes and malformed inputs", so SSRF address ranges, notation evasion, CRLF and fail-closed would have been specified but never built. Added to T3, T4, T6 and T7, and a **requirement-coverage table** added as the mechanical check — the failure mode was invisible precisely because every individual task looked complete |
| L-026 | `task-decomposition.md` T3 and T6 — invisible constraints | **EDITED** | Two decisions established during spec review had no home in the task list and would have been silently lost: the prohibition on an optimistic-lock column, and analytics fail-open. Both are the kind of constraint that is **easy to reintroduce by accident** — adding `@Version` looks like diligence, and wrapping resolution and increment in one transaction looks like correctness. Promoted to blockquote constraints on their owning tasks, each naming the test that enforces it |
| L-027 | `task-decomposition.md` T9 — NFR-06 scope | **REJECTED** | Draft listed NFR-06 through NFR-11 as validated by T9. Removed NFR-06: horizontal scalability is established by construction in T1 and cannot be validated by a scan or a single-instance load test. Listing it would have produced a checked box against evidence that does not exist, in the one task whose purpose is honest evidence |

Entries L-001 through L-027 cover the scaffold and specification phase.

---

## 5. Implementation ledger format

From T2 onward, entries use the per-task format defined in
[`scenarios/01-greenfield/task-decomposition.md`](scenarios/01-greenfield/task-decomposition.md) §7,
which captures the prompt envelope and the approval alongside the classification:

| Task | Intent and constraints supplied | Output used | Class | Engineer edit/rejection and rationale | Validation | Approval |
|---|---|---|---|---|---|---|
| **T1** | Enforce the §3.3 dependency rule as a build failure, not a document. No new dependency without verifying it exists. | ArchUnit rules: domain framework-free, domain I/O-free, inward-only layer dependencies, no static mutable state. | **GENERATED** | Accepted. ArchUnit 1.4.1 verified on Maven Central before adoption, per the secure-AI rule on hallucinated and typosquatted packages. | `LayeringTest` 4/4 pass. | Engineer approved. |
| **T1** | Same. | `allowEmptyShould(true)` on the two domain rules. | **EDITED** | ArchUnit failed the build because no `domain` classes exist yet, and it refuses to report "checked nothing" as a pass — correct behaviour. Suppression added, but **documented in the class Javadoc as a vacuous gate alongside risk R-5**, with the condition for removing it. Silently suppressing it would have reproduced exactly the JaCoCo hole already on the risk register. | `LayeringTest` passes; rules become load-bearing at T4. | Engineer approved with the limitation recorded. |
| **T1** | Prove the app starts against a **real** database and reports health, without leaking internals. | `SmartLinkApplicationIT` — context load, liveness, readiness, health-detail suppression, actuator surface, OpenAPI reachability. | **EDITED** | Added two assertions beyond the task's acceptance criteria: that `/actuator/{env,beans,configprops,mappings,loggers}` return 404, and that the health body names no database, host or JDBC URL. Both guard one-line config changes that no other test would notice, on an endpoint that is unauthenticated. | 6/6 pass against PostgreSQL 16 in Testcontainers. | Engineer approved. |
| **T1** | Make `mvn verify` work from a clean clone. | Initial diagnosis pointed at the Docker socket. | **REJECTED** | Testcontainers reported `Could not find a valid Docker environment`, which points at the socket — and the socket was fine. The daemon was answering `HTTP 400: client version 1.32 is too old`. Docker Engine 29 raised its minimum API to 1.40; docker-java ships 1.32 as its default. `DOCKER_API_VERSION` and `TESTCONTAINERS_API_VERSION` were both tried and are both ignored — docker-java reads a **system property** named `api.version`. Chasing the reported error rather than the actual response would have wasted the whole task. | `mvn verify` green from clean. | Engineer approved. |
| **T1** | Same. | Fix committed to `pom.xml` rather than a shell export. | **EDITED** | `api.version` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` moved into the failsafe configuration so a clean clone works. `DOCKER_HOST` deliberately left to the environment: it is machine-specific, and pinning it would break every non-Colima user. | Clean `mvn verify` with only `DOCKER_HOST` exported: 10/10 tests, coverage gate executing. | Engineer approved. |
| **T2** | Public error vocabulary per spec §4.4. Errors actionable but disclosing nothing internal. No HTTP knowledge below the api layer. | `ErrorCode` enum, `SmartLinkException` hierarchy, `ApiExceptionHandler`. | **EDITED** | Exceptions were initially drafted carrying their own HTTP status. Moved the status into `ErrorCode` in the api layer: a status is a transport decision, and embedding it would make the types unusable from a batch import or message consumer that has no reason to know what 422 means. Added `safeDetail()` separate from `getMessage()` — the message is for operators and may name internal state; only `safeDetail()` reaches a caller. | `ErrorContractTest` 11/11. | Engineer approved. |
| **T2** | Same. | Catch-all `@ExceptionHandler(Exception.class)`. | **REJECTED** *(as first written)* | **Introduced a real bug.** Spring Boot 3.2+ raises `NoResourceFoundException` for an unmatched path; the catch-all swallowed it and returned **500 for every 404**. That is exactly the corruption of the 500 signal `INTERNAL_ERROR`'s own contract promises to prevent — if a typo or a scanner produces 500, nobody can use 500 to decide whether to investigate. Fixed by handling `NoResourceFoundException` and `NoHandlerFoundException` explicitly. **Found by T1's actuator-exposure test, not by any error-contract test** — the T1 assertion was written to guard config drift and caught a logic regression instead. | 33/33 after fix. | Engineer approved. |
| **T2** | Propagate a request identifier to responses and logs. | `CorrelationIdFilter`. | **EDITED** | Draft echoed the inbound header as supplied. Rejected: the value goes into a response header *and* into logs, making an unchecked value both response-header injection (the same CRLF primitive as `Location`, GF-18) and log forgery. Changed to an allowlist with **replacement rather than sanitisation** — sanitising invites a bypass via an encoding the sanitiser misses. Added MDC cleanup in a `finally` block, because a pooled thread would otherwise stamp the next request with the previous request's id, producing logs that are confidently wrong. | `CorrelationIdFilterTest` 12/12, including 8 hostile-input cases. | Engineer approved. |
| **T3** | Initial schema. Forward-only, additive-first, no PII column, **no optimistic-lock version**. | `V1__create_short_link.sql`. | **EDITED** | Draft included a `version` column, which is the standard JPA reflex. Removed: `total_redirects` is written on every redirect, so a version turns it into load-modify-save and concurrent redirects of one link collide — failure rate rising with popularity, inverting NFR-08. Added a `CHECK (total_redirects >= 0)` the draft lacked: unreachable through the application today, but it is what stops a bad migration or a future async aggregation silently storing nonsense that later gets reported as fact. | `SchemaConstraintsIT` 6/6. | Engineer approved. |
| **T3** | Same. | Schema-introspection tests over `information_schema`. | **GENERATED** | Accepted. Two of T3's most important properties are *absences* — no version column, no PII column — and an absence is exactly what a behavioural test cannot notice: nothing fails when an extra column appears, it just quietly starts being used. Asserting the column set structurally converts "we agreed not to" into "the build refuses". | 6/6. | Engineer approved. |
| **T3** | Persistence for create, lookup and counter update. Parameterised queries only. | `ShortLinkEntity`, `ShortLinkJpaRepository`. | **EDITED** | Counter mapped `insertable = false, updatable = false` so it is *impossible* to increment by loading, mutating and flushing — the implementation the design rules out. Entity equality keyed on `shortCode`, not the surrogate `id`: id-based equality makes an instance unequal to itself across a save, so anything holding it in a `Set` silently loses it. `toString()` deliberately omits the destination, since it reaches logs by accident far more often than by design and query strings carry reset tokens. | `ShortLinkRepositoryIT` 9/9, `ShortLinkEntityTest` 6/6. | Engineer approved. |
| **T3** | Prove the atomic increment. | `concurrentIncrementsLoseNoCounts` — 16 threads × 25 increments. | **GENERATED** | Accepted. The assertion fails under *both* rejected implementations: read-modify-write loses updates silently, `@Version` throws outright. Only a single `UPDATE … SET x = x + 1` satisfies it, which makes this the executable form of the T3 constraint rather than a comment about it. | 400/400 increments recorded. | Engineer approved. |
| **T3** | Reach the coverage gate. | Suggestion to lower the branch threshold. | **REJECTED** | Branch coverage came in at 58% against a 75% gate. Lowering it would have been the exact failure already on the risk register as R-5 — a gate reporting green without looking. Inspected instead: only 12 branches existed and `ShortLinkEntity.equals()` accounted for 4 with zero coverage. Wrote real tests for the JPA equality trap and the `currentId` fallback. Branch coverage 100%, and the tests are worth having on their own merits. | 55/55 tests, line 90.8%, branch 100%. | Engineer approved. |
| **T4** | Destination policy per spec §8.1. Normalise before deciding. Zero framework imports, DNS behind a port. | `DestinationPolicy`, `HostLiterals`, `AddressPolicy`, `Destination`, `PolicyViolation`. | **EDITED** | Draft compared the host *string* against a blocklist. Rejected: that admits every numeric spelling of an address. Restructured so the host is converted to bytes first and rules run against 4 or 16 octets — full `inet_aton` handling (1–4 parts, decimal/octal/hex), IPv6, and the authority taken after the **last** `@`. Added ranges Java's own predicates miss: `isSiteLocalAddress()` does not cover IPv6 unique-local (`fc00::/7`) at all, and nothing covers carrier-grade NAT. | `DestinationPolicyTest` 55/55. | Engineer approved. |
| **T4** | Same. | Rely on `URI.getHost()` for the host component. | **REJECTED** | `java.net.URI` requires a hostname's final label to begin with a letter, so it returns **null** for `0251.0376.0251.0376`, `169.254.43518` and `127.1`. Those were therefore being refused as `HOST_MISSING` — the right HTTP outcome for the wrong reason. Rejection resting on a parser quirk rather than on the address policy would be silently undone by any future parser that became more permissive, with no test noticing. Added `rawAuthorityHost()` so these are classified as `BLOCKED_ADDRESS` on their merits. | Notation table asserts the violation, not just the refusal. | Engineer approved. |
| **T4** | Same. | Notation-table fixtures. | **REJECTED** | Two generated fixtures were arithmetically wrong: `025177650776` is not the octal form of `169.254.169.254` (`025177524776` is), and the two-part form should be `169.16689662`. The suite failed on both. Kept as a note in the test, because the failure demonstrates something no passing case can: the policy genuinely **evaluates** these hosts rather than reflexively refusing anything unusual — a blanket-reject implementation would have passed the bad fixtures too. | 143/143 after correction. | Engineer approved. |
| **T4** | Enforce the layering rule now that domain code exists. | `LayeringTest.noStaticMutableState`. | **REJECTED** *(as first written)* | The rule checked **class** modifiers, so it read as "no static non-final classes" and duly flagged a nested sealed interface — interfaces are never final. Rewritten against fields. The mistake was undetectable while the domain package was empty and the rule matched nothing, which is precisely the hazard the class Javadoc records: **a rule that checks nothing cannot be observed to be checking the wrong thing.** | 4/4, now against real domain classes. | Engineer approved. |
| **T4** | Short-code generation. | `CodeGenerator` on `SecureRandom`. | **GENERATED** | Accepted. Sequential and hash-of-destination both rejected in the design: the first makes the corpus walkable by counting, the second leaks whether a URL was shortened before *and* silently reintroduces the deduplication GF-04 rules out. `nextInt(bound)` rather than a modulus, which would bias the leading characters of a value whose whole job is to be unguessable. | `CodeGeneratorTest` 20/20, incl. full alphabet reachability. | Engineer approved. |
| **T5** | Create-link use case. No destination lookup anywhere. Collision allowance separate from transient allowance. | `CreateLinkUseCase`, `LinkRepository` port, `Link`. | **EDITED** | Port placed in `domain/port` rather than `application`, so `infrastructure` can implement it without violating the layering rule that `application` is reachable only from `api`. Signature takes `Destination` — obtainable only from `DestinationPolicy` — which makes GF-19 a compile-time property rather than a convention. Use case left **non-transactional** so each insert attempt stands alone. | `CreateLinkUseCaseTest` 10/10. | Engineer approved. |
| **T5** | Same. | `@Transactional(REQUIRES_NEW)` on the insert adapter, catching the violation inside. | **REJECTED** | **Does not work, and fails pointing nowhere near the cause.** A constraint violation marks the transaction rollback-only the instant it is raised, so swallowing it inside the method returns normally into a transaction that can no longer commit — Spring then throws `UnexpectedRollbackException: transaction silently rolled back` at the boundary. The collision is handled correctly and the request still fails, with an error naming neither the collision nor the retry. Replaced with a `TransactionTemplate` so the catch sits **outside** the boundary: the template settles the transaction, then the exception surfaces on clean ground. Three integration tests failed on this and passed after. | `CreateLinkIT` 5/5. | Engineer approved. |
| **T5** | Prove insert-and-retry under real contention. | `forcedConcurrentCollisionHasOneWinner` — 8 threads handed the same first candidate. | **GENERATED** | Accepted. Sequential collision tests show the loop runs; this shows the *database* arbitrates. One thread takes the contested code, seven fall back to their own, all eight succeed. A check-then-insert would let several threads observe the code as free, and the corruption would be timing-dependent — reproducible only occasionally, which is the worst kind to chase. | 8/8 distinct codes, exactly one winner. | Engineer approved. |
| **T5** | Assert GF-04. | Test that no destination lookup occurs. | **EDITED** | Draft asserted only that two creates yield two codes. Strengthened to assert the *absence* of any storage read on the create path, using a fake that records what it was asked. Deduplication is an easy, well-meaning addition that looks like an optimisation — asserting the outcome would not catch it, since a dedup check that never fires still passes. | `neverLooksUpByDestination`. | Engineer approved. |
| **T6** | Resolve a code, record the hit, never serve an unverified destination. | `ResolveLinkUseCase`. | **EDITED** | Draft wrapped the whole method in one try/catch. Rejected: that swallows a *lookup* failure too, so a request whose mapping could not be verified would fall through to something rather than failing — the exact outcome NFR-02 forbids. Narrowed the catch to the counter alone. Added `failOpenScopeIsNarrow` as a standalone test, because the two behaviours are one refactor apart: widening the catch by a single line silently converts a 503 into a redirect to an unverified destination. | `ResolveLinkUseCaseTest` 8/8. | Engineer approved. |
| **T6** | Emit the redirect. | `ResponseEntity.location(URI.create(...))`. | **REJECTED** | That overload writes `URI.toASCIIString()`, which re-encodes the value. GF-07 requires the destination byte-identical: re-encoding is invisible in most URLs and fatal in signed ones, where changing a single escape invalidates the signature — and the failure would surface as an underperforming campaign, not as anything traceable back here. Replaced with a raw `Location` header, which is safe only because the destination policy already refused control characters (GF-18). The two decisions are load-bearing together. | `locationIsByteIdentical`; end-to-end assertion through the full stack. | Engineer approved. |
| **T6** | Keep operational routes un-shadowed. | Path-variable regex `{code:[A-Za-z0-9]{7}}`. | **GENERATED** | Accepted. GF-16 enforced by pattern rather than by registration order: `/actuator/**` and `/api/v1/**` cannot be captured because they do not match, not because they happen to be registered first. A future change to code length must change this pattern, which makes the coupling visible instead of latent. | 7 non-code paths asserted un-captured; 3 operational routes asserted reachable end-to-end. | Engineer approved. |
| **T6** | Map a datastore failure to 503 (NFR-02). | `DataAccessException` handler in the API layer. | **EDITED** | Translating in the persistence adapter would have been the instinctive place, but `infrastructure` cannot depend on `application` under the layering rule, and that is where the exception vocabulary lives. Handled at transport instead — which is where the decision belongs anyway, since "what does this failure look like on the wire" is a transport question. Without it these fell to the catch-all and returned 500, meaning a dependency outage was indistinguishable from a bug. | End-to-end error assertions. | Engineer approved. |
| **T6** | *(carried from T5)* | Create controller. | **EDITED** | T5's task list included "add create controller" and the implementation stopped at the use case. Noticed while wiring T6, delivered here. Recorded rather than quietly absorbed: a task marked complete that was not is the failure mode the coverage table in `task-decomposition.md` exists to catch, and it caught nothing here because the check is per-requirement, not per-deliverable. | End-to-end create → redirect → analytics. | Engineer approved. |
| **T7** | Bounded retry: transient failures only, upper bound asserted. | `BoundedRetry`, `TransientFailures`. | **EDITED** | Draft retried any `DataAccessException`. Narrowed: a constraint violation is the *collision signal*, so retrying it would consume the caller's three collision candidates on a single genuine clash. Classification, not the count, is what stops a retry making an outage worse. Applied to reads and inserts but **not** to the counter — it is fail-open, so retrying buys nothing a visitor can perceive while adding latency to the hot path exactly when the database is already struggling. | `BoundedRetryTest` 15/15. | Engineer approved. |
| **T7** | **Readiness must fail when the database is unreachable (GF-13).** | Fault-injection test against a dead port. | **REJECTED** *(the shipped config)* | **Readiness returned 200 OK with no database.** Spring's default readiness group contains only `readinessState` and never consults `db`, so a load balancer would keep routing to an instance unable to serve a single request. The T1 readiness test passed throughout — because the database was up. A health test written against a healthy dependency asserts nothing about health. Fixed by declaring the group membership explicitly, with `liveness` pinned to exclude `db` for the opposite reason. | `DependencyOutageIT` 6/6. | Engineer approved. |
| **T7** | Same. | `DataAccessException` handler alone. | **REJECTED** | **Create returned 500 while redirect returned 503, for the identical outage.** A connection failure raised while opening a transaction surfaces as `CannotCreateTransactionException`, which descends from `TransactionException`, not `DataAccessException`. So the same failure was reported two different ways depending on which endpoint you hit — and the create path was claiming "something is broken" when the correct answer was "come back". Added `TransactionException` to the handler and to the transient classifier. | Both paths now 503. | Engineer approved. |
| **T7** | Prove destination URLs never reach the logs (NFR-14). | Capture root logger at DEBUG, assert the secret is absent. | **EDITED** | Failed, and the failure was informative: five *framework* loggers emit the destination at DEBUG — `RestTemplate`, `DispatcherServlet`, and `RequestResponseBodyMethodProcessor` logging the deserialised body verbatim. **Zero came from `com.smartlink`.** Scoped the assertion to this application's loggers, which is the guarantee this codebase can actually make, and closed the framework half in configuration by pinning `org.springframework.web` and Hibernate's SQL loggers to INFO. Without the pin, setting root to DEBUG to troubleshoot anything would silently begin writing customer tokens to disk. Added a test asserting the pin exists, since it is one YAML line and exactly the kind of thing removed in a tidy-up by someone who cannot see what it protected. | `LogHygieneIT` 5/5. | Engineer approved. |
| **T8** | Prove fail-open against a real failure, not a stub. | `AnalyticsFailureIT` using a PostgreSQL trigger that refuses every UPDATE. | **EDITED** | Draft used a mocked repository. Rejected: that proves only that the application layer catches what the stub throws. Replaced with a database-level trigger, so a genuine refusal travels the driver, Hibernate, the transaction manager and the repository before the visitor still gets their redirect. Reads stay untouched, which is exactly the production shape — healthy read path, broken write path. Added a complement asserting creation still fails loudly, since fail-open must apply to instrumentation and never to what the caller actually asked for. | 5/5. | Engineer approved. |
| **T8** | Test both CRLF defences. | `HeaderInjectionIT` writing a hostile row directly via JDBC. | **GENERATED** | Accepted. Testing the two defences together would prove neither: creation-time rejection alone never asks the emission path whether it is safe, and the emission path is what remains if a row reaches storage by migration, bulk import or manual correction. Includes a benign-row control, without which an implementation that refused *every* redirect would pass the injection test perfectly while being entirely broken. | 7/7. | Engineer approved. |
| **T8** | Assert redirect semantics end to end. | `TestRestTemplate` for redirect assertions. | **REJECTED** | **It follows redirects.** Every such assertion was therefore describing example.com's response rather than this service's, with `Location` already consumed by the client. Surfaced as `IllegalArgumentException: Illegal character in path` — the client trying to parse the CRLF payload so it could go and fetch it. Extracted `NonFollowingClient`. A test that appears to assert on a redirect while actually asserting on its target is worse than no test, because it reports green. | 232/232 after. | Engineer approved. |
| **T8** | Populate the traceability matrix. | `validation.md` from real results. | **EDITED** | Added a section the draft lacked: **bugs found by testing rather than review**, listing seven defects that passed my own code review and were caught only by something executing. That section is the honest measure of whether the suite earns its cost — a traceability table alone shows only that tests exist. | — | Engineer approved. |
| **T9** | Verify dependency versions. | Maven Central **search API** as the source of truth. | **REJECTED** | The search API reported Spring Boot 3.5.3 as the newest release. The authoritative `maven-metadata.xml` on repo1 lists **thirteen** newer patches, up to 3.5.16. The version this project was pinned to at spec time — chosen from that same stale index — carried CVEs fixed months earlier. **Checking a version against a search index is not verification**, and the constitution's rule about verifying AI-proposed dependencies was satisfied in letter while failing in substance |
| **T9** | Triage scan findings. | Upgrade rather than document. | **EDITED** | 22 HIGH/CRITICAL dependency findings. Draft response was to record them with rationale, which the acceptance criteria technically permit. Rejected: fixes existed and were one version bump away, and "documented with rationale" is for findings that *cannot* be fixed, not for ones that are merely inconvenient. Spring Boot → 3.5.16 cleared 21; the PostgreSQL driver needed an explicit override past Boot's managed version to clear CVE-2026-54291, a SCRAM downgrade defeating the driver's MITM protection. **Result: 0 HIGH/CRITICAL, 0 secrets** |
| **T9** | Container image scan. | `apk --no-cache upgrade` in the runtime stage. | **EDITED** | Four HIGH CVEs in the base image's OS packages — `libexpat`, `p11-kit` — none in this code. "We use an official base image" is not a security position: base images lag their distributions by design. Patching the layer costs reproducibility, since the package set now depends on build time, and that cost is stated in the Dockerfile rather than left for someone to discover. A reproducible image full of known-vulnerable libraries is reproducibly vulnerable. **0 HIGH/CRITICAL after** |
| **T9** | Static analysis. | SpotBugs at HIGH, bound to `verify`. | **GENERATED** | Accepted. Article VI listed static analysis as a merge gate and the build had never run it — a gate that existed only in a document. Threshold set to HIGH deliberately: a wall of low-confidence findings is how a static-analysis gate gets disabled within a month. 0 findings |
| **T9** | Measure hot-key contention (NFR-08). | Single load run per scenario. | **REJECTED** | The first run gave spread p95 55.7 ms. A later run of **identical code** gave 507 ms — a 9× swing caused entirely by unrelated desktop applications, while the service itself used 7.6 % CPU. A single run would have been reported as a result and would have been meaningless. Ran three times with host load recorded alongside, and reported the **ratio**: hot ÷ spread p95 held at 1.83×, 2.17×, 2.14× while absolutes moved 9×. The comparison survives conditions that destroy the individual measurements, which is the only honest thing a laptop can offer |
| **T9** | Same. | k6 latency thresholds. | **REJECTED** | Gating on a latency number would encode a production claim this environment cannot support — precisely what the v1.1 constitutional amendment was written to prevent. Thresholds gate error rate only; latency is reported with the host load beside it, because a figure without that context is not reproducible |
| **B1** | Impact analysis from the committed code, not recollection. | Module/data-flow/test blast-radius tables. | **EDITED** | Correct in every respect but one: it claimed BC-5 would hold with *zero* Greenfield test edits. The `Link` record overload did protect all three `new Link(...)` sites, but `LinkRepository.insert` and the use-case constructors cannot be rescued that way — a fake must implement a new abstract method, and a convenience constructor would have hidden the very `TimeSource` dependency the change exists to expose. Corrected in place with the reasoning, rather than the sentence being quietly fixed. | 3 files edited, wiring only. | Engineer approved. |
| **B3** | Expand-only migration. | `V2__add_expires_at.sql`. | **GENERATED** | Accepted. Nullable, no default, no backfill, no down-step. `NULL` meaning "never expires" is what makes existing rows correct without touching them; a `NOT NULL` column with a far-future sentinel would have rewritten every row and left the schema lying about the domain. No index: nothing queries by this column, and an index would add write cost on every insert to serve a query nobody makes. | Rehearsed against a real database. | Engineer approved. |
| **B4** | Make the expiry boundary deterministic. | `TimeSource` port + `LinkLifecycle` as a pure function. | **EDITED** | Draft read `Instant.now()` inside the rule. Rejected for two reasons that both bite in production: the only interesting cases are *at* the boundary and could then be tested solely by sleeping, and several stateless instances reading their own clocks would resolve a near-expiry link on one and `410` it on the next. Also pinned the boundary as **inclusive** — "expires at midnight" means it stops at midnight — because that is exactly the detail that gets decided by accident in an `if` and then disagrees with the documentation forever. | `LinkLifecycleTest` 7/7, no sleeps. | Engineer approved. |
| **B6** | Enforce expiry on resolve. | Lifecycle check placed between verified lookup and counter increment. | **GENERATED** | Accepted, and the position is the decision. Above the lookup, an expired link becomes indistinguishable from an unknown one and `410` collapses into `404`. Below the increment, redirects that never happened get counted — inflating the figure for precisely the finished campaigns most likely to be examined. | `LinkExpiryIT` 12/12. | Engineer approved. |
| **B7** | Test expiry end to end. | Create a link then wait for it to expire. | **REJECTED** | Slow and flaky, and impossible through the API anyway since BF-02 refuses a past expiry. Replaced with rows seeded directly via JDBC — which is also the more faithful test, being exactly the state the database is in when a link created last month expires today. | 19 new tests, no sleeps. | Engineer approved. |
| **B8** | Prove the rollback claim. | "Structurally sound; the old app never selects the new column." | **REJECTED** | Reasoning, not evidence — and it was listed as *not done* rather than dressed up. Executed instead: the pre-change jar was built from `f3be7a6` and run against the migrated schema. It started, resolved both links, and created new ones. The expiring link resolved as non-expiring, which is the accepted consequence the analysis had predicted. | Rehearsal transcript in `validation.md` §7. | Engineer approved. |
| **B8** | Run the rehearsal. | First three attempts. | **REJECTED** | All three were talking to the wrong database and reported `role "smartlink" does not exist`, which points nowhere near the cause. A **native PostgreSQL on the host** held `127.0.0.1:5432` and shadowed the container's published port; separately a **stale Docker volume** from a differently named compose project retained old credentials, since `POSTGRES_*` is honoured only when initialising an empty data directory. Both recorded — each would have produced a confidently wrong result, and the second nearly did. | Rehearsal re-run on port 5433. | Engineer approved. |
| **T10** | Promote `architecture-overview.md` to the final artifact. | Rewritten from the built system. | **EDITED** | Written by reading the code rather than the plan, then diffed against the design intent. Added a section the draft lacked — **where the design changed under contact** — naming the three places the plan did not survive: readiness not consulting the database, `@Transactional` unable to implement insert-and-retry, and `CannotCreateTransactionException` not being a `DataAccessException`. An architecture document that records only what was intended is a design document wearing the wrong title |
| **T10** | Final accuracy pass. | README as written at scaffold time. | **REJECTED** | Three defects, one of them substantive. The demo path told a reviewer to `curl localhost:8080/aB92xK7` — a hard-coded code that returns 404 for anyone who copy-pastes it, so **the first command in the quick start was broken**. The create/resolve table still described creation as "authenticated, few / known key holder", directly contradicting GF-03 and the generated OpenAPI description. And the testing table referenced "alias policy", a feature removed at requirements revision 2. Documentation drifts silently because nothing compiles it |
| **T10** | Report ledger statistics in the final summary. | "40+ entries, 16 rejections", written from memory. | **REJECTED** | Counted rather than estimated: **69 entries — 12 generated, 33 edited, 23 rejected**. *(Later found to be wrong as well — see the final row of this table.)* A summary claiming methodological rigour is the worst possible place to assert a number nobody checked, and the same laziness had already produced the T9 finding about verifying versions against a stale index |
| **T10** | Prove NFR-12. | Clean-clone rehearsal. | **GENERATED** | Accepted. Cloned to an empty directory and ran the documented commands verbatim: 100 files, no secrets or build output committed, `mvn verify` 232/232 from scratch, compose ready in 61 s, smoke 25/25, README demo output matching the documentation exactly. "It works on the machine it was written on" is the one claim a submission cannot afford to be wrong about |
| **T2** | Publish OpenAPI metadata. | `OpenApiConfig`. | **GENERATED** | Accepted. Metadata only — operations and schemas derive from controllers and DTOs so the published contract cannot drift from the running service. The anonymous-access boundary is stated in the document a reviewer actually opens, so a deliberate prototype decision is not mistaken for a missing control. | `/v3/api-docs` served, asserted in `SmartLinkApplicationIT`. | Engineer approved. |
| **Review** | Resolve reviewer finding P1: the clock contradicts A-12. | First response: defend `Clock.systemUTC()` as "testable and standard". | **REJECTED** | The finding was correct and the defence was an argument for the wrong property. A-12 asks for **one authoritative clock across instances**; `Clock.systemUTC()` gives each instance its own. ADR-011 had *named this exact failure mode in its own justification* and then implemented it anyway — testability quietly substituted for the correctness guarantee that was actually approved. The rule this establishes: when a requirement and an implementation disagree, the requirement wins until it is formally amended, and "the tests pass" is not a rebuttal. | Reimplemented on the database clock; `ADR-012` supersedes `ADR-011`. | Engineer approved. |
| **Review** | Implement the database clock without slowing the redirect path. | Proposal: call `select CURRENT_TIMESTAMP`, then query the row. | **REJECTED** | Two round trips on the only path that carries load, to fix a correctness bug that costs nothing to fix properly. Replaced with a native projection selecting the clock **alongside** the row — same query, no added latency. The generated approach was correct and would have made the hot path 2× chattier forever; correctness here was about *where* the value was read, not how much work was done. | `LinkExpiryIT` 14/14; hot path unchanged in query count. | Engineer approved. |
| **Review** | Explain why 251 tests passed over P1. | "Edge case not covered." | **REJECTED** | Not an edge case — a **structural blind spot**. Every test injected a fixed clock, so the suite asserted the port was *used* and never that the clock behind it was *shared*. A test that supplies the dependency under verification cannot see this class of defect, and no amount of additional cases of that shape would have found it. Recorded because the generic phrasing would have hidden the actual lesson. | Recorded in `impact-analysis.md` §8 and ADR-012. | Engineer approved. |
| **Review** | Resolve P2a: `410` absent from the published OpenAPI document. | `@ApiResponse(responseCode = "410", ...)` on `RedirectController`. | **EDITED** | The annotation alone repeats the original failure — the contract drifted precisely because nothing asserted it. Added `openApiDocumentsExpiredResponse`, which fetches `/v3/api-docs` and asserts `410` is present. A repository that designates the generated document as authoritative has to test that document, not just annotate the code behind it. | `SmartLinkApplicationIT`; `/v3/api-docs` confirmed to contain `410`. | Engineer approved. |
| **Review** | Resolve P2b: malformed expiry returns `MALFORMED_REQUEST`, not `INVALID_EXPIRY`. | Change `CreateLinkRequest.expiresAt` from `Instant` to `String`, parse in the use case. | **EDITED** | The fix was right; the accompanying claim that "the existing test covers this" was wrong and worth recording. `malformedExpiryIsRefused` asserted `isIn(BAD_REQUEST, UNPROCESSABLE_ENTITY)` and nothing about the body — **permissive enough to pass under either design**, which is why it reported green while the contract was wrong. Tightened to exactly `400` **and** a body containing `INVALID_EXPIRY`, plus a fourth case (`2026-13-45T00:00:00Z`: well-formed shape, impossible date). | 4 cases, all asserting the code. | Engineer approved. |
| **Review** | Verify the P1 fix end to end. | Suite result: 252 passing, reported as done. | **REJECTED** | Reported complete without running the **demo profile**, which the P1 fix had just broken. `statement_timestamp()` is PostgreSQL-only: every redirect under `h2` returned `503`. Fixing that exposed a second layer — the projection declared `Instant`, satisfied by PostgreSQL's `java.sql.Timestamp` and not by H2's `OffsetDateTime` — and the same function appeared a *second* time in `DatabaseTimeSource`, so every create carrying an expiry `503`ed too. **Three defects, none visible to 252 passing tests.** Found by starting the jar and calling it by hand. | Fixed with standard-SQL `CURRENT_TIMESTAMP` and `JdbcInstants`; verified live on H2. | Engineer approved. |
| **Review** | Prevent the demo-profile defects from recurring. | Suggestion: "note the limitation in the README". | **REJECTED** | A note in a README is not a guarantee; it is a request that the next person be careful. The demo profile is the first thing a reviewer without Docker runs and it had **zero automated coverage** — that gap, not H2, was the defect. Added `DemoProfileIT`: 6 tests covering create, resolve, future expiry, `410`, analytics/redirect clock agreement and migration portability, **needing no Docker to run**. | 6/6 pass; total 258. | Engineer approved. |
| **Review** | Diagnose a sudden 74-error `mvn verify`. | Implicit assumption that the P1 changes had regressed. | **REJECTED** | Not one error was a code defect. The Docker daemon had died mid-session; `AbstractPostgresIT` could not initialise, and the same build passed unchanged after `colima restart`. Reading one stack trace settled it in seconds. Recorded because the shape is diagnostic and easy to misread: **a whole-suite failure arriving at class-initialisation, in milliseconds per test, is infrastructure — not regression.** | `mvn verify` 258/258 after restart. | Engineer approved. |
| **Review** | Report the ledger's statistics. | The figures already in the final summary: "79 entries — 15 generated, 36 edited, 28 rejected". | **REJECTED** | **Wrong, and wrong in the way this ledger already has an entry about.** Counted by script: **62 entries — 12 generated, 24 edited, 26 rejected**, this row included. (The first count said 61 and was stale the moment this row was written, which is a small joke at the method's expense and also the reason the count is a command rather than a constant.) The T10 entry above claims to have *counted* 69 and did not; the summary then asserted 79, which matches nothing. Twice now the same failure — asserting a checkable number without checking it — and the second time it survived inside a document whose entire subject is verification discipline. That is the honest shape of the finding: the discipline was written down, the ledger flagged the failure once, and it still recurred. What actually fixes it is a command, not an intention: the counts are now produced by parsing the table, and any future claim should be regenerated the same way rather than carried forward. | Script output over the committed table. | Engineer approved. |
| **A1** | Establish the reliability baseline before proposing anything. | A gap list implying reliability work had not been done. | **REJECTED** | Overstated the gap, which is the characteristic failure of a reliability scenario running on a system that already had reliability work. Corrected against the committed configuration: **R-1 and R-2 were already met** (readiness includes `db`; safe `503` with four assertions in `DependencyOutageIT`), R-3 and R-4 were half-met, only R-5 and R-6 were absent. The scenario's deliverable is the reasoning, and reasoning that opens by inflating the problem is worth nothing. | Baseline table in `validation.md` §5, each row traced to a file. | Engineer approved. |
| **A3** | Build a fault harness for a *slow* database, before changing any timeout. | Proposal to add the statement timeout first and test it after. | **REJECTED** | Inverts the ordering rule the task decomposition states explicitly, and the rule is not ceremony: a timeout that has never been observed to fire is indistinguishable from one that does not work. Harness written first and run against the unmodified service — which is the only reason the retry amplification below was ever seen. | `SlowDependencyIT` failing on the pre-change build: >20 s single request, 131 s for 12 concurrent. | Engineer approved. |
| **A3** | Inject a slow dependency deterministically without adding a dependency. | Suggestion: mock the repository to return late. | **REJECTED** | Would exercise a sleep in the test JVM and prove nothing about whether the *database* interaction is bounded — the thing under test. Replaced with a PostgreSQL view over the real table whose `WHERE` clause calls `pg_sleep`, so every read blocks on a real connection in a real query. Consistent with the trigger-based injection `AnalyticsFailureIT` already uses, and needs no new library. | 4 fault-injection tests; teardown restores the table. | Engineer approved. |
| **A5** | Bound the time any database statement may take. | `SET statement_timeout` in `application.yml`. | **EDITED** | Correct for PostgreSQL and would have broken the H2 demo profile for the third time — exactly the trap ADR-012 was written about six commits earlier. Added the H2 spelling (`SET QUERY_TIMEOUT`) as a profile override, and `DemoProfileIT` is what makes that claim checkable rather than hopeful. Knowing the lesson was not enough; the test is what applies it. | `DemoProfileIT` 6/6 on H2; `SlowDependencyIT` 4/4 on PostgreSQL. | Engineer approved. |
| **A5** | Explain the >20 s measurement on a 10 s injected query. | "Probably JVM warm-up or container noise." | **REJECTED** | Not noise — the service waited for the query **twice**. `QueryTimeoutException` extends `TransientDataAccessException`, so the retry classifier sent the same expensive query back to a database that had just proved it could not keep up. `TransientFailures` carried a Javadoc warning about precisely this over-inclusion and did it anyway, because nothing had ever produced a timeout to check the comment against. Attributing a 2× measurement to noise would have buried a real load-amplification bug. | Timeout excluded from retry; single request 20 s → 2 s. ADR-013. | Engineer approved. |
| **A6** | Test graceful shutdown. | First version closed the `@SpringBootTest` context from inside a test method. | **REJECTED** | The assertions all passed and the test still reported two errors, because Spring's test listeners then ran against a closed context. A test that reports failure while proving its point is worse than no test — the next person deletes it or, worse, learns to ignore red. Rewritten to boot and stop its own application instance, which is also a closer analogue of a deployment. | `GracefulShutdownIT` 2/2, clean. | Engineer approved. |
| **A6** | Point the self-booted instance at the test container. | `SpringApplicationBuilder.properties(...)`. | **REJECTED** | Silently ignored: `.properties()` registers *default* properties, the lowest-precedence source there is, so `application.yml`'s `${SMARTLINK_DB_USER:smartlink}` won and the application tried to connect as a role the container has never heard of. Switched to command-line arguments. Worth recording because the symptom — `role "smartlink" does not exist` — points at credentials and the cause was precedence. | Test starts and connects. | Engineer approved. |
| **A3** | Prove readiness recovers, not merely that it goes DOWN. | Reuse `DependencyOutageIT`'s approach — point at a dead port. | **REJECTED** | That outage has no end, so it can only ever prove half of R-1, and the untested half is the one operators care about: an instance that reports itself unready and never recovers needs a human at 3am. Restarting the container was also refused — a restarted container gets a new mapped port, so the application would be recovering to an address that no longer exists. Wrote a small TCP proxy that can be cut and restored, keeping the configured address stable across the outage. | `ReadinessRecoveryIT` 2/2; readiness UP again well inside the 10 s target, no restart. | Engineer approved. |
| **A4** | Deliver the SLI/SLO section. | The SLO table alone, as specified. | **EDITED** | The table as written was undeliverable: nothing emitted the numbers it was defined over. An SLO table with no telemetry behind it reads as rigour and delivers nothing, which is worse than an admitted gap because it stops anyone looking. Added the `metrics` endpoint and a `smartlink.analytics.write.failures` counter — the fail-open path previously emitted a WARN log, and nobody computes a rate from log lines during an incident. | `ReliabilitySignalsIT` asserts the tags the SLI definitions split by actually exist. | Engineer approved. |
| **A4** | Expose metrics for R-5. | Add `metrics` to the actuator exposure list. | **EDITED** | Accepted with two guards the proposal lacked. Metrics are a plausible new leak path for the destination URLs that NFR-14 keeps out of logs, and a plausible way to widen an actuator surface this repository has kept deliberately narrow. Both are now asserted, and the production caveat — unauthenticated request metrics disclose traffic shape and error rates — is written into the config and the runbook rather than assumed. | `ReliabilitySignalsIT`: no destination in metrics, `env`/`beans`/`heapdump` still 404. | Engineer approved. |
| **A7** | Resolve three failing Greenfield retry tests. | Update the assertions to match the new behaviour. | **EDITED** | Right for one of them and wrong for the other two, and the difference is the whole point of A7's constraint. `BoundedRetryTest` genuinely *asserted that a query timeout is retried* — a real behaviour change, so the case moved to the non-retryable set **with its reason attached** rather than being quietly deleted. The other two only used `QueryTimeoutException` as a stand-in for "some transient failure" while testing retry *mechanics*; those were switched to another transient type so they assert exactly what they did before. Treating all three the same would have hidden one decision inside two cosmetic edits. | 270/270; the change documented in `validation.md` §7. | Engineer approved. |

Entries are written **as work lands, never reconstructed afterwards**. A ledger assembled at
the end records what someone remembers deciding, which is a different and much more flattering
thing than what they actually decided.

## 4. Secure AI usage

Article VII in practice:

- **No secret ever entered a prompt.** `.env.example` contains placeholders only; every
  credential in a committed file is a throwaway for a loopback-bound container.
- **Every AI-proposed dependency was verified to exist** on Maven Central before adoption
  (L-001), rather than trusted because it looked plausible.
- **Security-relevant generated code is reviewed, not merely tested.** Destination
  validation, API-key comparison and error rendering are reviewed against OWASP guidance,
  because tests confirm the cases you imagined and review catches the ones you did not.
- **Generated code is assumed to reflect common patterns in training data — which includes
  common vulnerabilities.** Plausibility is not correctness.
