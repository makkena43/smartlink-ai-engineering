# Scenario 02 — Brownfield · Requirements

**Requirement as received:** *"Add expiration so campaigns can stop redirecting after a
defined time."*

- **Scenario type:** Brownfield — a change to a system that already exists, is tested, and has clients
- **Status:** Not started — blocked by Scenario 01 reaching Gate D
- **Engineer of record:** Srinivas Makkena

---

## 1. Why this change, and not a larger one

Expiration was chosen over bigger candidates on purpose. It is **compact but genuinely
cross-cutting**: it touches persistence, the creation API, redirect behaviour, backward
compatibility, migration, generated documentation and tests — which is exactly the surface a
brownfield scenario is supposed to exercise.

A larger feature (custom domains, per-click event analytics, a web UI) would have produced
more code and *less* evidence of judgment, because the interesting part of brownfield work
is not volume. It is: what does this touch, what might it break, what do existing clients
depend on, and how do I land it without a flag day.

Expiration also has a real trap in it — see A-11 below — which a purely additive feature
would not.

---

## 2. What makes this brownfield rather than greenfield

Three things are true here that were not true in Scenario 01:

1. **The schema has data in it.** A migration is no longer a fresh `CREATE TABLE`; it must
   leave every existing row valid and every existing link resolvable.
2. **The API has clients.** Any request body that worked before this change must still work
   after it, unchanged.
3. **The tests encode current behaviour.** A test that starts failing is either a bug I
   introduced or a decision I made without noticing — and telling those two apart is most of
   the work.

The impact analysis in [`impact-analysis.md`](impact-analysis.md) is the artifact that takes
this seriously; it is written *before* the change, and verified against the real codebase
rather than recalled.

---

## 3. Ambiguity Register

The requirement is short, and three things in it are undecided.

### A-11 — What does an expired link return?

**Candidate readings:** `404 Not Found` · `410 Gone` · `302` to a configurable landing page.

**Chosen: `410 Gone`.**

**Rationale.** `404` and `410` are semantically different and the difference is operationally
useful: `404` means "never existed", `410` means "existed, deliberately ended". That
distinction is what lets an operator — or a campaign owner reading a log — tell a typo apart
from a finished campaign **without a database query**. Collapsing both into `404` throws that
signal away permanently and costs nothing to preserve.

A redirect to a landing page was rejected: it makes the service responsible for hosting
content, and it means an expired link still *works* from the visitor's perspective, which is
the opposite of what "stop redirecting" asked for.

**This is a public API contract decision** — clients may branch on the status code — so it is
escalated under Article II.3 rather than settled in implementation.

**Reversibility:** *Costly.* Clients that special-case `410` would break if it later became
`404`.

### A-12 — Is expiry evaluated against a shared clock or per-node time?

**Chosen: a single authoritative clock, database-side.**

**Rationale.** With per-node local time, two instances can disagree about whether a link is
live. The visible symptom is a link that redirects on one request and returns `410` on the
next, with no way to reproduce it — the kind of bug that costs days and is trivially avoided
by deciding this now. Also makes the domain rule a pure function of `(link, clock)`, which
keeps it unit-testable with no container.

### A-13 — May an existing link's expiry be set, changed, or removed after creation?

**Chosen: not in this Brownfield scenario. Expiry is set at creation only.**

**Rationale.** Scenario 01 deliberately excluded destination mutation on the grounds that a
short link is a *stable public handle*. Expiry mutation is a smaller version of the same
question and deserves the same deliberate answer rather than arriving as a side effect of
adding a column. Deferring it costs nothing and keeps this Brownfield change's blast radius honest.

**Reversibility:** *Reversible* — adding mutation later is additive.

---

## 4. Backward compatibility requirements

These are requirements, not aspirations, and each gets a test:

| ID | Requirement |
|---|---|
| **BC-1** | Every link created before this change continues to resolve, unchanged |
| **BC-2** | A creation request with no expiry field behaves exactly as it did before — never expires |
| **BC-3** | Existing stats responses keep their current shape; fields may be added, never removed or retyped |
| **BC-4** | The migration is forward-only and additive; no existing column is altered or dropped |
| **BC-5** | Scenario 01's full test suite passes untouched. Any test that requires modification is treated as a **behaviour change** and must be justified in the impact analysis, not quietly edited |

BC-5 is the one that does the real work. It converts "I don't think I broke anything" into
something the build can check.

---

## 5. Functional requirements

| ID | Requirement type | Requirement |
|---|---|---|
| BF-01 | Link-creation enhancement | A link creator may provide an optional expiration timestamp when creating a short link. |
| BF-02 | Input validation | An expiration timestamp, when supplied, shall be a valid UTC instant later than the creation time. |
| BF-03 | Backward compatibility | A caller that omits the expiration timestamp shall receive the same creation behaviour as in Scenario 01. |
| BF-04 | Link lifecycle | A link with no expiration timestamp shall remain active. |
| BF-05 | Redirect behaviour | A link whose expiration time has passed shall not redirect to its registered destination. |
| BF-06 | Error behaviour | Resolving an expired link shall return `410 Gone` and a safe `LINK_EXPIRED` response. |
| BF-07 | Analytics | Basic analytics shall expose an expiry timestamp when set and the current active/expired status. |

## 6. Brownfield scope assumptions

- Expiration timestamps are supplied and stored as UTC instants.
- A link is expired when the current authoritative time is equal to or later than its expiration timestamp.
- Existing rows have no expiry value and remain active.
- Expired links remain stored for analytics and auditability; they are not automatically deleted.
- The public redirect route and short-link format remain unchanged.
- This is an additive API and schema change; `/api/v2` is not required.

## 7. API-versioning decision

The current management API remains under `/api/v1` because this change is backward compatible:

- `expiresAt` is optional in the create request.
- Existing callers may omit it without behavioural change.
- Response fields are only added; no field is removed or retyped.
- Public links remain unversioned `GET /{code}` because shared links are a stable product contract.

A new management API version is appropriate only for a breaking change, such as removing or
renaming a required field, changing a response meaning, or changing authentication semantics.

## 8. Out of scope

- Changing or removing expiry after creation (A-13).
- Bulk expiry, scheduled campaigns, timezone-aware business calendars.
- Notifying owners before expiry.
- Purging or archiving expired rows — codes stay retired regardless (ADR-002).
- Editing the destination URL or expiry of an existing link.
- A lifecycle-management user interface.
- A breaking `/api/v2` management API.

---

## 9. Gate A — approval required

- [ ] `410` over `404` for expired links is the right contract (A-11).
- [ ] Expiry-at-creation-only is an acceptable Brownfield boundary (A-13).
- [ ] The backward-compatibility requirements in §4 are complete.

**Approved by:** _________________  **Date:** __________
