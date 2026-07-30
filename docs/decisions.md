# Architecture Decision Records

Append-only. An accepted ADR is never edited — it is superseded by a later one, and the
supersession is recorded in both. That is what makes this file the audit trail of *when* and
*why* something changed, leaving `architecture-overview.md` free to describe only what is
true now.

An ADR with no negative consequences listed is an advertisement, not a decision record.

| ADR | Decision | Status | Reversibility |
|---|---|---|---|
| [001](#adr-001) | 302 Found, not 301 Permanent | Accepted | One-way toward 301 only |
| [002](#adr-002) | Short codes are never reused | Accepted | **One-way door** |
| [003](#adr-003) | Alias namespace disjoint from generated codes | **Withdrawn** | n/a — feature out of scope |
| [004](#adr-004) | Analytics synchronous and fail-open | Accepted | Reversible |
| [005](#adr-005) | No PII in analytics | Accepted | **One-way if reversed** |
| [006](#adr-006) | Destination scheme and address policy | Accepted | Reversible to widen |
| [007](#adr-007) | Modular monolith | Accepted | Costly |
| [008](#adr-008) | PostgreSQL as system of record; no cache in v1 | Accepted | Reversible |
| [009](#adr-009) | Random codes, uniqueness enforced by the database | Accepted | Costly |
| [010](#adr-010) | Expired links return 410, not 404 | Accepted | Costly |
| [011](#adr-011) | Expiry evaluated through a time port, not `Instant.now()` | Accepted | Reversible |

---

## ADR-001 {#adr-001}
### Redirect with 302 Found rather than 301 Permanent

**Status** Accepted · **Scenario** 01-greenfield · **Reversibility** One-way toward 301 only

**Context.** The requirement asks for redirect *and* analytics in one sentence. Real
shorteners use both status codes.

**Options.** 301 — cached by browsers and intermediaries, fastest for repeat visitors.
302 — every click reaches the service. 307/308 — method-preserving, irrelevant for GET-only
resolution.

**Decision.** 302, with `Cache-Control: no-store`.

**Why.** A 301 makes analytics structurally incomplete: cached clients stop contacting the
service entirely, and the resulting undercount is unbounded and unmeasurable — you cannot
even quantify what you are missing. It is also irreversible in the wild, because a cached
301 cannot be recalled. Moving 302 → 301 later is a config change; moving 301 → 302 is
impossible for clients that already cached. **The asymmetry decides it, not the latency.**

**Consequences.** *Positive:* analytics are complete; revocation and expiry remain
honourable; the decision stays open. *Negative:* every click costs an origin request, and
repeat visitors are marginally slower — real costs, accepted knowingly. *Follow-on:*
server-side caching recovers the speed under our own control (ADR-008).

**Revisit when** analytics move to a client-side beacon, or origin traffic becomes the
binding cost constraint.

---

## ADR-002 {#adr-002}
### Short codes are permanently retired, never reused

**Status** Accepted · **Reversibility** **One-way door**

**Context.** Removal is out of v1 scope, but the v1 data model either permits code reuse or
forecloses it. The choice is made now whether or not it is discussed.

**Decision.** Codes are retired permanently. Any future removal is a tombstone, never a row
deletion.

**Why.** A printed or messaged link outlives the service's memory of it. If `abc123` can be
reissued to a new owner, every historical holder of that link is silently redirected to a
destination chosen by somebody else — a link-hijacking primitive, and one the original
recipient has no way to detect. This is a security decision wearing a storage decision's
clothes.

**Consequences.** *Positive:* a short link's meaning is stable forever; no hijacking vector.
*Negative:* the namespace only ever shrinks, and tombstone rows accumulate — negligible
against 62⁷, but genuinely permanent. *Neutral:* v2 expiry and any future revocation both
inherit tombstone semantics for free.

**Revisit when** never. This is why it was escalated before implementation rather than
discovered after.

---

## ADR-003 {#adr-003}
### Custom aliases occupy a namespace disjoint from generated codes

**Status** **Withdrawn at requirements revision 2** — custom aliases and branded domains are
explicitly out of scope (requirements §6). The decision is retained rather than deleted
because the reasoning survives the feature: if aliases are ever introduced, the enumeration
oracle described below is the trap to avoid, and re-deriving it from scratch would be waste.

The one part that outlived the feature is routing safety, now carried as **GF-16** — short
codes and application routes share the root namespace whether or not aliases exist.

*Original record follows.*

**Reversibility** Reversible

**Context.** Custom aliases and generated codes could share one namespace or occupy
separate ones.

**Decision.** Disjoint namespaces, recorded as a stored `code_kind` rather than inferred
from shape. Aliases are 3–32 chars of `[A-Za-z0-9_-]`, case-sensitive, with a reserved-word
denylist.

**Why.** A shared namespace creates two problems at once: a race between allocation and
claim, and an **enumeration oracle** — a caller can discover which codes exist by attempting
to claim them and reading the error. Disjoint namespaces remove the interaction rather than
mitigating it. Reserved words protect routing prefixes (`api`, `actuator`, `health`) and
reduce impersonation (`login`, `verify`, `secure`).

**Consequences.** *Positive:* no race, no oracle, no shadowing of the management surface.
*Negative:* case sensitivity means links dictated aloud are error-prone — a real usability
cost, accepted for namespace density. *Neutral:* `code_kind` must be maintained on write.

---

## ADR-004 {#adr-004}
### Analytics recording is synchronous and fails open

**Status** Accepted · **Reversibility** Reversible

**Context.** v1 needs per-link counters. Recording can be synchronous or asynchronous, and
can fail the request or not.

**Decision.** Synchronous counter update on the resolve path; failures are logged and
swallowed so the redirect still succeeds.

**Why.** *Synchronous* because an async pipeline in v1 buys accuracy nobody has asked for at
the price of a queue, a consumer, and a whole class of delivery-semantics bugs — complexity
that is not yet earned. *Fail-open* because the visitor's need is to reach their
destination; blocking them from an available page to protect a counter serves nobody.

**Consequences.** *Positive:* simple, immediately consistent, easy to validate. *Negative:*
work on the hot path, and **hot-row contention** when one code dominates traffic — the known
failure mode. *Follow-on:* performance scenario B measures exactly this rather than assuming
it; asynchronous events are the documented evolution.

**Revisit when** scenario B shows contention at loads the service actually sees.

---

## ADR-005 {#adr-005}
### Analytics retains no personal data in v1

**Status** Accepted · **Reversibility** **One-way if reversed**

**Context.** Every resolution carries a client IP, a user-agent and often a referrer. None
of it was requested; all of it is trivially available.

**Decision.** Persist counters only. No IP, no user-agent, no referrer.

**Why.** Persisting request data turns a link shortener into a behavioural tracking system
and acquires data-protection obligations — retention limits, subject access, deletion —
that nothing in the requirement asked for and that no part of this design is built to
honour. In a financial-services context that is a compliance surface acquired by accident.
Collecting "just in case" means deciding to collect personal data before deciding it is
needed.

**Consequences.** *Positive:* no retention policy needed; no deletion machinery; the privacy
decision stays open and deliberate. *Negative:* questions about referrer, device and
geography cannot be answered retroactively — the data was never there. That is accepted:
data never collected cannot be recovered, but data collected under an unclear basis cannot
be un-collected either, and only one of those two mistakes is fixable.

**Revisit when** a stated product requirement needs richer analytics, at which point
retention and minimisation are designed in rather than bolted on.

---

## ADR-006 {#adr-006}
### Destinations restricted to public http(s)

**Status** Accepted · **Reversibility** Reversible to widen

**Context.** A shortener is an open redirector by construction. "URL" is far broader than
what is safe to redirect to.

**Decision.** `http` and `https` only. Reject hosts resolving to private, loopback,
link-local or cloud-metadata ranges, including decimal, octal and IPv6-mapped encodings.

**Why.** Without scheme restriction the service becomes a **stored-XSS delivery mechanism** —
a `javascript:` payload behind a link the recipient was told to trust. Without address
restriction it becomes an **SSRF pivot** the moment any server-side component fetches the
target, which link-preview or metadata enrichment plausibly will. Restricting at creation
costs little now; retrofitting after such a component exists is expensive and, in the
interim, exploitable.

**Consequences.** *Positive:* two whole vulnerability classes closed before the code that
would expose them is written. *Negative:* legitimate internal-hostname use cases are
blocked; a deliberate allowlist would be needed. *Neutral:* DNS rebinding means validation
must happen against the resolved address, not the hostname string.

---

## ADR-007 {#adr-007}
### Modular monolith

**Status** Accepted · **Reversibility** Costly

**Context.** Create and resolve have very different load profiles, which is an argument for
separate services.

**Decision.** One deployment unit with enforced internal module boundaries.

**Why.** The load asymmetry is real but does not yet justify the operational cost of
separate services — deployment, discovery, distributed tracing, partial-failure semantics.
Enforcing boundaries in-process preserves the *option* to split later at a fraction of the
cost of splitting now and discovering the seams were wrong.

**Consequences.** *Positive:* fast delivery, simple deployment, coherent tests. *Negative:*
create and resolve cannot scale independently; a create-path bug can affect the resolve
path's process. *Follow-on:* split only on measured need, and the domain layer's zero
dependencies is what keeps that split cheap.

---

## ADR-008 {#adr-008}
### PostgreSQL as system of record; no caching tier in v1

**Status** Accepted · **Reversibility** Reversible

**Decision.** Reads go to PostgreSQL. No Redis in v1.

**Why.** A cache introduces an invalidation problem, a second failure mode, and a coherency
window — and in v1 there is no measurement showing it is needed. Adding infrastructure on
the strength of an assumption is how prototypes acquire operational burden they never earn.

**Consequences.** *Positive:* fewer moving parts, no stale-read class of bug, one source of
truth. *Negative:* popular links hit the database on every resolution, and the 302 decision
(ADR-001) guarantees every click arrives. *Follow-on:* read-through caching with stampede
protection is the documented evolution, introduced when measurement justifies it.

**Revisit when** performance scenario A shows database read cost dominating p95.

---

## ADR-009 {#adr-009}
### Random codes; uniqueness enforced by the database

**Status** Accepted · **Reversibility** Costly

**Decision.** 7 random characters over `[A-Za-z0-9]` (62⁷ ≈ 3.5 × 10¹²). A unique index
arbitrates collisions; insert-and-retry, bounded.

**Why.** Sequential codes let anyone enumerate every link in the system by counting.
Hash-of-URL leaks whether a given URL was shortened before — an oracle that also silently
reintroduces the deduplication rejected in A-02. Random codes avoid both.

Uniqueness is enforced by the **database**, not by a check-then-insert in application code,
because check-then-insert is a race: two concurrent requests can both observe a code as
free. Letting the unique index arbitrate makes the collision *impossible* rather than
*unlikely*.

**Consequences.** *Positive:* no enumeration, no oracle, no race. *Negative:* an insert can
fail and retry, so creation latency has a tail; code length is fixed and changing it later
affects every future code. *Neutral:* NFR-5 tests behaviour under a **forced** collision —
probability arguments fail silently, tests do not.


---

## ADR-010 {#adr-010}
### An expired link returns 410 Gone, not 404 Not Found

**Status** Accepted · **Scenario** 02-brownfield · **Reversibility** Costly

**Context.** Scenario 02 introduces links that stop resolving at a chosen instant. A visitor who
follows one afterwards has to be told something.

**Options.** `404 Not Found` — reuse the unknown-code response. `410 Gone` — a distinct status.
`302` to a configurable landing page.

**Decision.** `410 Gone`, with no `Location` header.

**Why.** `404` and `410` answer genuinely different questions: *never existed* versus *existed and
was deliberately ended*. That difference is what lets an operator, or a campaign owner reading an
access log, tell a typo apart from a finished campaign **without querying the database**.
Collapsing both into `404` discards that signal permanently and saves nothing.

The landing-page option was rejected on stronger grounds: it makes the service responsible for
hosting content, and an expired link would still *work* from the visitor's point of view — the
opposite of what "stop redirecting" asked for.

**Consequences.** *Positive:* the two states stay distinguishable in logs and to clients; the
absence of `Location` makes it structurally impossible for a redirect-following client to reach
the destination anyway. *Negative:* it is a public contract, so a client that special-cases `410`
would break if this ever became `404`. *Neutral:* additive — no previously reachable state changes
its status, which is why the API stays on `/api/v1`.

**Revisit when** never, absent a product decision to reintroduce expired links as something other
than gone.

---

## ADR-011 {#adr-011}
### Expiry is evaluated through a time port, not `Instant.now()`

**Status** Accepted · **Scenario** 02-brownfield · **Reversibility** Reversible

**Context.** Expiry is an inequality against "now". Something has to supply "now".

**Decision.** A `TimeSource` port in the domain, implemented in infrastructure over
`Clock.systemUTC()`. The lifecycle rule takes the instant as a parameter.

**Why.** Two reasons that both bite in production rather than in a demo:

- **The interesting cases are all at the boundary** — the instant before, the instant itself, the
  instant after. With a hardcoded clock those can only be tested by sleeping, and a sleeping test
  is slow, flaky, and the first thing deleted when CI goes red for an unrelated reason.
- **One clock, not one per instance.** Several stateless instances each calling `Instant.now()`
  means several clocks that disagree. A link near its expiry would resolve on one instance and
  return `410` on the next, with nothing to reproduce.

**Consequences.** *Positive:* every boundary case is a plain assertion; the suite gained no sleeps.
*Negative:* one more constructor argument, which is what forced three Greenfield test files to be
edited (`impact-analysis.md` §5). *Neutral:* a deployment could substitute a synchronised clock
without touching the domain.
