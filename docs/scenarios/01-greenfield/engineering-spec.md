# Scenario 01 — Greenfield · Engineering Spec

Translates [`requirements.md`](requirements.md) into testable behaviour and a technical
design. Every acceptance criterion below is mechanically checkable; every one maps to at
least one automated test in [`validation.md`](validation.md).

- **Status:** Draft — Gate B
- **Depends on:** Gate A approval of `requirements.md`

---

## 1. Functional requirements

### FR-1 — Create a short link
Given a valid destination, return a short code and its full short URL. Optional inputs:
custom alias, idempotency key.

| ID | Criterion |
|---|---|
| AC-1.1 | Valid `https` destination → `201 Created` with code, short URL, destination, creation instant |
| AC-1.2 | Response carries a `Location` header for the created resource |
| AC-1.3 | Same idempotency key + identical body → identical response; exactly one link exists |
| AC-1.4 | Same idempotency key + different body → `409 Conflict`; original unmodified |
| AC-1.5 | Generated codes are 7 characters over a 62-symbol alphabet and are not derivable from an adjacent code |
| AC-1.6 | Missing or invalid API key → `401` |

### FR-2 — Resolve a short link

| ID | Criterion |
|---|---|
| AC-2.1 | Known code → `302` with `Location` = exact stored destination |
| AC-2.2 | Redirect carries `Cache-Control: no-store` (A-01) |
| AC-2.3 | Unknown code → `404` |
| AC-2.4 | Destination returned byte-identical, including query string and fragment |
| AC-2.5 | Resolution requires no authentication |

### FR-3 — Custom alias

| ID | Criterion |
|---|---|
| AC-3.1 | Available, well-formed alias → `201`, alias used verbatim |
| AC-3.2 | Already-claimed alias → `409` |
| AC-3.3 | Reserved word → `422` naming the violated rule |
| AC-3.4 | Malformed alias (length or charset) → `422` |
| AC-3.5 | A custom alias can never collide with a generated code (A-06) |

### FR-4 — Destination validation

| ID | Criterion |
|---|---|
| AC-4.1 | Non-`http(s)` scheme → `422` |
| AC-4.2 | Host resolving to private / loopback / link-local / metadata ranges → `422` |
| AC-4.3 | Decimal, octal and IPv6-mapped encodings of blocked addresses also rejected |
| AC-4.4 | Destination exceeding the documented maximum length → `422` |
| AC-4.5 | Rejections name the failed rule and never echo raw input back unescaped |

### FR-5 — Basic analytics

| ID | Criterion |
|---|---|
| AC-5.1 | Owner reads per-link total resolutions, first-resolution instant, last-resolution instant |
| AC-5.2 | A successful resolution increments the counter exactly once |
| AC-5.3 | A non-owner key reading another owner's link → `404`, not `403` — non-existence and non-ownership are deliberately indistinguishable, so the endpoint is not an enumeration oracle |
| AC-5.4 | **When the analytics write fails, the redirect still returns `302` with the correct `Location`** (A-05). Proven by fault injection |
| AC-5.5 | Concurrent resolutions of one code do not lose counts |

### FR-6 — Operability

| ID | Criterion |
|---|---|
| AC-6.1 | Liveness reflects process health only |
| AC-6.2 | Readiness fails when a required dependency is unavailable |
| AC-6.3 | Every response carries a correlation ID, echoed from the caller when supplied |
| AC-6.4 | When the datastore is unavailable, resolution returns a clear `503`. It never guesses, and never redirects to a wrong or stale destination |
| AC-6.5 | Logs are structured and contain no full destination URL and no API key at INFO or below |
| AC-6.6 | API documentation is generated from the implementation, not hand-maintained |

---

## 2. Design targets (SLI / SLO)

**Design targets and discussion points. Not contractual SLAs, and a prototype on a laptop
proves none of them.** Measurements are reported with method, machine and sample size
stated, and with no extrapolation to production scale.

| Signal | SLI | Production target | What v1 actually demonstrates |
|---|---|---|---|
| Redirect availability | successful redirects / eligible redirect requests | 99.9 % monthly | Correct error semantics and health behaviour — **not** multi-AZ availability |
| Redirect latency | p95 server-side resolution | < 100 ms | Local measurement, environment stated |
| Creation latency | p95 `POST /api/v1/links` | < 250 ms | Functional response plus modest local load |
| Error rate | 5xx / total | < 0.1 % over the window | Errors surfaced clearly, users never misdirected |

### Non-functional requirements actually verifiable in v1

| ID | Requirement | Verified by |
|---|---|---|
| NFR-1 | Redirect survives analytics failure | Fault-injection test (AC-5.4) |
| NFR-2 | Resolution returns 503, never a wrong destination, when the datastore is down | Fault-injection test (AC-6.4) |
| NFR-3 | No destination URL or API key in logs at INFO or below | Log assertion test |
| NFR-4 | No node-local state on the read path | Stateless by construction; architecture review |
| NFR-5 | Behaviour on code collision is correct regardless of probability | Forced-collision test |
| NFR-6 | Domain and application layers ≥ 85 % line coverage | JaCoCo gate |

NFR-5 is deliberately phrased around *behaviour on collision* rather than its probability.
Probability arguments fail silently; a test that forces a collision and asserts correct
recovery does not.

---

## 3. Technical design

### 3.1 Layering

The dependency rule runs inward only. Enforced by package and asserted in the test suite.

```
   api ────▶ application ────▶ domain ◀──── infrastructure
   HTTP      use cases          rules        adapters
```

| Package | Holds | Depends on |
|---|---|---|
| `com.smartlink.domain` | `ShortCode`, `Destination`, `Link`, code generation, alias and URL policy | nothing |
| `com.smartlink.application` | `CreateLinkUseCase`, `ResolveLinkUseCase`, `ReadStatsUseCase`, port interfaces | domain |
| `com.smartlink.infrastructure` | JPA entities and repositories, config, clock, key store | domain (implements its ports) |
| `com.smartlink.api` | controllers, request/response records, error mapping, filters | application |

The reason this matters practically: shortening, alias validation and URL policy — the
logic most worth testing exhaustively — become testable with **no Spring context and no
database**. That is what keeps the unit suite fast enough to run on every save, and it is
why the layering is a constraint rather than decoration.

### 3.2 Data model

One table in v1. Additive-first, so v2's expiry column is a pure addition (see
[`../02-brownfield/impact-analysis.md`](../02-brownfield/impact-analysis.md)).

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | internal only, never exposed |
| `code` | `varchar(32)` **unique** | the public handle; uniqueness is enforced by the database, not by application check-then-insert |
| `code_kind` | `varchar(16)` | `GENERATED` or `CUSTOM` — makes A-06's disjoint namespaces a stored fact rather than an inferred one |
| `destination_url` | `text` | byte-preserved (AC-2.4) |
| `owner_key_id` | `varchar(64)` | which API key created it; scopes stats reads |
| `idempotency_key` | `varchar(128)` | nullable; unique per owner when present |
| `created_at` | `timestamptz` | UTC |
| `total_resolutions` | `bigint` default 0 | A-03 |
| `first_resolved_at` | `timestamptz` null | A-03 |
| `last_resolved_at` | `timestamptz` null | A-03 |

**Uniqueness is a database constraint, and collision handling is retry-on-violation.** A
check-then-insert in application code is a race under concurrency: two requests can both
observe a code as free. Letting the unique index arbitrate makes the race impossible rather
than unlikely, which is what NFR-5 asserts by forcing one.

Counters live on the link row in v1. This is the accepted hot-row trade-off from A-05, and
`scripts/performance-test/` measures it rather than assuming it.

### 3.3 API surface

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/links` | API key | Create (FR-1, FR-3) |
| `GET` | `/{code}` | none | Resolve (FR-2) |
| `GET` | `/api/v1/links/{code}/stats` | API key | Analytics (FR-5) |
| `GET` | `/actuator/health/{liveness,readiness}` | none | Operability (FR-6) |
| `GET` | `/v3/api-docs`, `/swagger-ui.html` | none | Generated documentation (AC-6.6) |

Resolution is mounted at the root so short links stay short. Everything else is namespaced
under `/api/v1`, which is what makes the reserved-word denylist in A-06 necessary rather
than merely tidy — a code of `api` would otherwise shadow the management surface.

Errors use RFC 9457 `application/problem+json`, carrying the violated rule (AC-4.5) and the
correlation ID (AC-6.3).

### 3.4 Code generation

Random, not sequential. 7 characters over `[A-Za-z0-9]` = 62⁷ ≈ 3.5 × 10¹².

Sequential or hash-of-URL schemes are rejected: sequential codes let anyone enumerate every
link in the system by counting, and hash-of-URL leaks whether a given URL has been shortened
before — an oracle that also silently reintroduces the deduplication rejected in A-02.

Collision is handled by insert-and-retry against the unique index, bounded to a small number
of attempts, after which the request fails loudly rather than looping.

---

## 4. Risks carried into implementation

| Risk | Why it matters | Mitigation |
|---|---|---|
| Synchronous counter on the hot path | Accepted trade-off (A-05); hot-row contention is the known failure mode | Measured directly by performance scenario B, not assumed |
| Destination validation bypass | A-07 is only as strong as its resistance to encoding tricks and DNS rebinding | Validate post-resolution against parsed address ranges; test decimal/octal/IPv6-mapped forms |
| v2 expiry coupling | v1 must not encode "never expires" as an invariant v2 has to unpick | Additive-first schema; no `NOT NULL` that presumes absence of expiry |
| Analytics fail-open regressing | A later refactor can quietly reintroduce coupling | Structural test (AC-5.4) runs in CI, not a code-review convention |
| Coverage gate passing vacuously | JaCoCo skips when no exec data exists | Gate becomes load-bearing at T-02; noted in `pom.xml` and `testing-strategy.md` |

---

## 5. Definition of done

1. Every AC exercised by at least one automated test, traceably mapped in `validation.md`.
2. All quality gates green (`ai-assisted-engineering.md`, Article VI).
3. Service runs end-to-end from a clean clone with one documented command.
4. `scripts/smoke-test.sh` passes against the compose stack.
5. API documentation generated from the implementation.
6. Decisions marked *costly* or *one-way* recorded in `docs/decisions.md`.
7. `docs/ai-assisted-engineering.md` ledger complete — including rejections with rationale.
