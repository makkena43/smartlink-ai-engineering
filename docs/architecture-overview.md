# Architecture Overview

**This document describes the system as it is now.** It is amended at each scenario, never
duplicated per version — a stale architecture document is worse than none, because a reader
cannot tell which one is load-bearing. The *evolution* lives in three other places, each
with a different job:

| Where | Tense | Job |
|---|---|---|
| `docs/decisions.md` | past, append-only | ADRs. Immutable once accepted; superseded, never edited |
| `docs/scenarios/*/impact-analysis.md` | delta | What a change touches and what it risks |
| git history | audit | The actual diff, per task |

**Current state: v1 scaffold complete, v1 logic pending (T-02 onward).**

---

## 1. Context

```
   ┌──────────────┐   POST /api/v1/links      ┌─────────────────┐
   │ API consumer │ ─────────────────────────▶│                 │
   │ (key holder) │◀───────────────────────── │                 │
   └──────────────┘   201 + short URL         │                 │       ┌────────────┐
                                              │    SmartLink    │──────▶│ PostgreSQL │
   ┌──────────────┐   GET /{code}             │                 │       │  (system   │
   │ Link visitor │ ─────────────────────────▶│                 │◀──────│ of record) │
   │ (anonymous)  │◀───────────────────────── │                 │       └────────────┘
   └──────────────┘   302 + Location          │                 │
                                              │                 │
   ┌──────────────┐   /actuator/health/*      │                 │
   │   Operator   │ ─────────────────────────▶│                 │
   └──────────────┘                           └─────────────────┘
```

The two request paths differ in every dimension that matters, and the architecture is
organised around that fact rather than around resource CRUD:

| | Create path | Resolve path |
|---|---|---|
| Caller | authenticated, few | anonymous, many |
| Trust | known key holder | hostile by default |
| Volume | low | the whole load |
| Cost of failure | caller retries | user does not reach their destination |
| Latency budget | 250 ms | 100 ms |

---

## 2. Internal structure

A **modular monolith**. Chosen deliberately, not by default — see ADR-007. The module
boundaries are real and enforced; the deployment unit is one process because nothing yet
justifies the operational cost of more.

```
                        ┌──────────────────────────────────────────┐
   HTTP ───────────────▶│  api                                     │
                        │  controllers · problem+json · filters    │
                        └───────────────────┬──────────────────────┘
                                            │ commands / queries
                        ┌───────────────────▼──────────────────────┐
                        │  application                             │
                        │  CreateLink · ResolveLink · ReadStats     │
                        │  ports (interfaces)                       │
                        └───────────────────┬──────────────────────┘
                                            │
                        ┌───────────────────▼──────────────────────┐
                        │  domain                                  │
                        │  ShortCode · Alias · Destination · Link   │
                        │  code generation · URL policy             │
                        │  ── no framework, no I/O ──               │
                        └───────────────────▲──────────────────────┘
                                            │ implements ports
                        ┌───────────────────┴──────────────────────┐
                        │  infrastructure                          │
                        │  JPA adapters · Flyway · clock · keys     │
                        └──────────────────────────────────────────┘
```

**The dependency rule runs inward only.** `domain` imports nothing — not Spring, not JPA.
That is not architectural fashion: it is what makes code generation, alias rules and URL
policy testable with no context and no container, which is what keeps the unit suite fast
enough to run on every save. Everything expensive to test is pushed to the edges where
there is less of it.

---

## 3. Control flow

### Create — `POST /api/v1/links`

```
  request ─▶ correlation-id filter ─▶ API-key auth ─▶ controller
                                                          │
                              validate destination (scheme, address ranges) ─┐
                                                          │                  │ 422
                              validate alias / reserve namespace ────────────┤
                                                          │                  │ 409
                              idempotency-key lookup ──────────────────────── ┤
                                                          │                  │ 409 on body mismatch
                              allocate code ──▶ insert ──▶ unique violation? ─┘
                                                          │        │
                                                          │        └─▶ retry, bounded
                                                          ▼
                                                     201 + Location
```

### Resolve — `GET /{code}`

```
  request ─▶ correlation-id filter ─▶ (no auth) ─▶ lookup by code
                                                        │
                                        ┌───────────────┼───────────────┐
                                     found          not found      datastore down
                                        │               │               │
                             record resolution         404            503
                             (fail-open ─ A-05)                  never a guess
                                        │
                                        ▼
                              302 + Location + Cache-Control: no-store
```

The fail-open branch is the architecturally interesting one. A counter write failure is
logged and swallowed; the redirect proceeds. Blocking a user from a page that is perfectly
available, in order to protect a number, serves nobody. This is enforced by a
fault-injection test (AC-5.4) rather than by code-review convention, because conventions
do not survive refactors.

---

## 4. Key decisions and where they live

| Decision | Where | Reversibility |
|---|---|---|
| 302 over 301 | ADR-001 | one-way toward 301 only |
| Codes never reused | ADR-002 | **one-way door** |
| Alias namespace disjoint from generated | ADR-003 | reversible |
| Analytics synchronous, fail-open | ADR-004 | reversible |
| No PII in analytics | ADR-005 | **one-way if reversed** |
| Destination scheme + address policy | ADR-006 | reversible to widen |
| Modular monolith | ADR-007 | costly |
| PostgreSQL as system of record, no cache in v1 | ADR-008 | reversible |
| Random codes, DB-enforced uniqueness | ADR-009 | costly |

---

## 5. What this architecture does not yet do

Stated plainly so absence reads as decision rather than oversight:

- **No caching tier.** v1 reads from the system of record. Read-through caching with
  stampede protection is the documented evolution, introduced when measurement justifies it
  — not before.
- **No async analytics.** Counters are written synchronously on the read path. The
  contention this causes is measured by performance scenario B rather than assumed.
- **Single deployment unit, single AZ.** The service is stateless on the read path (NFR-4),
  so horizontal scaling is available; it is simply not demonstrated by a laptop.
- **No rate limiting.** v3.
- **No expiry.** v2.

## 6. Evolution log

| Version | Change | Amended sections |
|---|---|---|
| v1 | Initial architecture — create, resolve, basic analytics | all |
| v2 | *pending* — expiration | §2 data model, §3 resolve flow |
| v3 | *pending* — reliability posture | §3, §5 |
