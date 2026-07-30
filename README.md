# SmartLink

An AI-assisted engineering implementation of a production-minded URL shortener, built with
Spec-Driven Development (SDD), Java 21 and Spring Boot 3.

> SmartLink demonstrates engineer-led, AI-assisted software delivery. The engineer owns the
> requirements, architecture, acceptance criteria, risk decisions and final validation; AI
> is used as a bounded accelerator for analysis, implementation, testing and documentation.

**Status:** Scenarios 01 (greenfield) and 02 (brownfield — link expiration) complete and verified
end to end. Scenario 03 (ambiguous — reliability) is specified but not implemented.

```
./mvnw verify   251 tests, 0 failures     line 92.7 %   branch 78.9 %
smoke-test.sh    25 checks, 0 failures    against docker compose
trivy             0 HIGH/CRITICAL         dependencies · secrets · image
spotbugs          0 findings at HIGH
rollback          rehearsed: pre-change jar runs against the migrated schema
```

---

## The idea in one paragraph

Given a one-sentence requirement — *"build a URL shortener with redirect and basic
analytics"* — the interesting engineering is not the shortening. It is everything the
sentence failed to say: whether the redirect is cacheable, whether shortening the same URL
twice returns the same code, what "basic analytics" counts, what personal data that implies,
and what happens when the thing you are counting fails. This repository answers those in
writing, before writing code, and keeps a record of who decided what.

---

## Quick start

### Fastest — no Docker, no database, no Maven

**Only prerequisite: a Java 21 JDK.** The Maven wrapper fetches Maven itself.

```bash
git clone <repo> && cd smartlink-ai-engineering
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

Ready in about 15 seconds on `http://localhost:8080`. Runs against an in-memory H2 database,
so nothing is installed and nothing survives a restart.

> **What the `h2` profile does not prove.** The integration suite runs against a real
> PostgreSQL deliberately, because most of what it asserts is *database* behaviour — a unique
> index arbitrating a concurrent insert, `UPDATE … SET x = x + 1` serialising at row level,
> Flyway and Hibernate agreeing about a schema. H2 is close enough to demonstrate the API and
> nowhere near close enough to validate those. Use Docker Compose for anything you intend to
> trust.

### Production-shaped — Docker Compose with PostgreSQL

```bash
cp .env.example .env
docker compose up --build
./scripts/smoke-test.sh          # 25 checks
```

### Full test suite

```bash
./mvnw verify                    # 232 tests; needs a Docker daemon for Testcontainers
```

On macOS with **Colima** rather than Docker Desktop, one machine-specific export is still
yours (everything else is fixed in `pom.xml`):

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
```

Detail: [`docs/testing-strategy.md`](docs/testing-strategy.md) §6.

| Surface | URL |
|---|---|
| API docs (generated) | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/v3/api-docs` |
| Readiness | `http://localhost:8080/actuator/health/readiness` |
| H2 console (`h2` profile only) | `http://localhost:8080/h2-console` — JDBC `jdbc:h2:mem:smartlink`, user `sa`, no password |

---

## Demo path

```bash
# create  (anonymous - GF-03)
curl -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/campaign"}'

# capture the generated code (it is random - a hard-coded one would 404)
CODE=$(curl -fsS -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/campaign"}' \
  | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')

# resolve  →  302, Location: https://example.com/campaign, Cache-Control: no-store
curl -i "localhost:8080/$CODE"

# analytics
curl "localhost:8080/api/v1/links/$CODE/analytics"

# create with an expiry  (scenario 02) - omit expiresAt for a link that never expires
curl -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/campaign","expiresAt":"2030-01-01T00:00:00Z"}'

# rejected: cloud metadata endpoint, well-formed and https-adjacent but blocked  →  422
curl -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"http://169.254.169.254/latest/meta-data/"}'
```

---

## Architecture

A **modular monolith** with the dependency rule running inward only.

```
   api ────▶ application ────▶ domain ◀──── infrastructure
   HTTP      use cases          rules        adapters
```

`domain` imports nothing — not Spring, not JPA. That is not architectural fashion: it makes
code generation and the destination policy testable with no context and no container, which is
what keeps the unit suite fast enough to actually get run. 88 of the 232 tests run that way.

The two request paths differ in every dimension that matters, and the design is organised
around that rather than around resource CRUD:

| | Create | Resolve |
|---|---|---|
| Caller | anonymous, few | anonymous, many |
| Trust | hostile by default | hostile by default |
| Volume | low | the whole load |
| Cost of failure | caller retries | visitor never reaches their destination |
| Failure posture | fail loudly | fail safely, never guess |

Both endpoints are unauthenticated — a stated prototype boundary (GF-03, GF-12), which is
precisely why short codes are cryptographically random: **possession of the code is the only
access control that exists.**

Full detail: [`docs/architecture-overview.md`](docs/architecture-overview.md).

---

## Three scenarios

One evolving codebase, three changes in requirement — each exercising a different
engineering mode.

| | Requirement | What it demonstrates |
|---|---|---|
| **[01 Greenfield](docs/scenarios/01-greenfield/)** ✅ | *"Build a URL shortener with redirect and basic analytics."* | Design from first principles; 10 ambiguities surfaced and resolved with rationale |
| **[02 Brownfield](docs/scenarios/02-brownfield/)** ✅ | *"Add expiration so campaigns can stop redirecting after a defined time."* | Codebase reasoning — impact analysis written from the committed code, expand-only migration, backward compatibility, **rollback rehearsed with the pre-change jar** |
| **[03 Ambiguous](docs/scenarios/03-ambiguous/)** ⏳ *not implemented* | *"Improve reliability."* | Normalising a direction into a bounded, testable scope — and naming what was deliberately excluded |

---

## Decisions worth arguing about

Nine ADRs are recorded in [`docs/decisions.md`](docs/decisions.md). Three that a reviewer
should push on:

**302, not 301** *(ADR-001)*. A cached 301 means repeat clicks never reach the service, so
analytics undercount by an unbounded and unmeasurable margin — and a cached 301 cannot be
recalled. Moving 302 → 301 later is a config change; the reverse is impossible. The
asymmetry decides it, not the latency.

**Codes are never reused** *(ADR-002)*. A printed link outlives the service's memory of it.
Reissuing `abc123` silently redirects every historical holder to a destination chosen by
someone else. A one-way door, escalated before implementation rather than discovered after.

**No PII in analytics** *(ADR-005)*. Counters only — no IP, no user-agent, no referrer.
Persisting request data turns a shortener into a behavioural tracking system and acquires
data-protection obligations nothing asked for. Data never collected cannot be recovered; data
collected under an unclear basis cannot be un-collected. Only one of those is fixable.

---

## AI-assisted engineering

The process is governed by a written constitution — ten articles covering spec-before-code,
ambiguity registers, task envelopes, traceability, quality gates, secure AI usage and human
sign-off — written *before* the first specification.

The traceability ledger classifies every material AI contribution as `GENERATED`, `EDITED` or
`REJECTED`. **It contains 28 rejections** — among them a dependency version verified against a
stale search index, a `@Transactional` annotation that could not do what it appeared to,
a mocked failure that would have proven nothing, and a test client that silently followed the
very redirects it was meant to be asserting on.

A ledger with no rejections would be evidence that review was not happening.

Detail: [`docs/ai-assisted-engineering.md`](docs/ai-assisted-engineering.md).

---

## Testing

| Level | Runs | Covers |
|---|---|---|
| Unit | `mvn test` | Code generation, destination policy — no Spring, no database |
| Controller | `mvn test` | Status codes, headers, problem+json, route precedence |
| Integration | `mvn verify` | Real PostgreSQL via Testcontainers; migrations; unique-code races |
| Fault injection | `mvn verify` | Analytics down → redirect still works; datastore down → 503, never a guess |
| Smoke | `./scripts/smoke-test.sh` | Full reviewer path against the running stack |

Coverage gates at 85 % line / 75 % branch — a **floor, not a target**. A high number over
weak assertions is worse than a lower number over strong ones, because it converts "we did
not check" into "we checked and it was fine".

Detail, including a known gate hole stated rather than hidden:
[`docs/testing-strategy.md`](docs/testing-strategy.md).

---

## Honest limitations

1. **No SLO is proven.** The targets in the specs are design intent. A laptop measurement is
   a regression signal, not evidence of production capacity.
2. **Single node, single AZ.** Horizontal scalability is a property of the design
   (stateless read path), demonstrated by construction rather than by a running cluster.
3. **API keys are seeded configuration** — no issuance, rotation or revocation.
4. **No abuse controls in v1.** Rate limiting is scenario 03.
5. **Load figures are environment-bound.** Database and service share a host.

Full list: [`docs/tradeoffs-and-risks.md`](docs/tradeoffs-and-risks.md).

---

## Documentation map

| Document | What it answers |
|---|---|
| [`architecture-overview.md`](docs/architecture-overview.md) | What the system looks like *now* |
| [`api-overview.md`](docs/api-overview.md) | The contract, and why it has that shape |
| [`decisions.md`](docs/decisions.md) | What was decided, when, and what it cost |
| [`tradeoffs-and-risks.md`](docs/tradeoffs-and-risks.md) | What was traded away, and what could go wrong |
| [`testing-strategy.md`](docs/testing-strategy.md) | How correctness is established |
| [`task-plan.md`](docs/task-plan.md) | Programme-level sequencing across the three scenarios |
| [`ai-assisted-engineering.md`](docs/ai-assisted-engineering.md) | The constitution, and the AI traceability ledger |
| [`final-engineering-summary.md`](docs/final-engineering-summary.md) | Plan, rationale, artifacts, risks, assumptions, limitations |
