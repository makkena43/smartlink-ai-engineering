# Scenario 03 — Ambiguous · Clarified Requirements

Normalising *"improve reliability"* into something with acceptance criteria.

- **Status:** Not started — blocked by Scenario 02 reaching Gate D
- **Method:** ask first; where no answer is available, assume explicitly and record the
  assumption as an assumption — never as a finding

---

## 1. Questions that should be asked

In a real engagement these go to the requester before any code is written. Each one changes
the work materially, which is the test for whether a clarifying question is worth asking at
all.

| # | Question | Why it changes the work |
|---|---|---|
| Q-1 | Which path — resolve, create, or both? | Resolve is the whole load and has anonymous users who cannot retry. Create has authenticated callers who can. Hardening the wrong one is wasted effort |
| Q-2 | What triggered this? An incident, an audit, a launch? | An incident names the actual failure mode. An audit wants evidence. A launch wants headroom. Three different deliverables |
| Q-3 | What is the current failure rate, and what would be acceptable? | Without a baseline, "improved" is unfalsifiable |
| Q-4 | What is the budget — deploy topology, spend, timeline? | Multi-AZ and "add a timeout" are both reliability work and differ by orders of magnitude |
| Q-5 | Is degraded service preferable to no service? | Decides whether stale reads are acceptable. For this system the answer drives whether a cache is even permissible |

**Q-5 is the load-bearing one.** For most systems, serving slightly stale data beats serving
an error. For a URL shortener it is not obvious: a stale read means redirecting a user to a
destination the owner has already stopped, which quietly defeats ADR-002's guarantee that a
short link's meaning is stable and controllable. Reliability work that undermines a security
property is not an improvement, and this question is the only thing standing between that
outcome and a plausible-looking cache.

---

## 2. Assumptions, in the absence of answers

Stated as assumptions and marked as such. If any is wrong, the scope below is wrong, and
that is recoverable — silently guessing is not.

| ID | Assumption | If wrong |
|---|---|---|
| AS-1 | The **resolve path** is the subject | Work targets the wrong path; create-path hardening would be needed instead |
| AS-2 | No specific incident prompted this; it is pre-production hardening | A named failure mode would take priority over general work |
| AS-3 | Budget is one deployment unit — no new infrastructure | Multi-AZ and managed failover come back into scope |
| AS-4 | **Correctness beats availability**: a wrong redirect is worse than no redirect | Caching and stale-read tolerance become permissible |
| AS-5 | Reliability must be *evidenced*, not asserted | Documentation and SLI definitions could be dropped |

AS-4 is the decisive one, and it is not a neutral default. It is chosen because this
system's entire product promise is that a short link points where its owner says it points.
A service that occasionally fails loudly keeps that promise. A service that occasionally
redirects to a stale destination breaks it while appearing to work — and appearing to work
is what makes it worse.

---

## 3. Bounded interpretation

*"Improve reliability"* becomes:

> Expose liveness and readiness that reflect real dependency state; fail the resolve path
> **safely** with `503` rather than a guessed destination when dependencies are unavailable;
> bound every external interaction with an explicit timeout so one slow dependency cannot
> exhaust the request pool; define and document SLIs and SLOs as design targets; and provide
> an operational runbook. Defer caching, circuit breaking and multi-AZ deployment, with
> stated reasons.

### In scope

| ID | Deliverable | Acceptance shape |
|---|---|---|
| R-1 | Readiness reflects actual dependency health, not process liveness | Readiness flips to DOWN within a bounded interval of the database becoming unreachable, and back to UP on recovery |
| R-2 | Resolve fails safe | Database unavailable → `503`; never a stale, cached or guessed destination |
| R-3 | Explicit timeouts on every external interaction | A deliberately slow dependency does not exhaust the request pool; the request fails within its budget |
| R-4 | Graceful shutdown | In-flight requests complete; no new work accepted |
| R-5 | SLIs and SLOs documented | Named, measurable, and clearly marked as design targets rather than proven properties |
| R-6 | Operational runbook | Each SLO breach has a first diagnostic step and a stated escalation |

### Deferred, with reasons

Naming these is as much a part of the deliverable as building the ones above — an
unqualified "reliability improved" claim would be the actual failure of this scenario.

| Deferred | Why |
|---|---|
| **Read-through cache** | Introduces stale reads, which AS-4 makes unacceptable here. Reintroducible with an explicit coherency bound if Q-5 comes back the other way |
| **Circuit breaking** | A breaker with an unmeasured threshold converts a degraded dependency into a total outage. Needs baseline data this prototype cannot produce |
| **Retries** | Retrying into a saturated dependency deepens the outage it is meant to survive. Without a measured failure profile it is as likely to hurt as help |
| **Multi-AZ / multi-region** | Real reliability work, and entirely undemonstrable on one laptop. Claiming it would be exactly the unverifiable assertion this project avoids |
| **Bulkheads, load shedding** | No measured saturation profile to size them against |

Every deferral shares one shape: **it cannot be validated in this environment.** Shipping
unvalidated reliability machinery does not improve reliability; it improves the *appearance*
of reliability, which is worse than doing nothing because it stops anyone from looking
further.

---

## 4. What "done" means here

Not "the system is reliable" — that is unfalsifiable and nobody should accept it. Instead:

1. Each of R-1…R-6 has a test or a document, and R-1…R-4 have **fault-injection tests**
   rather than assertions.
2. The SLO table distinguishes production targets from what a laptop demonstrated.
3. The deferred list above appears in the final summary, so a reader sees the boundary of
   the claim without having to find this page.

---

## 5. Gate A — approval required

- [ ] The questions in §1 are the right questions.
- [ ] The assumptions in §2 are acceptable — **particularly AS-4**, which drives everything.
- [ ] The bounded interpretation in §3 is the right scope.
- [ ] The deferrals are justified rather than convenient.

**Approved by:** _________________  **Date:** __________
