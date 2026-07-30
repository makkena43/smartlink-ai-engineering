# Scenario 02 — Brownfield · Impact Analysis

> **Status: not started.** Blocked by Scenario 01 reaching Gate D.
>
> This document must be produced by **reading the committed code**, not by recalling what
> was intended. That distinction is the entire value of the artifact: an impact analysis
> written from memory reproduces the author's mental model, which is precisely the thing
> that is wrong when a change breaks something unexpected.
>
> The structure below is fixed now; every cell is filled from the real codebase at v1 close.

## 1. Impacted modules

| Layer | Component | Change | Blast radius |
|---|---|---|---|
| domain | | | |
| application | | | |
| infrastructure | | | |
| api | | | |

## 2. Impacted data flows

Which request paths change shape, and which merely pass through unchanged. The resolve path
is the one that matters: it carries all the load and all the untrusted callers.

## 3. Schema impact

Migration, rollback position, and the expand/migrate/contract stage this change occupies.
Forward-only and additive-first (BC-4).

## 4. API contract impact

Every field added, and proof that no existing request or response shape changed (BC-2, BC-3).

## 5. Test impact

| Existing test | Still passes? | If not — bug or intended behaviour change? |
|---|---|---|

Per BC-5, any test requiring modification is a **behaviour change** and must be justified
here. Editing a failing test to match new behaviour without recording why is how a
regression becomes a feature.

## 6. Risks introduced

## 7. Rollback plan

What happens if this is reverted after deploy but before the migration is rolled back —
the case that actually occurs in practice, and the one most often left unconsidered.
