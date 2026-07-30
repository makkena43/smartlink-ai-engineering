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
| Integration tests | Testcontainers (real PostgreSQL) | 100 % pass |
| Coverage | JaCoCo, on domain and service layers | ≥ 85 % line, ≥ 75 % branch |
| Dependency security | OWASP Dependency-Check | Zero CVSS ≥ 7 |
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

| L-009 | `01-greenfield/requirements.md` rev 2 — gap analysis | **PENDING** | Engineer-authored requirements (GF-01…GF-13, NFR-01…NFR-13) were reviewed for cases a compliant implementation could satisfy while still being wrong. Eight additions proposed: private/metadata address ranges (GF-14), destination length bound (GF-15), routing precedence (GF-16), analytics fail-open (GF-17), correlation ID (GF-18), log hygiene (NFR-14), enumeration resistance (NFR-15), no code reassignment (NFR-16). **Not yet classified — each stands or falls at Gate A, individually.** The strongest is GF-14: `http://169.254.169.254/` is well-formed and uses a supported scheme, so GF-10 as written permits it |
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
| **T2** | Publish OpenAPI metadata. | `OpenApiConfig`. | **GENERATED** | Accepted. Metadata only — operations and schemas derive from controllers and DTOs so the published contract cannot drift from the running service. The anonymous-access boundary is stated in the document a reviewer actually opens, so a deliberate prototype decision is not mistaken for a missing control. | `/v3/api-docs` served, asserted in `SmartLinkApplicationIT`. | Engineer approved. |

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
