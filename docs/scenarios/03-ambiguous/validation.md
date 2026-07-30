# Scenario 03 — Ambiguous · Validation

> **Status: not started.**

## 1. Acceptance criteria traceability
| AC | Requirement | Test | Level | Result |
|---|---|---|---|---|

## 2. Fault-injection evidence
| Fault injected | Expected behaviour | Observed |
|---|---|---|
| Database unreachable | resolve → 503, never a stale destination | |
| Database slow beyond timeout | request fails within budget; pool not exhausted | |
| Dependency recovers | readiness returns UP within bound | |
| SIGTERM during in-flight requests | requests complete; no new work accepted | |

## 3. SLO measurement
Reported with method, machine and sample size. **Production targets are not claimed as
demonstrated.**

## 4. What was NOT made more reliable
Carried forward from `clarified-requirements.md` §3 so the boundary of the claim is visible
without hunting for it: no cache, no circuit breaking, no retries, no multi-AZ.
