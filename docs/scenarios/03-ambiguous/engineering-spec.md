# Scenario 03 — Ambiguous · Engineering Spec

> **Status: not started.** Blocked by Scenario 02 and by this scenario's Gate A.

Translates the **bounded interpretation** in [`clarified-requirements.md`](clarified-requirements.md)
§3 into testable behaviour. R-1…R-6 become acceptance criteria.

The defining constraint: R-1…R-4 must be proven by **fault injection**, not assertion.
Reliability claims that rest on reading the code are the specific failure this scenario
exists to avoid — the code always looks like it handles the failure.

## 1. Functional requirements
## 2. SLI / SLO definitions
Design targets, explicitly separated from what the environment demonstrates.
## 3. Runbook structure
Each SLO breach gets a first diagnostic step and a stated escalation.
## 4. Definition of done
