# Greenfield Engineering Spec — SmartLink URL Shortener

- **Scenario:** 01 — Greenfield
- **Status:** Draft — awaiting Gate B
- **Engineer of record:** Srinivas Makkena
- **Requirements:** [`requirements.md`](requirements.md)

Requirements state *what* and *why*. This document states *how*, and is accountable to every
requirement ID it claims to satisfy.

---

## 1. Scope and requirement references

### 1.1 What this spec commits to building

| Requirement | Satisfied by |
|---|---|
| GF-01, GF-02 | §6.1 create endpoint · §7 data model · §5.1 flow |
| GF-03 | §6 — no authentication on any endpoint |
| GF-04 | §5.1 — no destination lookup before insert; each request inserts |
| GF-05, GF-06 | §7.2 — unique index on `code`; insert-and-retry, never check-then-insert |
| GF-07, GF-08 | §6.2 — HTTP 302 with `Location`; no interstitial, no client-side script |
| GF-09 | §6.2 — 404, no redirect |
| GF-10 | §9.1 — destination validation policy |
| GF-11, GF-12 | §6.3 — unauthenticated stats endpoint |
| GF-13 | §9.3 — liveness and readiness probes |
| NFR-01 | §7 — PostgreSQL as system of record; no in-memory mapping store |
| NFR-02 | §8.2 — 503 on unresolvable mapping; never a guessed destination |
| NFR-03 | §8.3 — bounded retry policy |
| NFR-04 | §9.2 — error schema |
| NFR-05 | §6.2 — standard HTTP semantics only |
| NFR-06 | §4 — stateless application tier |
| NFR-07, NFR-08 | §8.4, §8.5 — documented production evolution |
| NFR-09 | §8.6 — documented; not implemented per requirements §6 |
| NFR-10 | §10 — SLI/SLO definitions |
| NFR-11 | §11 — test strategy |
| NFR-12 | §11.4 — documented run and validate path |
| NFR-13 | §7.1 — schema stores no personal data |

### 1.2 Decisions this spec makes that requirements deferred

Requirements §5 delegates these explicitly: *"Exact traffic targets, SLI/SLO values,
short-code format, redirect status behavior, persistence technology, deployment mechanics,
retry settings, and error-response schema are engineering-spec decisions."*

They are settled in §2.

### 1.3 Decisions this spec makes that go beyond the literal requirement text

Flagged separately, because a spec quietly widening its own mandate is how scope creep
becomes invisible. Each is an engineering judgment about *how* to satisfy a stated
requirement, not a new requirement:

| Decision | Stated requirement it implements | Why it goes further |
|---|---|---|
| Reject private / loopback / link-local / metadata destination addresses (§9.1) | GF-10 (input validation) | `http://169.254.169.254/` is well-formed and uses a supported scheme, so a literal reading of GF-10 permits it. It is the cloud instance-metadata endpoint |
| Analytics failure must not fail a redirect (§5.2) | NFR-02 + GF-11 | Neither says what happens when the *counter write* fails. The obvious implementation — one transaction over lookup and increment — would fail a redirect whose destination was perfectly available |
| Destination URLs are not logged at INFO (§9.2) | NFR-04 | NFR-04 governs what errors expose to a client. Logs are a different and more persistent exposure; destination query strings routinely carry tokens |
| Short codes are unguessable and never reassigned (§2, §7.2) | GF-05 + GF-12 | With anonymous creation and unauthenticated analytics, possession of the code is the only access control that exists |

If any of these is unwanted, it is removed at Gate B rather than discovered in the diff.

---

## 2. Key engineering decisions

| # | Decision | Choice | Rationale |
|---|---|---|---|
| D-01 | Redirect status | **302 Found** + `Cache-Control: no-store` | A cached 301 stops reaching the service, so GF-11's count silently undercounts by an unmeasurable margin. 302 → 301 later is a config change; the reverse is impossible for clients that already cached |
| D-02 | Short-code format | 7 chars, base62 `[A-Za-z0-9]`, **random** | 62⁷ ≈ 3.5 × 10¹². Sequential codes would make the corpus and its traffic figures walkable by counting — decisive because GF-03 + GF-12 leave the code as the only access control |
| D-03 | Uniqueness enforcement | Unique DB index + insert-and-retry | Check-then-insert is a race: two requests can both observe a code as free. The index makes the collision impossible rather than unlikely (GF-05, GF-06) |
| D-04 | Persistence | PostgreSQL 16, Flyway migrations, Spring Data JPA | Transactional, indexed, durable across restarts (NFR-01). Flyway makes schema review and local startup predictable |
| D-05 | Cache | **None in the prototype** | Requirements §6 places production cache out of scope. Documented as the first production evolution step in §8.4 |
| D-06 | Analytics | Synchronous counter on the link row, **fail-open** | Simple and immediately consistent at prototype scale. Contention is the known cost and is measured (§10.3), not assumed |
| D-07 | Error schema | RFC 9457 `application/problem+json` | Machine-readable, standard, and carries a named rule without leaking internals (NFR-04) |
| D-08 | Retry policy | Asymmetric — see §8.3 | The redirect path and the create path have different failure economics and must not share a policy |
| D-09 | Application tier | Stateless; no session, no local mapping store | Any instance can serve any request, which is the whole content of NFR-06 |
| D-10 | Code reassignment | Never | A printed link outlives the service's memory of it. Reissuing a code silently redirects every historical holder somewhere chosen by someone else |

---

## 3. Architecture

### 3.1 Prototype architecture — what is actually built

```
   ┌───────────────┐
   │ Link creator  │  POST /api/v1/links
   └───────┬───────┘
           │
   ┌───────▼─────────────────────────────────────────────┐
   │  SmartLink application  (single instance, stateless) │
   │                                                      │
   │   api ──▶ application ──▶ domain ◀── infrastructure  │
   │                                                      │
   │   • destination validation      • bounded retries    │
   │   • code generation             • problem+json       │
   │   • correlation ID filter       • health probes      │
   └───────┬──────────────────────────────────────────────┘
           │  JDBC (bounded pool, short timeouts)
   ┌───────▼────────┐
   │  PostgreSQL 16 │   system of record — durable across restarts (NFR-01)
   │  links table   │
   └────────────────┘

   ┌───────────────┐
   │ Link recipient│  GET /{code}   ──▶  302 + Location
   └───────────────┘

   ┌───────────────┐
   │   Operator    │  GET /actuator/health/{liveness,readiness}
   └───────────────┘
```

Everything above is built, tested and demonstrable via `docker compose up`.

### 3.2 Production evolution architecture — **not implemented**

Documented to show the scale path required by NFR-06, NFR-07 and NFR-08. Every component
marked `[deferred]` is out of scope per requirements §6.

```
                        ┌──────────────┐
                        │    Client    │
                        └──────┬───────┘
                               │
                     ┌─────────▼──────────┐
                     │   Load balancer    │  [deferred]
                     └─────────┬──────────┘
              ┌────────────────┼────────────────┐
     ┌────────▼───────┐ ┌──────▼─────────┐ ┌────▼───────────┐
     │  SmartLink #1  │ │  SmartLink #2  │ │  SmartLink #n  │  [deferred: replicas]
     │   (stateless)  │ │   (stateless)  │ │   (stateless)  │   design is ready today
     └────────┬───────┘ └──────┬─────────┘ └────┬───────────┘
              └────────────────┼────────────────┘
                               │
                 ┌─────────────▼──────────────┐
                 │  Distributed cache (Redis) │  [deferred]
                 │  read-through · TTL        │
                 │  negative cache            │
                 │  stampede protection       │
                 └─────────────┬──────────────┘
                               │ on miss
              ┌────────────────┼────────────────┐
     ┌────────▼───────┐              ┌──────────▼─────────┐
     │ PG read replica│  [deferred]  │    PG primary      │
     │ (cache misses) │              │ (writes, fallback) │
     └────────────────┘              └──────────┬─────────┘
                                                │
                                     ┌──────────▼─────────┐
                                     │ Async analytics    │  [deferred]
                                     │ event pipeline     │
                                     └────────────────────┘
```

**The one thing this diagram is built to make honest:** the application box is the *same*
box in both diagrams. Statelessness is a property the prototype has today, so the horizontal
scaling in NFR-06 is a deployment change rather than a rewrite. Everything else on this
diagram is a claim about a design, not about a running system, and is labelled accordingly.

**A note on cache versus read replicas.** For hot links (NFR-08), the cache matters more than
replica count: a single popular link can generate traffic that a cache serves from memory
while protecting *every* database node. Adding replicas without a cache spreads hot-key load
across more machines rather than removing it.

**Replica lag caveat.** Once read replicas exist, a link created on the primary and
immediately resolved could miss on a lagging replica and return a false 404. The mitigation
is to write the mapping to cache at creation time, so the read path finds it without
consulting a replica at all. Recorded now because it constrains the order in which those two
components can safely be introduced — cache first, replicas second.

---

## 4. Component responsibilities

The dependency rule runs inward only. `domain` imports no framework and performs no I/O.

| Package | Responsibility | May depend on |
|---|---|---|
| `com.smartlink.domain` | `ShortCode` generation and format · `Destination` validation policy · `Link` invariants | nothing |
| `com.smartlink.application` | `CreateLinkUseCase` · `ResolveLinkUseCase` · `ReadStatsUseCase` · port interfaces · retry orchestration | domain |
| `com.smartlink.infrastructure` | JPA entities and repositories · Flyway · clock · retry adapters | domain (implements its ports) |
| `com.smartlink.api` | Controllers · request/response records · problem+json mapping · correlation-ID filter | application |

This is not decoration. Destination validation and code generation carry the highest branch
density in the system — encoded address forms, scheme rules, charset rules — and the layering
is what lets them be tested with **no Spring context and no database**, which is what makes
running the suite on every save realistic.

---

## 5. Flows

### 5.1 Create link — `POST /api/v1/links`

```
  request
    │
    ├─▶ correlation-ID filter                                    (GF-18 equivalent, §9.3)
    │
    ├─▶ parse + validate destination ──────────── invalid ──▶ 422 problem+json   (GF-10)
    │      scheme · length · address ranges
    │
    ├─▶ generate short code (random base62-7)                    (D-02)
    │
    ├─▶ INSERT ──── unique violation ──▶ regenerate, retry (max 3 total)  (D-03, GF-06)
    │                                        │
    │                                   exhausted ──▶ 503
    │      transient DB failure ──▶ 1 retry ──▶ still failing ──▶ 503
    │
    └─▶ 201 Created + Location + body                            (GF-01, GF-02)
```

**No lookup by destination occurs anywhere in this flow.** That is what satisfies GF-04:
resubmitting the same destination inserts a second, independent row, because nothing ever
asks whether the destination already exists.

### 5.2 Resolve — `GET /{code}`

```
  request
    │
    ├─▶ correlation-ID filter
    │
    ├─▶ SELECT by code ───── not found ──────────▶ 404, no redirect        (GF-09)
    │      │
    │      ├── transient failure ──▶ 1 retry (jittered backoff)           (NFR-03)
    │      │        └── still failing ──▶ 503, never a guess              (NFR-02)
    │      ▼
    │    found
    │      │
    │      ├─▶ increment counter ─── failure ──▶ log at WARN, continue    (D-06)
    │      │                                     ▲
    │      │                            fail-open: the redirect is the product,
    │      │                            the counter is instrumentation
    │      ▼
    └─▶ 302 Found · Location: <exact destination> · Cache-Control: no-store   (GF-07, GF-08)
```

The fail-open branch is the architecturally significant one and is **invisible in the
code** — it looks like an ordinary try/catch. It is therefore enforced by a fault-injection
test (§11.2), not by review convention, because conventions do not survive refactors.

`302` rather than an HTML page with a meta-refresh or script satisfies GF-08: a standards-
compliant client follows it with no client-side software and no separate browser context.

### 5.3 Stats — `GET /api/v1/links/{code}/stats`

Direct read of the counter columns. No authentication (GF-12). Unknown code → 404.

---

## 6. API contract

Generated from the implementation at `/v3/api-docs`; browsable at `/swagger-ui.html`. This
section is the human-readable summary — where the two disagree, the generated document wins
and this section is the bug.

| Method | Path | Auth | Requirement |
|---|---|---|---|
| `POST` | `/api/v1/links` | none | GF-01, GF-02, GF-03 |
| `GET` | `/{code}` | none | GF-07, GF-08, GF-09 |
| `GET` | `/api/v1/links/{code}/stats` | none | GF-11, GF-12 |
| `GET` | `/actuator/health/liveness` · `/readiness` | none | GF-13 |

Resolution is mounted at the root so short links stay short — a prefix would defeat the
product's only reason to exist. Everything else lives under `/api/v1`, and **route matching
takes precedence over code resolution**, so a code can never shadow an operational endpoint.

### 6.1 Create

```http
POST /api/v1/links
Content-Type: application/json

{ "destinationUrl": "https://example.com/campaign?utm_source=email" }
```

```http
HTTP/1.1 201 Created
Location: /api/v1/links/K3xR7pQ

{
  "code": "K3xR7pQ",
  "shortUrl": "http://localhost:8080/K3xR7pQ",
  "destinationUrl": "https://example.com/campaign?utm_source=email",
  "createdAt": "2026-07-30T09:14:22Z"
}
```

| Status | When |
|---|---|
| `201` | Created |
| `422` | Invalid destination — scheme, length, or blocked address range |
| `400` | Malformed request body |
| `503` | Dependency unavailable, or code allocation exhausted its retries |
| `500` | Unexpected internal failure only — never used for a known dependency outage |

### 6.2 Resolve

```http
GET /K3xR7pQ
```
```http
HTTP/1.1 302 Found
Location: https://example.com/campaign?utm_source=email
Cache-Control: no-store
```

| Status | When |
|---|---|
| `302` | Resolved |
| `404` | Unknown code (GF-09) |
| `503` | Mapping could not be verified (NFR-02, NFR-03) |

The destination is returned **byte-identical**, including query string and fragment (GF-07).
Normalising it would silently break signed URLs and tracking parameters.

### 6.3 Stats

```json
{ "code": "K3xR7pQ", "totalRedirects": 1432 }
```

Counters only. No IP, user-agent, referrer, geography or device (NFR-13).

---

## 7. Data model

### 7.1 `links`

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | internal only, never exposed |
| `code` | `varchar(16)` **UNIQUE NOT NULL** | the public handle |
| `destination_url` | `text NOT NULL` | byte-preserved |
| `created_at` | `timestamptz NOT NULL` | UTC, database clock |
| `total_redirects` | `bigint NOT NULL DEFAULT 0` | GF-11 |

**No column stores personal data** — that is NFR-13 enforced by schema rather than by
discipline. There is nowhere to put an IP address, so no future code can casually start
storing one without a migration that a reviewer would see.

`varchar(16)` against a 7-character format leaves room for a format change without a type
migration, at no cost.

### 7.2 Constraints and indexes

| Constraint | Purpose |
|---|---|
| `UNIQUE (code)` | GF-05 — one code, one destination. Enforced by the database, not by application check-then-insert |
| PK on `id` | internal identity, stable under code-format change |

Uniqueness is the database's job because check-then-insert is a race under concurrency
(GF-06). Insert-and-retry converts a *probabilistic* correctness argument into a
*structural* one, and §11.2 forces a collision to prove it.

### 7.3 Schema evolution

Flyway, forward-only, additive-first. Scenario 02 adds expiration as a nullable column, so no
`NOT NULL` here presumes expiry's absence.

---

## 8. Resilience, scale, caching and retry design

### 8.1 Failure posture

**Never redirect to an unverified destination** (NFR-02). When the mapping cannot be read
with confidence, the service returns `503` and says so. It does not guess, does not serve a
stale value, and does not have a cache from which a stale value could come.

### 8.2 Timeouts

| Setting | Value | Reason |
|---|---|---|
| Connection acquisition | 2 s | The 30 s default converts one database outage into service-wide thread-pool exhaustion |
| Query timeout (resolve) | 1 s | The resolve path is latency-sensitive; a slow read is a failed read |
| Validation timeout | 1 s | Detect a dead connection quickly |

### 8.3 Retry policy — deliberately asymmetric (NFR-03, D-08)

The two paths have different failure economics, so a shared policy would be wrong for one of
them.

**Resolve path — at most one retry.**

| Rule | Value |
|---|---|
| Attempt 1 | immediate |
| Attempt 2 | after a short jittered backoff (~50 ms base, full jitter) |
| Then | fail with `503` |
| Retryable | transient only — connection reset, connection timeout, transient SQL states |
| **Not retryable** | validation errors, not-found, constraint violations |

Capped at one retry because the resolve path carries the entire load. Under a database
outage, three or more retries per request amplify load against an already-failing dependency,
hold application threads for the duration, and delay the `503` the client needs in order to
fail fast. **Aggressive retries turn a degraded dependency into a total outage** — the retry
policy is a load-shedding decision as much as a resilience one.

Jitter is not cosmetic: without it, every in-flight request retries at the same instant and
the retry itself becomes a synchronised thundering herd.

**Create path — bounded, with a separate collision allowance.**

| Rule | Value |
|---|---|
| Code collision | regenerate and retry, **3 attempts total**, then `503` |
| Transient DB failure | **1 retry** |
| Malformed request | never retried |
| Dependency unavailable | `503` |
| Unexpected internal failure | `500` — and only this case |

A collision retry is not a failure retry: nothing is wrong, the dice simply came up used.
Separating the two allowances keeps a genuine outage from consuming the collision budget.

The `503`/`500` split matters operationally: `503` is "come back", `500` is "someone needs to
look at this". Collapsing them destroys the signal that decides whether a page is warranted.

### 8.4 Caching — documented, not implemented (D-05)

Out of scope per requirements §6. The design, for when measurement justifies it:

```
  1. Resolve request arrives
  2. Look up code in the distributed cache
  3. Hit  ──▶ redirect immediately
  4. Miss ──▶ read from replica / system of record
  5. Found     ──▶ populate cache with TTL, then redirect
  6. Not found ──▶ 404, optionally with a short negative-cache entry to protect
                   the database from repeated invalid-code traffic
```

Three properties any implementation must have, recorded now because each is easy to omit and
expensive to retrofit:

- **Stampede protection.** On expiry of a hot key, exactly one request refreshes it while
  others wait briefly or serve the stale value. Without this, a hot key's expiry is a
  synchronised burst against the database — the cache converts a steady load into a spike.
- **Write-through at creation.** Populate the cache when a link is created, so a
  just-created link is never served a false `404` by a lagging replica.
- **A stale entry is a wrong redirect.** Once a cache exists, NFR-02 stops being free. TTL
  becomes a correctness bound, not a performance knob — which is precisely why the cache is
  deferred rather than added speculatively.

### 8.5 Scale path (NFR-06, NFR-07, NFR-08)

Workload assumption, stated as an assumption:

| Dimension | Assumed |
|---|---|
| Read : write ratio | **≥ 100 : 1** redirects to creations |
| Create workload | low to moderate, bursty |
| Resolve workload | high volume, latency-sensitive |
| Hot links | a small share of links receive most traffic |
| Geography | one primary region |
| Peak pattern | short bursts from campaign, SMS, email or social sharing |

The prototype does **not** claim to meet a production TPS figure. It demonstrates the
architecture and the validation approach needed to evolve toward one.

Evolution is triggered by measured signals, not by preference:

| Signal | Decision |
|---|---|
| Redirect latency or database load exceeds target | Add read-through cache |
| A hot link overwhelms one cache node | Cache replication / partitioning / edge caching |
| Analytics writes affect redirect latency | Move analytics to an async pipeline |
| One instance cannot meet peak TPS | Add stateless replicas behind a load balancer |
| Primary database reads become the bottleneck | Add read replicas for cache misses |
| One-region outage exceeds business tolerance | Multi-AZ, then multi-region, per RTO/RPO |
| Abuse or excessive request rates appear | Authentication, quotas, distributed rate limiting |

Each row names the evidence that would justify the work. That is what keeps this a scale
*plan* rather than a wish list — nothing here gets built because it sounds advanced.

### 8.6 Abuse protection — documented, not implemented (NFR-09)

Out of scope per requirements §6. Production approach: per-client rate limiting at the edge,
distributed counters shared across instances, destination screening against known-malicious
lists, and quotas once identity exists. The prototype has no identity (GF-03), so a
per-creator quota has no subject to attach to — which is itself the reason NFR-09 is a design
obligation here rather than an implemented one.

---

## 9. Security and observability design

### 9.1 Destination validation (GF-10)

| Rule | Reject |
|---|---|
| Scheme | anything other than `http` or `https` — notably `javascript:`, `data:`, `file:` |
| Length | beyond a documented maximum (2 048 characters) |
| Address range | hosts resolving to private, loopback, link-local or cloud-metadata ranges |
| Encoded forms | decimal, octal and IPv6-mapped encodings of the above |

A shortener is an open redirector by construction — that is its function, not a defect. Two
things follow that GF-10's literal text does not cover:

- Without scheme restriction it is a **stored-XSS delivery mechanism**: a `javascript:`
  payload behind a link the recipient was told to trust.
- Without address restriction it is an **SSRF pivot** the moment any server-side component
  fetches a destination — which link-preview or metadata enrichment plausibly will.
  `http://169.254.169.254/` is well-formed, uses a supported scheme, and is the cloud
  instance-metadata endpoint.

Encoded forms are named explicitly because `http://2852039166/` and `http://0xA9FEA9FE/`
are the same address, and a validator that inspects only the hostname string rejects neither.

Validation is performed against the **resolved address**, not the hostname string.

### 9.2 Error handling and log hygiene (NFR-04)

RFC 9457 `application/problem+json`. Every error response:

- names the violated rule so a caller can branch programmatically rather than string-match prose;
- **never** contains a stack trace, database message, SQL state, internal hostname, or connection detail;
- **never** echoes raw user input unescaped — reflecting an attacker-supplied destination into an error body is how a validation endpoint becomes an XSS vector;
- carries the correlation ID, which is what lets an error stay diagnosable while remaining opaque.

**Destination URLs are not written to logs at INFO or below.** They are attacker-controlled
and routinely carry credentials in query strings — password-reset tokens, signed URLs,
session identifiers. Logging them reproduces those secrets into every log sink, backup and
aggregation pipeline the service touches. NFR-04 governs what a client sees; this governs
what the service records about itself, which is the more persistent exposure.

### 9.3 Observability (GF-13, NFR-10)

| Surface | Behaviour |
|---|---|
| `/actuator/health/liveness` | process health only — never fails on a dependency outage, or the orchestrator restarts a healthy process during a database blip |
| `/actuator/health/readiness` | fails when the database is unreachable, so a load balancer stops routing |
| Correlation ID | generated per request, echoed when supplied, present on every response and every log line |
| Actuator exposure | `health` and `info` only — the full actuator surface is not something to expose by default |

The liveness/readiness distinction is the one that matters. Conflating them means a
transient database outage triggers a restart storm across every instance, converting a
recoverable dependency failure into a self-inflicted outage.

---

## 10. SLI / SLO and validation strategy (NFR-10)

### 10.1 Definitions

**Design targets and discussion points. Not contractual SLAs. A prototype on a laptop proves
none of them.**

| Signal | SLI | Proposed production SLO | What the prototype demonstrates |
|---|---|---|---|
| Redirect availability | successful redirects ÷ eligible redirect requests | 99.9 % monthly | Correct error semantics and health behaviour — **not** multi-AZ availability |
| Redirect latency | p95 server-side resolution | < 100 ms | Local measurement, environment stated |
| Creation latency | p95 `POST /api/v1/links` | < 250 ms | Functional response plus modest local load |
| Error rate | 5xx ÷ total | < 0.1 % over the window | Errors surfaced clearly; users never misdirected |

### 10.2 What is actually verifiable here

| # | Property | Verified by |
|---|---|---|
| V-1 | Redirect survives analytics failure | Fault injection |
| V-2 | Datastore down → `503`, never a wrong destination | Fault injection |
| V-3 | Retry policy is bounded and does not retry non-transient errors | Unit + fault injection |
| V-4 | No destination URL in logs at INFO | Log assertion |
| V-5 | Concurrent creates produce no conflicting mapping | Concurrency integration test |
| V-6 | Behaviour under a **forced** code collision is correct | Integration test with a stubbed generator |
| V-7 | Readiness reflects dependency state | Fault injection |

V-6 is phrased around behaviour rather than probability on purpose. A collision-probability
argument fails silently; a test that forces the collision and asserts recovery does not.

### 10.3 Performance measurement

Two scenarios, because the delta is the actual result:

| Scenario | Purpose |
|---|---|
| **A** — load spread across many codes | Baseline read cost |
| **B** — load concentrated on one hot code | Isolates the row contention that D-06 knowingly accepted |

Reported with machine, core count, JVM, container runtime, whether the database shares the
host, and sample size. **No extrapolation to production scale.** Scenario B converts NFR-08
from a claim into a number.

---

## 11. Test strategy and quality gates (NFR-11)

### 11.1 Levels

| Level | Covers | Speed |
|---|---|---|
| Unit | Code generation, destination policy, retry classification, error mapping | ms — no Spring, no database |
| Controller | Status codes, headers, `Location`, problem+json shape | fast |
| Integration | Persistence, Flyway, unique-code races, concurrency | seconds — real PostgreSQL via Testcontainers |
| Fault injection | Analytics down, datastore down, readiness transitions, retry exhaustion | seconds |
| Smoke | Full reviewer path against the running stack | seconds |
| Performance | Bounded local load, scenarios A and B | minutes, run deliberately |

### 11.2 Tests that exist to stop a regression nobody would notice

| Test | Asserts | Why a test rather than a convention |
|---|---|---|
| `AnalyticsFailureIT` | Counter write fails → redirect still `302` with correct `Location` | The fail-open posture is invisible in the code. A refactor wrapping resolution in one transaction reverses it, and nothing else would catch that |
| `DatastoreUnavailableIT` | Database down → `503`, never a stale or guessed destination | "Never redirect wrongly" is a property, and a property is only real when something enforces it |
| `RetryPolicyTest` | Non-transient failures are **not** retried; transient ones retry exactly once | The dangerous bug is over-retrying, and it is invisible until an outage |
| `ConcurrentCreateIT` | N parallel creates → N distinct codes, zero conflicts | GF-06 |
| `ForcedCollisionIT` | Generator stubbed to repeat → insert-and-retry recovers | GF-05, V-6 |

### 11.3 Quality gates

| Gate | Threshold |
|---|---|
| Build | zero errors |
| Format — Spotless | zero violations |
| Unit + controller | 100 % pass |
| Integration (Testcontainers) | 100 % pass |
| Coverage — line / branch | ≥ 85 % / ≥ 75 % on domain and application |
| Smoke | all checks pass |
| Performance | method, machine and sample size reported; no extrapolated claims |

Coverage is a **floor, not a target**. A high number over weak assertions is worse than a
lower number over strong ones, because it converts "we did not check" into "we checked and it
was fine".

**Known hole, stated rather than hidden:** `jacoco:check` skips silently when no execution
data exists, so with an empty suite the coverage gate passes vacuously. Tolerable only until
T-02. From then on, a suite that stops producing execution data is a **failure, not a skip**.

### 11.4 Run and validate (NFR-12)

```bash
cp .env.example .env
docker compose up --build
./scripts/smoke-test.sh
mvn verify
```

---

## 12. Task decomposition and dependencies

Full task envelopes — intent, constraints, acceptance criteria, technical context — in
[`task-decomposition.md`](task-decomposition.md).

```
T-01 scaffold ✅ ──┬─▶ T-02 domain: code + destination policy ──┐
                   ├─▶ T-03 schema (Flyway) ───────────────────┤
                   └─▶ T-04 persistence adapter + ports ───────┤
                                                               │
        ┌──────────────────────────────────────────────────────┘
        ├─▶ T-05 create use case (+ collision retry)   ──▶ T-08 create API
        ├─▶ T-06 resolve use case (+ fail-open, retry) ──▶ T-09 resolve API
        └─▶ T-07 stats use case                        ──▶ T-10 stats API
                                                               │
                        T-11 problem+json + correlation ID ◀───┤
                        T-12 retry policy + timeouts       ◀───┤
                        T-13 fault-injection suite         ◀───┘
                        T-14 performance harness (A / B)
                        T-15 smoke test + docs
```

T-02, T-03 and T-04 parallelise after T-01. T-05…T-07 do not — they share the port
definitions T-04 introduces, and racing them produces conflicts in exactly the interfaces
that most need one author.

---

## 13. Risks, trade-offs and deferred production evolution

### 13.1 Trade-offs

| Decision | Gained | Cost accepted | Evolution |
|---|---|---|---|
| PostgreSQL only, no cache | One source of truth; no invalidation problem; no stale-read bug class | Every resolve hits the database, and D-01 guarantees every click arrives | Read-through cache with stampede protection, on measured need |
| Synchronous analytics | Simple, immediately consistent, easy to validate | Hot-row contention under concentrated load | Async event pipeline |
| 302 over 301 | Complete counts; expiry and revocation stay honourable | Every click costs an origin request | 301 remains available; the reverse never will be |
| Single instance | Repeatable reviewer experience | Proves nothing about HA | Stateless replicas behind a load balancer — a deployment change, not a rewrite |
| One retry on resolve | Fast failure; no load amplification | A genuinely transient blip may surface as `503` | Circuit breaker once a failure profile is measured |
| Random 7-char codes | No enumeration; no check-then-insert race | Creation has a retry tail | Widen alphabet or length; affects only future codes |

### 13.2 Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R-1 | Destination validation bypassed via DNS rebinding or an encoding normalised differently than resolved | High — SSRF | Validate the resolved address; explicit encoded-form tests |
| R-2 | Analytics coupling reintroduced by a later refactor | High — outage from a non-essential path | Fault-injection test in CI, not a review convention |
| R-3 | Hot-row contention on a viral link | Medium | Measured by scenario B before it is a surprise |
| R-4 | Over-retrying amplifies a database outage | High | One retry on resolve, jittered; asserted by test |
| R-5 | Coverage gate passes vacuously | Medium — false confidence | Noted in `pom.xml` and §11.3; load-bearing from T-02 |
| R-6 | Secrets leaked through destination query strings in logs | High | Destinations never logged at INFO |
| R-7 | Reviewer cannot run it | Medium — the submission fails on its own terms | Clean-clone rehearsal; pinned image tags |

### 13.3 Deferred to production evolution

| Capability | Prototype | Production |
|---|---|---|
| Stateless application design | **built** | multiple replicas behind a load balancer |
| Bounded retries | **built** | circuit breakers, adaptive policies |
| PostgreSQL system of record | **built** | primary + read replicas, HA |
| Cache | documented (§8.4) | Redis read-through, warming, stampede protection |
| Hot-key strategy | documented (§8.5) | cache replication, CDN / edge |
| Analytics | basic count | async event pipeline, aggregates |
| Rate limiting | documented (§8.6) | distributed limiter, quotas, WAF |
| Geography | one region | multi-AZ → global routing → multi-region per RTO/RPO |

---

## 14. AI-assisted execution and engineer sign-off

Execution follows the process in [`../../ai-assisted-engineering.md`](../../ai-assisted-engineering.md):
every task is dispatched with an explicit envelope — intent, constraints, acceptance criteria,
technical context — taken from §12. Open-ended prompts are not used, because they transfer
design authority away from the engineer.

Every material contribution is classified `GENERATED`, `EDITED` or `REJECTED` in the ledger,
with rationale. Rejections are recorded with more care than acceptances: a ledger with no
rejections is evidence that review was not happening.

The engineer of record owns correctness, maintainability and production readiness for every
artifact in this repository, regardless of how it was produced.

### Gate B — approval required

- [ ] The key decisions in §2 are sound, particularly **D-01** (302), **D-06** (synchronous fail-open analytics) and **D-08** (asymmetric retry).
- [ ] The four decisions in §1.3 that exceed the literal requirement text are accepted, or individually rejected.
- [ ] The prototype / production boundary in §3 and §13.3 is honest.
- [ ] The SLO framing in §10 claims nothing the environment can demonstrate.

**Approved by:** _________________  **Date:** __________
