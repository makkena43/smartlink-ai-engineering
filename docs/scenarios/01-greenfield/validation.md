# Scenario 01 — Greenfield · Validation

Evidence that v1 does what [`engineering-spec.md`](engineering-spec.md) says it does.

> **Status: pending implementation (T-02 onward).**
>
> This document is deliberately empty of results. Populating it before the tests exist would
> mean writing down numbers nobody measured — which is the specific failure this project is
> meant to demonstrate the absence of. The structure below is fixed now so that the
> traceability matrix is filled in *as* work lands, not reconstructed afterwards from memory.

---

## 1. Acceptance criteria traceability

Every AC maps to at least one automated test. An AC with no test is not done, and a test
that maps to no AC is challenged in review as scope creep.

| AC | Requirement | Test | Level | Result |
|---|---|---|---|---|
| AC-1.1 | Create returns 201 with body | — | integration | pending |
| AC-1.2 | `Location` header present | — | controller | pending |
| AC-1.3 | Idempotent replay | — | integration | pending |
| AC-1.4 | Key reuse with different body → 409 | — | integration | pending |
| AC-1.5 | Code shape, non-derivable | — | unit | pending |
| AC-1.6 | Missing key → 401 | — | controller | pending |
| AC-2.1 | Resolve → 302 | — | controller | pending |
| AC-2.2 | `Cache-Control: no-store` | — | controller | pending |
| AC-2.3 | Unknown → 404 | — | controller | pending |
| AC-2.4 | Destination byte-identical | — | integration | pending |
| AC-2.5 | Resolve needs no auth | — | controller | pending |
| AC-3.1 | Alias used verbatim | — | integration | pending |
| AC-3.2 | Claimed alias → 409 | — | integration | pending |
| AC-3.3 | Reserved word → 422 | — | unit | pending |
| AC-3.4 | Malformed alias → 422 | — | unit | pending |
| AC-3.5 | Alias/generated namespaces disjoint | — | unit | pending |
| AC-4.1 | Non-http(s) → 422 | — | unit | pending |
| AC-4.2 | Private/metadata host → 422 | — | unit | pending |
| AC-4.3 | Encoded address forms → 422 | — | unit | pending |
| AC-4.4 | Over-length → 422 | — | unit | pending |
| AC-4.5 | Rule named, input not echoed | — | controller | pending |
| AC-5.1 | Stats readable by owner | — | integration | pending |
| AC-5.2 | Exactly-once increment | — | integration | pending |
| AC-5.3 | Non-owner → 404 not 403 | — | integration | pending |
| **AC-5.4** | **Analytics down → redirect still works** | — | **fault injection** | pending |
| AC-5.5 | Concurrent resolves lose no counts | — | integration | pending |
| AC-6.1 | Liveness | — | integration | pending |
| AC-6.2 | Readiness fails on dependency loss | — | fault injection | pending |
| AC-6.3 | Correlation ID echoed | — | controller | pending |
| **AC-6.4** | **Datastore down → 503, never a guess** | — | **fault injection** | pending |
| AC-6.5 | No URL or key in logs at INFO | — | unit | pending |
| AC-6.6 | Docs generated from implementation | — | integration | pending |

The two bolded rows are the ones that would be easiest to fake and hardest to notice
faking. They are fault-injection tests specifically so that a future refactor which
recouples the redirect to the counter fails CI rather than passing review.

## 2. Quality gate results

| Gate | Threshold | Result |
|---|---|---|
| Build | zero errors | pending |
| Format (Spotless) | zero violations | pending |
| Unit tests | 100 % pass | pending |
| Integration tests (Testcontainers) | 100 % pass | pending |
| Coverage — line | ≥ 85 % | pending |
| Coverage — branch | ≥ 75 % | pending |
| Smoke test | all checks pass | pending |

## 3. Performance measurement

Method, machine, JVM, container runtime and sample size stated with the numbers, per
`scripts/performance-test/README.md`. **No extrapolation to production scale.**

| Scenario | p50 | p95 | p99 | Error rate | Throughput |
|---|---|---|---|---|---|
| A — spread across 1 000 codes | — | — | — | — | — |
| B — single hot code | — | — | — | — | — |

The A-vs-B delta is the actual deliverable here: it measures the hot-row contention that
A-05 accepted as a trade-off, converting an assumption into a number.

## 4. Manual verification

- [ ] `docker compose up --build` from clean clone
- [ ] `./scripts/smoke-test.sh` passes
- [ ] `/swagger-ui.html` reflects the implemented contract
- [ ] Logs contain no destination URLs and no API keys

## 5. Known gaps at v1 close

To be recorded honestly at completion — what was not verified, and why.
