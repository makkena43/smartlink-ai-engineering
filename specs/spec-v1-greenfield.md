# Spec v1 — Core Shortening and Basic Analytics (Greenfield)

- **Scenario:** Greenfield — new system, nothing to preserve
- **Requirement as received:** *"Build a URL shortener with redirect and basic analytics."*
- **Status:** Draft — awaiting Gate A
- **Engineer of record:** Srinivas Makkena
- **Governing document:** [`docs/constitution.md`](../docs/constitution.md)

> **Article I.2 compliance.** This document states *what* and *why*. It names no framework,
> library, class or table. Those belong in `docs/architecture.md` and `docs/decisions.md`,
> and are settled only after the problem below is agreed to be the right problem.

---

## 1. Intent

Exchange a long URL for a short, durable, publicly resolvable identifier; resolve that
identifier back to its destination fast enough to sit in the hot path of a user's
navigation; and record enough about each resolution to answer *"is this link being used?"*

The value is not string compression. It is that **the short link becomes the stable public
handle for a destination** — printed, pasted into messages, embedded in campaigns — while
the system retains the ability to observe what happens when it is used. Durability, because
printed links outlive deploys. Latency, because a redirect sits between a click and the
thing the user actually wanted. Observability, because a link nobody can measure is a link
nobody can justify.

---

## 2. Scope boundary across the three scenarios

One evolving codebase, three changes in requirement. This spec is **v1 only**.

| Version | Requirement | Why it sits here |
|---|---|---|
| **v1 — Greenfield** *(this spec)* | Create, redirect, basic analytics, validation | Nothing exists; the work is design from first principles |
| **v2 — Brownfield** | *"Add expiration so campaigns can stop redirecting after a defined time."* | Touches schema, creation API, redirect logic, migration, backward compatibility, docs and tests — real codebase reasoning without inventing a second product |
| **v3 — Ambiguous** | *"Improve reliability."* | Underspecified by construction; the engineering work is the disambiguation |

**v1 must not pre-build v2 or v3.** Where a v1 decision constrains them, it is recorded in
§4 rather than silently resolved.

---

## 3. Actors

| Actor | Need | Consequence for design |
|---|---|---|
| **API consumer** (campaign tool, internal service) | Create links programmatically and retry safely | Machine-readable errors; explicit idempotency |
| **Link visitor** (public, untrusted, highest volume) | Reach the destination immediately | Latency budget; no auth on resolve; hostile input assumed |
| **Link owner** | Know whether the link is being used | Analytics readable per link, owner-scoped |
| **Operator** | Know the service is healthy; diagnose failures | Health, readiness, correlation IDs, structured logs |

The visitor is the highest-volume and least-trusted actor. The read path and the write path
therefore carry different security postures and very different load profiles — the single
most load-bearing fact in `docs/architecture.md`.

---

## 4. Ambiguity Register

Per Constitution Article II. Each item was genuinely open in the one-sentence requirement.
Each resolution carries rationale, blast radius, and reversibility.

---

### A-01 — Which redirect status code?

Both 301 (Permanent) and 302 (Found) are used by real shorteners, with opposite
consequences.

| Reading | Consequence |
|---|---|
| **301 Permanent** | Browsers and intermediaries cache aggressively and **stop contacting the service**. Fastest for repeat visitors. |
| **302 Found** | Every click reaches the service. |
| 307 / 308 | Method-preserving variants; irrelevant, resolution is GET-only. |

**Chosen: 302 Found, with `Cache-Control: no-store` on the redirect.**

**Rationale.** The requirement asks for analytics *in the same sentence* as redirect. A 301
makes analytics structurally incomplete: once cached, repeat clicks never reach the service,
and the undercount is unbounded and unknowable — you cannot even measure how much you are
missing. A 301 is also functionally irreversible in the wild, because cached responses
cannot be recalled. Going 302 → 301 later is a config change; going 301 → 302 is impossible
for clients that already cached. **That asymmetry decides it**, not the latency difference.

Speed is instead recovered server-side, where it stays under our control.

**Blast radius if wrong:** more origin traffic, marginally slower repeat clicks.
**Reversibility:** *Reversible* toward 301. *One-way* in the other direction.

---

### A-02 — Does shortening the same URL twice return the same code?

**Chosen: no implicit deduplication. Each creation yields a new code. Retry-safety comes
from an explicit idempotency key.**

**Rationale.** Implicit dedup silently makes two independent links share a fate: two
campaigns pointing at the same landing page would merge into one analytics bucket, and the
merge is **not separable afterwards** — the per-campaign data was never recorded. Since this
spec introduces analytics in v1, that loss is immediate rather than theoretical. Explicit
idempotency delivers the real underlying requirement (safe retries) without conflating
distinct intents.

**Blast radius:** marginally more storage. **Reversibility:** *Reversible.*

---

### A-03 — What does "basic analytics" mean?

The requirement says "basic" without saying what is counted or how it is read.

**Chosen for v1: per-link aggregate counters — total resolutions, first resolution instant,
most recent resolution instant — readable by the link owner. No per-click event rows.**

**Rationale.** "Basic" is scoped to the question an owner actually asks first: *is this link
being used, and recently?* Aggregates answer that. Per-click event storage is what enables
the questions that come later (referrer, device, geography, time series), and each of those
is a product decision carrying its own privacy weight. Recording events "just in case" means
collecting personal data before deciding it is needed — the wrong default.

**Reversibility:** *Reversible* in the direction that matters. Aggregates can be enriched
later; data never collected cannot be recovered retroactively — which is exactly why the
conservative choice is the correct default. Recorded in `docs/tradeoffs.md`.

---

### A-04 — What personal data may analytics retain?

Not asked by the requirement. Answered anyway, because silence here defaults to collecting
whatever is convenient.

**Chosen: no client IP address, no user-agent string, no referrer persisted in v1. Counters
only.**

**Rationale.** A resolution request carries directly identifying data. Persisting it turns a
link shortener into a behavioural tracking system and pulls the service into data-protection
obligations — retention limits, subject access, deletion — that nothing in the requirement
asked for and that no part of this design is built to honour. In a financial-services
context that is a compliance surface acquired by accident.

When richer analytics are genuinely needed, the privacy decision gets made deliberately,
with retention and minimisation designed in.

**Reversibility:** *Reversible* as chosen. **One-way if decided the other way**, since data
collected under an unclear basis cannot be un-collected. Escalated per Article II.3.

---

### A-05 — Must analytics recording succeed for a redirect to succeed?

**Chosen: no. Analytics fails open — a failure in the counter path is logged and swallowed;
the redirect still happens.**

**Rationale.** The visitor's need (§3) is to reach their destination. Failing a redirect
because a counter could not be written serves nobody: the user is blocked from a page that
is perfectly available, in order to protect a number.

Counting is synchronous in v1. The trade-off — added work on the read path, hot-row
contention under concentrated load — is accepted knowingly and recorded in
`docs/tradeoffs.md`, with asynchronous events as the documented evolution.

**Verification:** asserted by a fault-injection test, not by convention (AC-5.4).

---

### A-06 — Are custom aliases allowed, and what may they contain?

**Chosen: allowed. 3–32 characters, `[A-Za-z0-9_-]`, case-sensitive, drawn from a namespace
disjoint from generated codes, with a reserved-word denylist.**

**Rationale.** Custom aliases and generated codes competing for one namespace creates both a
race and an enumeration oracle — a caller could probe which codes exist by attempting to
claim them. Disjoint namespaces remove the interaction entirely rather than mitigating it.
Reserved words protect routing prefixes (`api`, `health`, `actuator`, `admin`) and reduce
impersonation (`login`, `verify`, `secure`).

Case sensitivity is retained for namespace density; the usability cost (links dictated
aloud) is accepted and listed in `docs/tradeoffs.md`.

---

### A-07 — Which destinations are acceptable?

"URL" is far broader than what is safe to redirect to.

**Chosen: `http` and `https` only; public destinations only. Rejected: every other scheme —
notably `javascript:`, `data:`, `file:` — and hosts resolving to private, loopback,
link-local or cloud-metadata address ranges.**

**Rationale.** A shortener is an open redirector by construction; that is its function, not
a defect. But without scheme restriction it also becomes a **stored-XSS delivery mechanism**
— a `javascript:` payload behind a link the user was told to trust. Without address
restriction it becomes an **SSRF pivot** the moment any server-side component fetches the
target, which link-preview or metadata enrichment plausibly will. Restricting at creation
costs little now and is expensive to retrofit once such a component exists.

Validation must resist bypass by IP-encoding tricks (decimal, octal, IPv6-mapped).

**Reversibility:** *Reversible* to widen; costly to narrow.

---

### A-08 — Who may create links?

**Chosen: creation requires an API key; resolution is anonymous.**

**Rationale.** An anonymous creation endpoint on a public shortener is an abuse magnet —
phishing, malware, spam — with no attribution with which to respond. A key gives
owner-scoped analytics reads (FR-5) and gives v3 a subject for rate limiting. Resolution
stays anonymous because that is the product.

Keys are seeded configuration, not a user-management system (§7).

---

### A-09 — May a code be reused after a link is removed?

Removal is **not** in v1 scope. Recorded here because the v1 data model either permits code
reuse or forecloses it, and that is decided now whether or not it is discussed.

**Chosen: codes are permanently retired. Any future removal is a tombstone, never a row
deletion.**

**Rationale.** A printed or messaged link outlives the service's memory of it. If `abc123`
can be reissued to a new owner, every historical holder of that link is silently redirected
to a destination chosen by somebody else — a link-hijacking primitive. Storage cost is
trivial against that.

**Reversibility:** *One-way door.* Escalated per Article II.3.

---

### A-10 — What does "reliability" mean?

**Deferred to v3**, the designated ambiguous scenario. v1 commits only to the design targets
in §6 and the failure posture in AC-6.4.

---

## 5. Functional requirements

Each ID is used for forward and backward traceability (Article V.2).

### FR-1 — Create a short link
Given a valid destination, return a short code and its full short URL. Optional inputs:
custom alias, idempotency key.

- **AC-1.1** Valid `https` destination → `201 Created` with code, short URL, destination, creation instant.
- **AC-1.2** Response carries a `Location` header for the created resource.
- **AC-1.3** Same idempotency key + identical body → identical response; exactly one link exists.
- **AC-1.4** Same idempotency key + different body → `409 Conflict`; original unmodified.
- **AC-1.5** Generated codes are 7 characters over a 62-symbol alphabet and are not derivable from an adjacent code.
- **AC-1.6** Missing or invalid API key → `401`.

### FR-2 — Resolve a short link
- **AC-2.1** Known code → `302` with `Location` = exact stored destination.
- **AC-2.2** Redirect carries `Cache-Control: no-store` (A-01).
- **AC-2.3** Unknown code → `404`.
- **AC-2.4** Destination returned byte-identical, including query string and fragment.
- **AC-2.5** Resolution requires no authentication.

### FR-3 — Custom alias
- **AC-3.1** Available, well-formed alias → `201`, alias used verbatim.
- **AC-3.2** Already-claimed alias → `409`.
- **AC-3.3** Reserved word → `422` naming the violated rule.
- **AC-3.4** Malformed alias (length or charset) → `422`.
- **AC-3.5** A custom alias can never collide with a generated code (A-06).

### FR-4 — Destination validation
- **AC-4.1** Non-`http(s)` scheme → `422`.
- **AC-4.2** Host resolving to private / loopback / link-local / metadata ranges → `422`.
- **AC-4.3** Decimal, octal and IPv6-mapped encodings of blocked addresses also rejected.
- **AC-4.4** Destination exceeding the documented maximum length → `422`.
- **AC-4.5** Rejections name the failed rule and never echo raw input back unescaped.

### FR-5 — Basic analytics
- **AC-5.1** Owner reads per-link total resolutions, first-resolution instant, last-resolution instant.
- **AC-5.2** A successful resolution increments the counter exactly once.
- **AC-5.3** A non-owner key reading another owner's link → `404`, not `403`. Non-existence and non-ownership are deliberately indistinguishable, so the endpoint is not an enumeration oracle.
- **AC-5.4** **When the analytics write fails, the redirect still returns `302` with the correct `Location`** (A-05). Proven by fault injection, not by inspection.
- **AC-5.5** Concurrent resolutions of one code do not lose counts.

### FR-6 — Operability
- **AC-6.1** Liveness reflects process health only.
- **AC-6.2** Readiness fails when a required dependency is unavailable.
- **AC-6.3** Every response carries a correlation ID, echoed from the caller when supplied.
- **AC-6.4** When the datastore is unavailable, resolution returns a clear `503`. It never guesses, and never redirects to a wrong or stale destination.
- **AC-6.5** Logs are structured and contain no full destination URL and no API key at INFO or below — destinations are user-supplied and query strings routinely carry secrets.
- **AC-6.6** API documentation is generated from the implementation, not hand-maintained.

---

## 6. Design targets (SLI / SLO)

**These are design targets and discussion points. They are not contractual SLAs, and a
prototype on a laptop proves none of them.** Measurements are reported with method, machine
and sample size stated, and with no extrapolation to production scale.

| Signal | SLI | Production target | What v1 actually demonstrates |
|---|---|---|---|
| Redirect availability | successful redirects / eligible redirect requests | 99.9 % monthly | Correct error semantics and health behaviour — **not** multi-AZ availability |
| Redirect latency | p95 server-side resolution | < 100 ms | Local measurement, environment stated |
| Creation latency | p95 `POST /links` | < 250 ms | Functional response plus modest local load |
| Error rate | 5xx / total | < 0.1 % over the window | Errors surfaced clearly, users never misdirected |

### Non-functional requirements actually verifiable in v1

| ID | Requirement | Verified by |
|---|---|---|
| **NFR-1** | Redirect survives analytics failure | Fault-injection test (AC-5.4) |
| **NFR-2** | Resolution returns 503, never a wrong destination, when the datastore is down | Fault-injection test (AC-6.4) |
| **NFR-3** | No destination URL or API key in logs at INFO or below | Log assertion test |
| **NFR-4** | No node-local state on the read path — horizontally scalable by construction | Architecture review; stateless by design |
| **NFR-5** | Behaviour on code collision is correct regardless of probability | Forced-collision test + analysis in `docs/decisions.md` |
| **NFR-6** | Domain and application layers ≥ 85 % line coverage | Coverage gate |

NFR-5 is deliberately phrased around *behaviour on collision* rather than its probability.
Probability arguments fail silently; a test that forces a collision and asserts correct
recovery does not.

---

## 7. Out of scope for v1

Named so that absence reads as decision, not oversight.

- **Expiration** — v2, by design.
- **Reliability hardening, SLO instrumentation, runbook** — v3, by design.
- User accounts, sessions, OAuth. API keys are seeded configuration; key *issuance* is a product concern.
- Link removal / revocation — deferred, though its data-model consequence is settled in A-09.
- Link editing (mutating a destination) — deliberately excluded. It defeats the "stable public handle" premise in §1 and is a hijacking vector.
- Caching tier. v1 reads from the system of record; a read-through cache with stampede protection is the documented evolution in `docs/tradeoffs.md`.
- Per-click event storage; referrer / device / geography breakdown — see A-03, A-04.
- Web UI, custom domains, QR codes, bulk import, A/B destination splitting.
- Rate limiting — v3.
- Multi-region replication.

---

## 8. Risks carried into planning

| Risk | Why it matters |
|---|---|
| Code generation strategy | Simultaneously determines collision behaviour, guessability and write throughput — settled in `docs/decisions.md` |
| Synchronous counter on the hot path | Accepted trade-off (A-05); hot-row contention is the known failure mode and must be measured, not assumed |
| Destination validation bypass | A-07 is only as strong as its resistance to encoding tricks and DNS rebinding |
| v2 expiry coupling | v1 must not encode "never expires" as an invariant v2 has to unpick — schema is additive-first |
| Analytics fail-open | Must be structural (AC-5.4), or a later refactor will quietly reintroduce coupling |

---

## 9. Definition of done

1. Every AC above is exercised by at least one automated test, traceably mapped in `docs/testing.md`.
2. All Article VI quality gates green.
3. Service runs end-to-end from a clean clone with one documented command.
4. API documentation generated from the implementation.
5. Decisions marked *costly* or *one-way* recorded in `docs/decisions.md`.
6. `docs/ai-usage.md` complete — including rejections with rationale.

---

## Gate A — approval required

Per Article VIII.3, planning may not begin until the engineer of record confirms:

- [ ] §1 states the right problem.
- [ ] The v1 / v2 / v3 boundary in §2 is correct, and v1 genuinely excludes expiry.
- [ ] Each resolution in §4 is defensible — **particularly A-01 (302), A-04 (no PII), and A-09 (no code reuse, one-way)**.
- [ ] §6 claims nothing a laptop can demonstrate.
- [ ] §7 is acceptable.
- [ ] The acceptance criteria are testable as written.

**Approved by:** _________________  **Date:** __________
