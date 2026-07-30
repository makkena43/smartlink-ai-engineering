# SmartLink

An AI-assisted engineering implementation of a production-minded URL shortener, built with
Spec-Driven Development (SDD), Java 21 and Spring Boot 3.

> SmartLink demonstrates engineer-led, AI-assisted software delivery. The engineer owns the
> requirements, architecture, acceptance criteria, risk decisions and final validation; AI
> is used as a bounded accelerator for analysis, implementation, testing and documentation.

**Status:** v1 scaffold complete and building; v1 domain logic pending Gate A approval.
The process is deliberately visible — nothing is implemented ahead of the specification that
governs it.

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

**Prerequisites:** Java 21, Docker (or Colima), Maven 3.9+.

```bash
git clone <repo> && cd smartlink-ai-engineering
cp .env.example .env
docker compose up --build
```

Then:

```bash
./scripts/smoke-test.sh
```

Build and test without Docker Compose:

```bash
mvn verify
```

Integration tests need a running Docker daemon. Two environment quirks — docker-java's
default API version being rejected by Docker Engine 29, and Ryuk's socket path on Colima —
are fixed in `pom.xml` rather than left to your shell. One thing is machine-specific and
still yours, and only if you run **Colima** rather than Docker Desktop:

```bash
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
```

Detail: [`docs/testing-strategy.md`](docs/testing-strategy.md) §6.

| Surface | URL |
|---|---|
| API docs (generated) | `http://localhost:8080/swagger-ui.html` |
| OpenAPI document | `http://localhost:8080/v3/api-docs` |
| Readiness | `http://localhost:8080/actuator/health/readiness` |

---

## Demo path

```bash
# create  (anonymous - GF-03)
curl -X POST localhost:8080/api/v1/links \
  -H 'Content-Type: application/json' \
  -d '{"destinationUrl":"https://example.com/campaign"}'

# resolve  →  302, Location: https://example.com/campaign, Cache-Control: no-store
curl -i localhost:8080/aB92xK7

# analytics
curl localhost:8080/api/v1/links/aB92xK7/analytics

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
code generation, alias rules and URL policy testable with no context and no container, which
is what keeps the unit suite fast enough to actually get run.

The two request paths differ in every dimension that matters, and the design is organised
around that rather than around resource CRUD:

| | Create | Resolve |
|---|---|---|
| Caller | authenticated, few | anonymous, many |
| Trust | known key holder | hostile by default |
| Volume | low | the whole load |
| Cost of failure | caller retries | user never reaches their destination |

Full detail: [`docs/architecture-overview.md`](docs/architecture-overview.md).

---

## Three scenarios

One evolving codebase, three changes in requirement — each exercising a different
engineering mode.

| | Requirement | What it demonstrates |
|---|---|---|
| **[01 Greenfield](docs/scenarios/01-greenfield/)** | *"Build a URL shortener with redirect and basic analytics."* | Design from first principles; 10 ambiguities surfaced and resolved with rationale |
| **[02 Brownfield](docs/scenarios/02-brownfield/)** | *"Add expiration so campaigns can stop redirecting after a defined time."* | Codebase reasoning — impact analysis, migration, backward compatibility, all against code that already exists |
| **[03 Ambiguous](docs/scenarios/03-ambiguous/)** | *"Improve reliability."* | Normalising a direction into a bounded, testable scope — and naming what was deliberately excluded |

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
`REJECTED`. **It currently contains three rejections**, including a smoke-test assertion that
would have been wrong on first run, a compose file referencing a nonexistent Spring profile,
and an unverifiable `p99 ≤ 50 ms at 500 rps` performance claim that triggered a constitutional
amendment.

A ledger with no rejections would be evidence that review was not happening.

Detail: [`docs/ai-assisted-engineering.md`](docs/ai-assisted-engineering.md).

---

## Testing

| Level | Runs | Covers |
|---|---|---|
| Unit | `mvn test` | Code generation, alias policy, URL policy — no Spring, no database |
| Controller | `mvn test` | Status codes, headers, problem+json, auth |
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
