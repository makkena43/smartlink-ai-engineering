# Spec 001 — Core Link Shortening (Greenfield)

- **Scenario type:** Greenfield — new system, no existing code to preserve
- **Status:** Draft, awaiting Gate A approval
- **Author:** Srinivas Makkena (engineer of record)
- **Constitution:** v1.0

> **Article I.2 compliance.** This document states *what* and *why*. It deliberately names
> no framework, database, library, class or table. Those decisions belong in `plan.md` and
> are made only after the problem below is agreed to be the right problem.

---

## 1. Intent

Provide a service that exchanges a long URL for a short, durable, publicly resolvable
identifier, and that resolves such identifiers back to their targets fast enough to sit in
the hot path of a user's navigation.

The value is not the string compression. It is that **the short link becomes the stable
public handle for a destination** — printed, pasted into messages, embedded in campaigns —
while the system retains the ability to observe and control what happens when it is used.
Every requirement below follows from that: durability, because printed links outlive
deploys; latency, because a redirect sits between a user's click and the thing they
actually wanted; and control, because a link you cannot revoke is a liability.

---

## 2. Source requirement, as received

> "You will build a URL shortener service from scratch with core APIs, analytics, and
> reliability features."

This sentence is the entire requirement. It is three words of scope ("core APIs",
"analytics", "reliability") covering what is realistically several weeks of work, and it
specifies no behaviour whatsoever. Section 4 records what had to be decided to make it
buildable.

### 2.1 Scenario split

The requirement is decomposed across the three assignment scenarios rather than built in
one pass, so that each scenario exercises a genuinely different engineering mode:

| Scenario | Scope | Why here |
|---|---|---|
| **001 — Greenfield** *(this spec)* | Shorten, resolve, custom alias, expiry, revoke | Nothing exists; the work is design from first principles |
| **002 — Brownfield** | Click analytics | Must be added to a running system without breaking the hot path — real codebase reasoning |
| **003 — Ambiguous** | "Make it reliable and stop abuse" | Deliberately underspecified; the work is disambiguation |

---

## 3. Users and their needs

| Actor | Needs | Implication |
|---|---|---|
| **API consumer** (marketing tool, internal service) | Create links programmatically, predictably, idempotently | Machine-friendly errors; retry-safe creation |
| **Link visitor** (public, untrusted) | Reach the destination immediately | Latency budget; no auth on resolve; hostile input assumed |
| **Link owner** | Revoke a link that points somewhere wrong | Revocation must be immediate and total |
| **Operator** | Know the service is healthy; diagnose failures | Health, readiness, structured diagnostics |

The visitor is the highest-volume and least-trusted actor. Read path and write path
therefore have different security postures and very different load profiles — a fact that
drives the architecture in `plan.md`.

---

## 4. Ambiguity Register

Per Constitution Article II. Each ambiguity was genuinely open in the source requirement;
each resolution is recorded with its rationale, blast radius and reversibility.

---

### A-01 — Which redirect status code?

**Ambiguous because** the requirement says "shortener" and both 301 (Permanent) and 302
(Found) are used by real shorteners, with opposite consequences.

| Reading | Consequence |
|---|---|
| 301 Permanent | Browsers and intermediaries cache aggressively and **stop contacting the service**. Fastest for repeat visitors. |
| 302 Found | Every click reaches the service. |
| 307/308 | Method-preserving variants; irrelevant here since resolution is GET-only. |

**Chosen: 302 Found, with explicit `Cache-Control: no-store` on the redirect.**

**Rationale.** A 301 is functionally irreversible in the wild: once a browser has cached
it, revocation (§5, FR-6) cannot be honoured for that client, and analytics (Scenario 002)
undercount by an unknowable margin. The requirement explicitly asks for analytics and for
reliability features — both are defeated by caching the redirect. Speed is recovered
server-side through caching the *lookup*, which is under our control, rather than by
delegating caching to clients, which is not.

**Blast radius if wrong:** higher origin traffic and marginally slower repeat clicks.
**Reversibility:** *Reversible* going 302 → 301. **One-way** in the other direction, since
already-cached 301s cannot be recalled. This asymmetry is the deciding factor.

---

### A-02 — Does submitting the same long URL twice return the same short code?

**Ambiguous because** "shorten this URL" does not say whether the mapping is a function of
the URL or of the request.

**Chosen: no implicit deduplication. Each creation request yields a new code. Callers
wanting idempotency supply an explicit idempotency key.**

**Rationale.** Implicit dedup silently makes two independent links share a fate: if one
owner revokes, the other's link dies; analytics for two separate campaigns merge into one
bucket and cannot be separated afterwards. The data loss is unrecoverable. Explicit
idempotency gives retry-safety — the real requirement behind the question — without
conflating distinct intents.

**Blast radius:** slightly more storage. **Reversibility:** *Reversible.*

---

### A-03 — What happens when an expired link is visited?

**Chosen: `410 Gone`, not `404 Not Found`.**

**Rationale.** The two are semantically different and the difference is operationally
useful: 404 means "never existed", 410 means "existed, deliberately ended". That
distinction is what lets an operator tell a typo apart from an expired campaign without
a database query. Revoked links return 410 for the same reason.

**Reversibility:** *Costly* — clients may branch on the status. Treated as a public API
contract decision under Article II.3.

---

### A-04 — May a short code be reused after deletion?

**Chosen: never. Codes are permanently retired; deletion is a tombstone, not a row
removal.**

**Rationale.** This is a security decision, not a storage one. A printed or messaged link
outlives the service's memory of it. If code `abc123` can be reissued to a new owner, every
historical holder of that link is silently redirected to a destination chosen by someone
else — a link-hijacking primitive. The storage cost of tombstones is trivial against that.

**Reversibility:** *One-way door.* Escalated per Article II.3 and confirmed.

---

### A-05 — Which destination URLs are acceptable?

**Ambiguous because** "URL" is far broader than what is safe to redirect to.

**Chosen: `http` and `https` schemes only; public destinations only. Rejected: all other
schemes (notably `javascript:`, `data:`, `file:`), and hosts resolving to private,
loopback, link-local or cloud-metadata address ranges.**

**Rationale.** A shortener is, by construction, an open redirector — that is its function,
not a bug. But without scheme restriction it also becomes a stored-XSS delivery mechanism
(`javascript:` in a link a user is told is trustworthy), and without address restriction it
becomes an SSRF pivot the moment any server-side component fetches the target — which
Scenario 002's metadata enrichment plausibly will. Restricting at creation is far cheaper
than retrofitting once such a component exists.

Validation must resist bypass by IP-encoding tricks (decimal, octal, IPv6-mapped) and by
redirect chains. **Reversibility:** *Reversible* to widen; costly to narrow.

---

### A-06 — Are custom aliases allowed, and what may they contain?

**Chosen: allowed, 3–32 characters, `[A-Za-z0-9_-]`, case-sensitive, drawn from a
namespace disjoint from generated codes, with a reserved-word denylist.**

**Rationale.** Custom aliases and generated codes competing for one namespace creates a
race and an enumeration channel: a caller can probe which codes exist by attempting to
claim them. Disjoint namespaces remove the interaction entirely. Reserved words protect
routes (`api`, `health`, `admin`) and reduce impersonation (`login`, `verify`, `secure`).
Case sensitivity is retained because it triples effective namespace density; the
usability cost is accepted and noted as a risk in `plan.md`.

---

### A-07 — Who may create links?

**Chosen: creation requires an API key; resolution is anonymous.**

**Rationale.** An anonymous creation endpoint on a public shortener is an abuse magnet —
phishing, malware distribution, and spam — and there is no attribution with which to
respond. Requiring a key gives per-owner revocation and per-owner rate limiting, which
Scenario 003 needs. Resolution stays anonymous because that is the product.

The key model is deliberately minimal (see §7, Out of Scope). **Reversibility:**
*Reversible.*

---

### A-08 — Is "analytics" real-time or aggregate?

**Deferred to Scenario 002 and recorded here so it is not silently answered by this
scenario's data model.** The only constraint 001 imposes: resolution must not become
synchronously dependent on recording a click. That constraint is testable now, before the
analytics code exists, and is asserted by AC-9.1.

---

### A-09 — What does "reliability" mean?

**Deferred to Scenario 003**, which is the designated ambiguous scenario. 001 commits only
to the latency and availability budgets in §6.

---

## 5. Functional requirements

Each requirement carries an ID used for forward/backward traceability (Article V.2).

### FR-1 — Create a short link
Given a valid destination URL, the service returns a short code and its full short URL.
Optional inputs: custom alias, expiry instant, idempotency key.

- **AC-1.1** Valid `https` destination → `201 Created` with code, short URL, destination, creation instant.
- **AC-1.2** Response carries a `Location` header for the created resource.
- **AC-1.3** Two requests with the same idempotency key and identical body → identical response; exactly one link exists.
- **AC-1.4** Same idempotency key with a *different* body → `409 Conflict`; the original is unmodified.
- **AC-1.5** Generated codes are 7 characters from a 62-symbol alphabet, and are not sequentially guessable from an adjacent code.

### FR-2 — Resolve a short link
- **AC-2.1** Live code → `302` with `Location` = exact stored destination.
- **AC-2.2** Redirect carries `Cache-Control: no-store` (per A-01).
- **AC-2.3** Unknown code → `404`.
- **AC-2.4** Expired code → `410` (per A-03).
- **AC-2.5** Revoked code → `410`.
- **AC-2.6** Destination is returned byte-identical, including query string and fragment.
- **AC-2.7** Resolution requires no authentication.

### FR-3 — Custom alias
- **AC-3.1** Available, well-formed alias → `201`, alias used verbatim.
- **AC-3.2** Already-claimed alias → `409`.
- **AC-3.3** Reserved word → `422` naming the violated rule.
- **AC-3.4** Malformed alias (length or charset) → `422`.
- **AC-3.5** A custom alias can never collide with a generated code (per A-06).

### FR-4 — Expiry
- **AC-4.1** Expiry in the past at creation → `422`.
- **AC-4.2** Before expiry, resolution succeeds; at or after, `410`.
- **AC-4.3** Absent expiry → link does not expire.
- **AC-4.4** Expiry is evaluated against a single authoritative clock, not per-node local time.

### FR-5 — Inspect a link
- **AC-5.1** Owner retrieves metadata (destination, created, expiry, status) without following the redirect.
- **AC-5.2** A non-owner key cannot read another owner's link → `404`, not `403` (non-existence and non-ownership are made indistinguishable, to avoid an enumeration oracle).

### FR-6 — Revoke a link
- **AC-6.1** Owner revokes → subsequent resolution returns `410` within the cache-coherency bound stated in NFR-4.
- **AC-6.2** Revocation is idempotent.
- **AC-6.3** The code is never reissued (per A-04).

### FR-7 — Destination validation
- **AC-7.1** Non-`http(s)` scheme → `422`.
- **AC-7.2** Host resolving to private/loopback/link-local/metadata ranges → `422`.
- **AC-7.3** Decimal, octal and IPv6-mapped encodings of blocked addresses are also rejected.
- **AC-7.4** URL exceeding the documented maximum length → `422`.
- **AC-7.5** Rejections name the specific failed rule; they never echo the raw input back unescaped.

### FR-8 — Operability
- **AC-8.1** Liveness endpoint reflects process health only.
- **AC-8.2** Readiness endpoint fails when a required dependency is unavailable.
- **AC-8.3** Every response carries a correlation ID, echoed from the caller when supplied.
- **AC-8.4** Logs are structured and contain no full destination URLs at INFO (they are user-supplied and may carry secrets in query strings).

### FR-9 — Analytics-readiness *(structural only; behaviour is Scenario 002)*
- **AC-9.1** Resolution succeeds and stays within its latency budget when the click-recording path is disabled or failing. Enforced by a fault-injection test in this scenario.

---

## 6. Non-functional requirements

| ID | Requirement | Measured by |
|---|---|---|
| **NFR-1** | Resolve p99 ≤ 50 ms server-side at 500 rps, warm | Load script, reference hardware |
| **NFR-2** | Create p99 ≤ 200 ms at 50 rps | Load script |
| **NFR-3** | Resolution remains available when the analytics path is down | Fault-injection test (AC-9.1) |
| **NFR-4** | Revocation takes effect within 5 s across all nodes | Documented cache TTL + test |
| **NFR-5** | No destination URL, API key or secret in logs at INFO or below | Log assertion test |
| **NFR-6** | Service is horizontally scalable — no node-local state on the read path | Architecture review + two-node test |
| **NFR-7** | Code generation collision probability < 1 in 10⁶ at 10⁸ stored links | Analysis in `plan.md` |
| **NFR-8** | Domain and service layers ≥ 85 % line coverage | JaCoCo gate |

---

## 7. Out of scope

Named explicitly so their absence reads as a decision rather than an oversight:

- User accounts, sessions, password flows, OAuth. API keys are seeded configuration; key *issuance* is a product concern, not an engineering-assessment concern.
- Web UI. The deliverable is an API plus generated documentation.
- Custom domains per owner.
- Link editing (destination mutation) — deliberately excluded; it defeats the "stable public handle" premise in §1 and is a link-hijacking vector.
- QR codes, bulk import, geo/device breakdown, A/B destination splitting.
- Multi-region replication.
- Rate limiting and abuse controls — **Scenario 003**.
- Click analytics — **Scenario 002**.

---

## 8. Risks carried into planning

| Risk | Why it matters |
|---|---|
| Code generation strategy | Determines collision behaviour, guessability and write throughput at once — resolved in ADR-002 |
| Cache invalidation on revoke | NFR-4 is a correctness requirement, not a performance one; a stale cache serves a revoked link |
| Destination validation bypass | A-05 is only as good as its resistance to encoding tricks and DNS rebinding |
| Hot-path coupling | AC-9.1 must be enforced structurally, not by convention, or Scenario 002 will violate it |

---

## 9. Definition of done

1. Every AC above is exercised by at least one automated test, traceably mapped.
2. All Article VI quality gates green.
3. Service runs end-to-end from a clean clone with a documented command.
4. OpenAPI documentation generated from the implementation, not hand-maintained.
5. ADRs recorded for every decision marked *costly* or *one-way* above.
6. Traceability ledger complete, including rejections.

---

## Gate A — approval required

Per Constitution Article VIII.3, `plan.md` may not be written until the engineer of record
confirms:

- [ ] The problem in §1 is the right problem.
- [ ] The scenario split in §2.1 is sound.
- [ ] Each ambiguity resolution in §4 is defensible — **particularly A-01 (302 over 301),
      A-04 (no code reuse, one-way), and A-07 (authenticated creation)**.
- [ ] The out-of-scope list in §7 is acceptable.
- [ ] The acceptance criteria are testable as written.

**Approved by:** _________________ **Date:** __________
