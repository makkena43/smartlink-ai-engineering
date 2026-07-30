# Scenario 02 — Brownfield · Validation

> **Status: not started.**

## 1. Acceptance criteria traceability
| AC | Requirement | Test | Level | Result |
|---|---|---|---|---|

## 2. Backward compatibility evidence
The section that matters most in a brownfield change.

| ID | Requirement | Evidence |
|---|---|---|
| BC-1 | Pre-existing links still resolve | |
| BC-2 | Requests without expiry behave as before | |
| BC-3 | Stats response shape unchanged | |
| BC-4 | Migration additive and forward-only | |
| BC-5 | Scenario 01 suite passes **untouched** | |

BC-5 carries the weight. It converts "I don't think I broke anything" into something the
build can check.

## 3. Quality gate results

## 4. Rollback rehearsal
Migration applied, application reverted, service still correct.
