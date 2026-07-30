# Architecture Overview

**The system as built.** Written after implementation, from the code that exists — not from the
design that was intended. Where the two diverged, the divergence is recorded.

| | |
|---|---|
| Runtime | Java 21.0.11, Spring Boot 3.5.16 |
| Persistence | PostgreSQL 16, Flyway, Spring Data JPA |
| Production code | ~2 600 lines, 39 classes |
| Test code | ~3 900 lines, 251 tests |
| Delivery | Docker Compose |
| Status | Scenarios 01 and 02 complete; 03 (reliability) not started |

That test-to-production ratio of roughly 1.5 : 1 is not an accident of style. Most of it sits in
two places — the destination policy and the failure postures — because those are where being
wrong is expensive and where being wrong is invisible.

---

## 1. Context

```
   ┌──────────────┐   POST /api/v1/links              ┌─────────────────────┐
   │ API consumer │ ────────────────────────────────▶ │                     │
   │  (anonymous) │ ◀──────────────────────────────── │                     │
   └──────────────┘   201 + short URL                 │                     │      ┌────────────┐
                                                      │      SmartLink      │─────▶│ PostgreSQL │
   ┌──────────────┐   GET /{code}                     │  (stateless, one    │      │  16        │
   │ Link visitor │ ────────────────────────────────▶ │   instance)         │◀─────│  1 table   │
   │  (anonymous, │ ◀──────────────────────────────── │                     │      └────────────┘
   │   untrusted) │   302 + Location + no-store       │                     │
   └──────────────┘                                   │                     │
                                                      │                     │
   ┌──────────────┐   /actuator/health/{live,ready}   │                     │
   │   Operator   │ ────────────────────────────────▶ │                     │
   └──────────────┘                                   └─────────────────────┘
```

The two request paths differ in every dimension that matters, and the design is organised around
that rather than around resource CRUD:

| | Create | Resolve |
|---|---|---|
| Volume | low | the whole load |
| Trust | anonymous | anonymous **and hostile by default** |
| Cost of failure | caller retries | visitor never reaches their destination |
| Failure posture | fail loudly | fail safely, never guess |

---

## 2. Internal structure

A modular monolith. The dependency rule runs inward only and is **enforced by ArchUnit**, not by
convention — `LayeringTest` fails the build on violation.

```
                        ┌────────────────────────────────────────────────┐
   HTTP ───────────────▶│  api                                7 classes  │
                        │  LinkController · RedirectController           │
                        │  ApiExceptionHandler · ErrorCode               │
                        │  CorrelationIdFilter · 3 DTOs                  │
                        └──────────────────┬─────────────────────────────┘
                                           │
                        ┌──────────────────▼─────────────────────────────┐
                        │  application                        9 classes  │
                        │  CreateLink · ResolveLink · ReadAnalytics      │
                        │  6 exception types                             │
                        └──────────────────┬─────────────────────────────┘
                                           │
                        ┌──────────────────▼─────────────────────────────┐
                        │  domain                            12 classes  │
                        │  DestinationPolicy · HostLiterals              │
                        │  AddressPolicy · ShortCode · CodeGenerator     │
                        │  LinkLifecycle                                 │
                        │  ports: HostResolver · LinkRepository          │
                        │         TimeSource                             │
                        │  ── no Spring, no JPA, no I/O ──               │
                        └──────────────────▲─────────────────────────────┘
                                           │ implements ports
                        ┌──────────────────┴─────────────────────────────┐
                        │  infrastructure                     7 classes  │
                        │  JpaLinkRepository · ShortLinkEntity           │
                        │  SystemHostResolver · BoundedRetry             │
                        │  SystemTimeSource                              │
                        └────────────────────────────────────────────────┘
```

**Why the rule earns its cost.** `DestinationPolicy` and `CodeGenerator` carry the highest branch
density in the system, and 95 of the 251 tests exercise them — all with no Spring context and no
database. That is only possible because DNS sits behind `HostResolver` and storage behind
`LinkRepository`. A policy that could only be tested when DNS cooperated would be a policy whose
tests got skipped.

The rule also produces a compile-time guarantee: `LinkRepository.insert` takes a `Destination`,
and a `Destination` can only be obtained from `DestinationPolicy`. **There is no way to persist an
unvalidated URL** — GF-19 holds by construction rather than by review.

---

## 3. Control flow

### Create — `POST /api/v1/links`

```
  request ─▶ correlation-id filter ─▶ controller
                                         │
                   destination policy ───┼── refused ──▶ 422 + violated rule
                    (6 stages, §4)       │               (input never echoed)
                                         │
                   generate code ────────┤
                    (SecureRandom)       │
                                         │
                   INSERT ── unique violation ─▶ new code, retry (max 3) ─▶ 503
                    (own transaction)    │
                                         ▼
                                    201 + Location
```

**No step looks a destination up.** That absence is the implementation of GF-04: the same URL
submitted twice yields two independent links, because nothing ever asks whether it already exists.

### Resolve — `GET /{code}`

```
  request ─▶ correlation-id filter ─▶ controller (path must match [A-Za-z0-9]{7})
                                         │
                   lookup by code ───────┼── not found ──▶ 404
                    (1 jittered retry)   │
                                         ├── unavailable ─▶ 503, never a guess
                                         │
                   lifecycle check ──────┼── expired ────▶ 410, NO Location   ← v2
                    (TimeSource)         │
                                         │
                   increment counter ────┤
                    (atomic UPDATE) ─────┴── fails ──▶ log WARN, CONTINUE
                                         │
                                         ▼
                        302 + Location (byte-identical) + Cache-Control: no-store
```

The fail-open branch is the architecturally significant one, and it is **invisible in the code** —
an ordinary try/catch. `AnalyticsFailureIT` keeps it true by refusing every `UPDATE` at the
database and asserting the redirect still arrives.

The lifecycle check's **position** is equally load-bearing and equally invisible. Above the lookup
it would make an expired link indistinguishable from an unknown one, collapsing `410` back into
`404`. Below the increment it would count redirects that never happened — inflating the figure for
precisely the finished campaigns most likely to be examined afterwards.

---

## 4. The destination policy

The largest single piece of the system, and the reason is that a URL shortener is an **open
redirector by construction**. The policy cannot stop redirection; it bounds what can be redirected
to, and what a submitted string can do on the way through.

```
  raw input
    ├─▶ length bound            reject before parsing
    ├─▶ control characters      literal AND percent-encoded
    ├─▶ RFC 3986 parse
    ├─▶ scheme allowlist        http | https only
    ├─▶ host normalisation      → 4 or 16 bytes
    └─▶ address check           every resolved address, not the first
```

**Order is load-bearing: normalise, then decide.** A validator that decides first is inspecting a
string the rest of the system will never see. `HostLiterals` implements full `inet_aton` parsing,
so all of these are recognised as the cloud metadata endpoint and refused identically:

```
169.254.169.254   2852039166   0xA9FEA9FE   0251.0376.0251.0376
169.254.43518     169.16689662  [::ffff:169.254.169.254]
http://expected.com@169.254.169.254/          ← userinfo, not the host
```

`AddressPolicy` covers two ranges Java's own predicates miss entirely: IPv6 unique-local
(`fc00::/7`, which `isSiteLocalAddress()` does not recognise) and carrier-grade NAT (`100.64/10`).

---

## 5. Data model

One table. Two of its properties are **absences**, both asserted structurally by
`SchemaConstraintsIT` because an absence is exactly what a behavioural test cannot notice.

```
short_link
  id              bigserial     PK
  short_code      varchar(16)   NOT NULL UNIQUE   ← the collision authority
  destination_url varchar(2048) NOT NULL          ← stored byte-identical
  created_at      timestamptz   NOT NULL DEFAULT now()
  total_redirects bigint        NOT NULL DEFAULT 0  CHECK (>= 0)
  expires_at      timestamptz   NULL                 ← v2, nullable: NULL means never expires
```

**No `version` column.** `total_redirects` is written on every redirect, so optimistic locking
would make concurrent redirects of one link collide — failure rate rising with popularity, exactly
inverting NFR-08. The counter is updated by a single atomic `UPDATE … SET x = x + 1`.

**No column can hold personal data.** No IP, geography, user agent or referrer. Privacy enforced
by schema rather than by discipline: no future change can start collecting it without a migration
a reviewer would see.

---

## 6. Failure behaviour

| Condition | Response | Why |
|---|---|---|
| Destination refused by policy | `422` + rule name | Understood and declined — distinct from unparseable |
| Unparseable request | `400` | Different remedy for the caller |
| Unknown **or malformed** code | `404` | Identical on purpose — otherwise a probing oracle |
| Database unreachable | `503` | Come back. Never a guessed or stale destination |
| Code allocation exhausted | `503` | Nothing broken; retryable |
| Genuinely unanticipated | `500` | Reserved, so its presence in a log means something |
| **Link expired** | **`410`, no `Location`** | Existed and ended — distinct from "never existed", so a log tells a typo from a finished campaign |
| Invalid expiry supplied | `400` | The caller can fix it by sending different bytes |
| Counter write fails | **`302` anyway** | The redirect is the product; the counter is instrumentation |

**Liveness excludes the database; readiness includes it.** If liveness consulted the database, one
outage would make every instance report itself dead, the orchestrator would restart all of them,
and the restarts would add load to the database already struggling.

Retries: **one**, jittered, transient failures only. The cap is load-shedding as much as
resilience — three retries per request during an outage means three times the load on a failing
dependency and a caller who waits three times as long to be told to come back. The counter is
**not** retried at all: it is fail-open, so a retry buys nothing a visitor can perceive.

---

## 7. What is measured, and what is not

`scripts/performance-test/RESULTS.md` carries the detail. In summary:

- **Hot-key contention is measured**, not assumed: concentrating traffic on one link roughly
  doubles p95 and costs about a third of throughput, with correctness untouched.
- **Absolute latency is not a capability claim.** Identical code measured p95 55.7 ms and 507 ms
  in different runs, driven entirely by unrelated desktop load. The *ratio* held within 15 %; the
  absolutes did not.

---

## 8. Not built

Stated so absence reads as decision:

| | Why |
|---|---|
| Caching tier | Introduces stale reads, and a stale read here is a wrong redirect. Deferred until measurement justifies the coherency cost |
| Async analytics | The contention that would justify it is now measured; at this scale it does not yet |
| Read replicas | Must come *after* a cache, or a just-created link gets a false 404 from a lagging replica |
| Rate limiting | Scenario 03. No identity exists to attach a quota to |
| Multi-instance / multi-AZ | Statelessness is proven by construction; the deployment is not |
| Fetch-time re-validation | The TOCTOU gap (R-1b) is real and unfixable at creation time. Binding constraint on the first feature that fetches a destination |
| Homograph detection | A phishing control, not an injection control. A partial implementation gives false assurance |

---

## 9. Where the design changed under contact

The plan survived mostly intact. Three things did not, and each was found by a test rather than by
review:

1. **Readiness did not consult the database.** Spring's default readiness group contains only
   `readinessState`. The health check had been "passing" since T1 — against a healthy database.
2. **`@Transactional` + an inner catch cannot implement insert-and-retry.** A constraint violation
   marks the transaction rollback-only, so the collision was handled correctly and the request
   still failed. Replaced with a `TransactionTemplate` so the catch sits outside the boundary.
3. **`CannotCreateTransactionException` is not a `DataAccessException`.** Create returned 500 while
   resolve returned 503 for the same outage.

The full list of seven such defects is in
[`scenarios/01-greenfield/validation.md`](scenarios/01-greenfield/validation.md) §5.

---

## 10. Production evolution

Documented, not implemented. Each row names the **signal** that would justify the work, which is
what keeps this a plan rather than a wish list.

```
              Client
                │
         Load balancer                      [deferred]
                │
   ┌────────────┼────────────┐
 SmartLink   SmartLink   SmartLink          [deferred: replicas]
 (stateless — unchanged from today)          design is ready now
   └────────────┼────────────┘
                │
        Redis read-through                  [deferred]
        TTL · negative cache
        stampede protection
                │ on miss
        PG replica ── PG primary            [deferred]
                          │
                 Async analytics            [deferred]
```

| Signal | Action |
|---|---|
| Redirect p95 or DB load exceeds target | Add read-through cache |
| A hot link overwhelms one cache node | Cache replication / partitioning / edge |
| Counter contention affects redirect p95 | Move analytics to an async pipeline |
| One instance cannot meet peak | Stateless replicas behind a load balancer |
| Primary reads become the bottleneck | Read replicas for cache misses |
| Regional outage exceeds tolerance | Multi-AZ, then multi-region per RTO/RPO |
| Abuse appears | Authentication, quotas, distributed rate limiting |

**The application box is unchanged between today and that diagram.** Statelessness is a property
the service has now — asserted by ArchUnit — so horizontal scaling is a deployment change, not a
rewrite. Everything else on it is a design commitment.

---

## 11. Evolution log

| Version | Change | Sections affected |
|---|---|---|
| v1 | Create, resolve, basic analytics, destination policy, resilience | all |
| **v2** | **Optional expiration** — nullable `expires_at`, `TimeSource` port, lifecycle check on resolve, `410 Gone` | §3 resolve flow, §5 data model, §6 failure table |
| v3 | *not started* — reliability posture | §6, §8 |

### What v2 changed, and what it deliberately did not

Delivered expand-only: one nullable column, no backfill, no contract step. **Verified by
rehearsal, not by assertion** — the pre-change jar was run against the migrated schema and
resolved every existing link, including one carrying an expiry it knows nothing about (which
resolves as non-expiring during a rollback window; a known, accepted consequence).

The resolve path gained exactly one branch, placed between the verified lookup and the counter
increment. Before the lookup, an expired link would be indistinguishable from an unknown one;
after the increment, redirects that never happened would be counted.

Unchanged: `302` and `Cache-Control` for active links, `404` for unknown *and* malformed codes,
`503` on datastore failure, and the fail-open counter. `AnalyticsFailureIT` passes untouched,
which is what proves the last of those survived the new branch.
