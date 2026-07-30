# Scenario 02 — Brownfield · Engineering Spec

> **Status: not started.** Blocked by Scenario 01 Gate D and by this scenario's own Gate A.

Translates [`requirements.md`](requirements.md) into testable behaviour, given the system
that actually exists.

## 1. Functional requirements
FR-7 (set expiry at creation), FR-8 (expired links return `410`), plus the
backward-compatibility criteria BC-1…BC-5 promoted to acceptance criteria with tests.

## 2. Design
Expiry as a pure function of `(link, clock)` — unit-testable with no container, per A-12's
single authoritative clock.

## 3. Migration strategy
Additive nullable column; forward-only; expand/migrate/contract position stated.

## 4. Risks

## 5. Definition of done
