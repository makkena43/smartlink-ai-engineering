# Scenario 01 — Greenfield · Requirements

**Requirement as received:** *"Build a URL shortener with redirect and basic analytics."*

- **Scenario type:** Greenfield — new system, nothing to preserve
- **Status:** Draft — awaiting Gate A
- **Engineer of record:** Srinivas Makkena
- **Governs:** [`docs/ai-assisted-engineering.md`](../../ai-assisted-engineering.md)

> This document is **requirement understanding**: what is being built, for whom, and what
> the sentence above failed to say. It names no framework, library, class or table — those
> live in [`engineering-spec.md`](engineering-spec.md) and
> [`docs/decisions.md`](../../decisions.md), and are settled only once the problem here is
> agreed to be the right problem.

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

One evolving codebase, three changes in requirement. This document covers **v1 only**.

| Version | Requirement | Why it sits here |
|---|---|---|
| **v1 — Greenfield** *(this)* | Create, redirect, basic analytics, validation | Nothing exists; the work is design from first principles |
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
most load-bearing fact in [`architecture-overview.md`](../../architecture-overview.md).

---

## 4. Ambiguity Register

Ten items were genuinely open in that one sentence. Each resolution carries rationale, blast
radius and reversibility. Silent resolution is the failure mode this register exists to
prevent: a choice absorbed into an implementation becomes invisible in the diff and is
discovered only in production.

---

### A-01 — Which redirect status code?

Both 301 (Permanent) and 302 (Found) are used by real shorteners, with opposite
consequences.

| Reading | Consequence |
|---|---|
| **301 Permanent** | Browsers and intermediaries cache aggressively and **stop contacting the service**. Fastest for repeat visitors. |
| **302 Found** | Every click reaches the service. |
| 307 / 308 | Method-preserving variants; irrelevant, resolution is GET-only. |

**Chosen: 302 Found, with `Cache-Control: no-store`.**

**Rationale.** The requirement asks for analytics *in the same sentence* as redirect. A 301
makes analytics structurally incomplete: once cached, repeat clicks never reach the service,
and the undercount is unbounded and unknowable — you cannot even measure how much you are
missing. A 301 is also functionally irreversible in the wild, because cached responses
cannot be recalled. Going 302 → 301 later is a config change; going 301 → 302 is impossible
for clients that already cached. **That asymmetry decides it**, not the latency difference.
Speed is recovered server-side, where it stays under our control.

**Blast radius if wrong:** more origin traffic, marginally slower repeat clicks.
**Reversibility:** *Reversible* toward 301. *One-way* in the other direction. → ADR-001

---

### A-02 — Does shortening the same URL twice return the same code?

**Chosen: no implicit deduplication. Each creation yields a new code; retry-safety comes
from an explicit idempotency key.**

**Rationale.** Implicit dedup silently makes two independent links share a fate: two
campaigns pointing at the same landing page merge into one analytics bucket, and the merge
is **not separable afterwards** — the per-campaign data was never recorded. Since analytics
is in v1, that loss is immediate rather than theoretical. Explicit idempotency delivers the
real underlying requirement (safe retries) without conflating distinct intents.

**Reversibility:** *Reversible.*

---

### A-03 — What does "basic analytics" mean?

**Chosen for v1: per-link aggregate counters — total resolutions, first resolution instant,
most recent resolution instant — readable by the link owner. No per-click event rows.**

**Rationale.** "Basic" is scoped to the question an owner asks first: *is this link being
used, and recently?* Aggregates answer that. Per-click event storage is what enables the
questions that come later (referrer, device, geography, time series), and each carries its
own privacy weight. Recording events "just in case" means collecting personal data before
deciding it is needed — the wrong default.

**Reversibility:** *Reversible* in the direction that matters. Aggregates can be enriched
later; data never collected cannot be recovered retroactively, which is exactly why the
conservative choice is correct.

---

### A-04 — What personal data may analytics retain?

Not asked by the requirement. Answered anyway, because silence defaults to collecting
whatever is convenient.

**Chosen: no client IP, no user-agent, no referrer persisted in v1. Counters only.**

**Rationale.** A resolution request carries directly identifying data. Persisting it turns a
link shortener into a behavioural tracking system and pulls the service into
data-protection obligations — retention limits, subject access, deletion — that nothing
asked for and that no part of this design is built to honour. In a financial-services
context, that is a compliance surface acquired by accident.

**Reversibility:** *Reversible* as chosen. **One-way if decided the other way**, since data
collected under an unclear basis cannot be un-collected. Escalated and confirmed. → ADR-005

---

### A-05 — Must analytics recording succeed for a redirect to succeed?

**Chosen: no. Analytics fails open — a counter failure is logged and swallowed; the redirect
still happens.**

**Rationale.** The visitor's need is to reach their destination. Failing a redirect because
a counter could not be written serves nobody: the user is blocked from a page that is
perfectly available, in order to protect a number.

Counting is synchronous in v1. The trade-off — added work on the read path, hot-row
contention under concentrated load — is accepted knowingly, with asynchronous events as the
documented evolution. **Verified by fault injection (AC-5.4), not by convention.** → ADR-004

---

### A-06 — Are custom aliases allowed, and what may they contain?

**Chosen: allowed. 3–32 characters, `[A-Za-z0-9_-]`, case-sensitive, namespace disjoint from
generated codes, with a reserved-word denylist.**

**Rationale.** Aliases and generated codes competing for one namespace creates both a race
and an enumeration oracle — a caller could probe which codes exist by attempting to claim
them. Disjoint namespaces remove the interaction rather than mitigating it. Reserved words
protect routing prefixes (`api`, `health`, `actuator`, `admin`) and reduce impersonation
(`login`, `verify`, `secure`). Case sensitivity is retained for namespace density; the
usability cost is accepted. → ADR-003

---

### A-07 — Which destinations are acceptable?

**Chosen: `http` and `https` only; public destinations only. Rejected: every other scheme —
notably `javascript:`, `data:`, `file:` — and hosts resolving to private, loopback,
link-local or cloud-metadata ranges.**

**Rationale.** A shortener is an open redirector by construction; that is its function, not
a defect. But without scheme restriction it is also a **stored-XSS delivery mechanism** — a
`javascript:` payload behind a link the user was told to trust. Without address restriction
it is an **SSRF pivot** the moment any server-side component fetches the target, which
link-preview or metadata enrichment plausibly will. Restricting at creation costs little now
and is expensive to retrofit once such a component exists. Validation must resist bypass by
decimal, octal and IPv6-mapped encodings.

**Reversibility:** *Reversible* to widen; costly to narrow. → ADR-006

---

### A-08 — Who may create links?

**Chosen: creation requires an API key; resolution is anonymous.**

**Rationale.** An anonymous creation endpoint on a public shortener is an abuse magnet —
phishing, malware, spam — with no attribution with which to respond. A key gives
owner-scoped analytics reads and gives v3 a subject for rate limiting. Resolution stays
anonymous because that is the product. Keys are seeded configuration, not a user store.

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

**Reversibility:** *One-way door.* Escalated and confirmed. → ADR-002

---

### A-10 — What does "reliability" mean?

**Deferred to v3**, the designated ambiguous scenario. v1 commits only to the design targets
in [`engineering-spec.md`](engineering-spec.md) §2 and the failure posture in AC-6.4.

---

## 5. Out of scope for v1

Named so that absence reads as decision, not oversight.

- **Expiration** — v2, by design.
- **Reliability hardening, SLO instrumentation, runbook** — v3, by design.
- User accounts, sessions, OAuth. Key *issuance* is a product concern.
- Link removal / revocation — deferred, though its data-model consequence is settled in A-09.
- Link editing (mutating a destination) — deliberately excluded. It defeats the "stable public handle" premise in §1 and is a hijacking vector.
- Caching tier — v1 reads from the system of record; read-through caching with stampede protection is the documented evolution.
- Per-click event storage; referrer / device / geography breakdown — see A-03, A-04.
- Web UI, custom domains, QR codes, bulk import, A/B destination splitting.
- Rate limiting — v3.
- Multi-region replication.

---

## 6. Gate A — approval required

Planning may not begin until the engineer of record confirms:

- [ ] §1 states the right problem.
- [ ] The v1 / v2 / v3 boundary in §2 is correct, and v1 genuinely excludes expiry.
- [ ] Each resolution in §4 is defensible — **particularly A-01 (302), A-04 (no PII) and A-09 (no code reuse, one-way)**.
- [ ] §5 is acceptable.

**Approved by:** _________________  **Date:** __________
