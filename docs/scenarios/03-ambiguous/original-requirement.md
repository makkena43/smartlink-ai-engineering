# Scenario 03 — Ambiguous · Original Requirement

## Verbatim

> **"Improve reliability."**

That is the entire requirement, reproduced exactly. It is preserved unedited on its own page
so that the distance between what was *asked* and what was eventually *built* stays visible
and auditable — rather than being quietly closed by a rewrite nobody can now compare against.

---

## Why this cannot be implemented as written

It is not a requirement. It is a **direction**. Three things are missing, and none of them
can be supplied by working harder:

**1. No subject.** Reliability *of what?* The redirect path and the creation path have
different callers, different volumes, and different costs of failure. "Reliable" for an
anonymous visitor who cannot reach their destination means something entirely different from
"reliable" for an authenticated tool that can retry.

**2. No measure.** Reliability is not a property a system has or lacks; it is a number
against a definition. Without an SLI there is no way to know whether the work improved
anything, and without an SLO there is no way to know when to stop.

**3. No bound.** Everything from "add a timeout" to "deploy multi-region with automated
failover" is a defensible reading. They differ by three orders of magnitude in cost. A
requirement that admits both is not a scope, it is a blank cheque.

---

## The failure mode this scenario exists to demonstrate

The tempting response is to start building recognisably reliability-shaped things — retries,
circuit breakers, a cache, a second replica — and report them as progress.

That fails in a specific and expensive way: **without a measure, there is no way to
distinguish reliability work that helped from reliability work that merely happened.** Some
of it actively hurts. Retries against a saturated dependency deepen an outage. A cache
introduces stale reads, and stale reads on this particular system would silently defeat the
security property in ADR-002. A circuit breaker with a badly chosen threshold converts a
degraded dependency into a total outage.

Each of those is *plausible*. Plausibility is exactly what makes this ambiguity dangerous
rather than merely annoying — the wrong answer here does not look wrong.

---

## What happens next

1. [`clarified-requirements.md`](clarified-requirements.md) — the questions that should be
   asked, the answers assumed in their absence, and the bounded interpretation those
   assumptions produce.
2. [`engineering-spec.md`](engineering-spec.md) — that interpretation as testable behaviour.
3. [`task-decomposition.md`](task-decomposition.md), [`validation.md`](validation.md).

The deliverable of this scenario is **the normalisation itself**, not the code that follows
from it. The code is small. Getting from "improve reliability" to something with an
acceptance criterion — and being explicit about what was deliberately excluded and why — is
the entire exercise.
