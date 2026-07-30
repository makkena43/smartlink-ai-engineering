# Final Engineering Summary

> **Status: interim.** Sections 1–3 and 6–7 are settled and written. Sections 4–5 record
> outcomes and are completed as scenarios close. They are left visibly empty rather than
> pre-filled, because a summary written ahead of the work summarises intentions and reads
> exactly like one that summarises results.

---

## 1. Plan and rationale

The requirement was one sentence. The plan was to treat that as the problem rather than an
inconvenience.

**Spec-Driven Development with four human gates.** A written constitution came before the
first specification: spec precedes code, ambiguity is registered rather than absorbed, AI is
directed through task envelopes, every material AI contribution is classified, and named
gates require approval between phases. The constitution exists so that "the AI wrote it" is
structurally unavailable as an explanation for a defect.

**Three scenarios against one evolving codebase**, sequenced rather than parallel:

| | Requirement | Mode |
|---|---|---|
| 01 | *"Build a URL shortener with redirect and basic analytics."* | Design from first principles |
| 02 | *"Add expiration so campaigns can stop redirecting after a defined time."* | Change to a system that exists, is tested, and has clients |
| 03 | *"Improve reliability."* | Normalise a direction into a bounded scope |

The sequence is the point. A brownfield scenario against code written the same afternoon is
greenfield with extra steps; the impact analysis only has teeth against code that is already
committed and tested. And "improve reliability" is only genuinely ambiguous when there is a
running system whose reliability is in question.

**Expiration was chosen as the brownfield change** over larger candidates because it is
compact but genuinely cross-cutting — persistence, creation API, redirect logic, backward
compatibility, migration, documentation, tests. A bigger feature would have produced more
code and less evidence of judgment.

---

## 2. Key decisions

Nine ADRs in [`decisions.md`](decisions.md). The three most consequential:

| Decision | Reasoning | Reversibility |
|---|---|---|
| **302, not 301** | A cached 301 means repeat clicks never reach the service, so analytics undercount by an unmeasurable margin — and cached responses cannot be recalled. 302 → 301 is a config change; the reverse is impossible | One-way toward 301 |
| **Codes never reused** | A printed link outlives the service's memory of it. Reissuing a code silently redirects every historical holder to a destination chosen by someone else | **One-way door** |
| **No PII in analytics** | Persisting IP/user-agent/referrer turns a shortener into a behavioural tracking system and acquires data-protection obligations nothing asked for | **One-way if reversed** |

The pattern across all three: **decisions were classified by reversibility before being
made**, and the one-way ones were escalated rather than settled in implementation. That is
the mechanism that separates a considered decision from a default that happened to stick.

---

## 3. Assumptions

| # | Assumption | If wrong |
|---|---|---|
| 1 | The service is public-facing, so creation needs attribution and resolution does not | The API-key model is unnecessary complexity |
| 2 | Analytics answers "is this link used, and recently" — not attribution or segmentation | Per-click event storage is needed, and with it a privacy design |
| 3 | Correctness beats availability: a wrong redirect is worse than no redirect | Caching and stale-read tolerance become permissible |
| 4 | Prototype scale; no measured production load exists | Cache and async analytics would move from "deferred" to "required" |
| 5 | The reviewer runs it locally with Docker | The compose path is the wrong delivery mechanism |

Assumption 3 is load-bearing and not a neutral default. It is chosen because the product
promise is that a short link points where its owner says it points: a service that fails
loudly keeps that promise, and one that redirects to a stale destination breaks it while
appearing to work.

---

## 4. Artifacts delivered

*Completed as scenarios close.*

| Artifact | Location | State |
|---|---|---|
| Runnable service | `docker compose up --build` | scaffold builds; v1 logic pending |
| Build with gates wired | `pom.xml` | ✅ `mvn verify` green |
| API contract, generated | `/v3/api-docs` | pending v1 |
| Test suite | `src/test/` | pending v1 |
| Smoke test | `scripts/smoke-test.sh` | ✅ written, pending a service to run against |
| Specifications | `docs/scenarios/*/` | 01 complete; 02, 03 requirements complete |
| ADRs | `docs/decisions.md` | ✅ 9 recorded |
| AI ledger | `docs/ai-assisted-engineering.md` | ✅ 8 entries, 3 rejections |
| Performance results | `scripts/performance-test/RESULTS.md` | pending T-14 |

---

## 5. Validation outcomes

*Completed as scenarios close. Per-scenario detail in each `validation.md`.*

| Scenario | ACs | Tests | Coverage | Gates |
|---|---|---|---|---|
| 01 Greenfield | 30 defined | pending | pending | pending |
| 02 Brownfield | pending | pending | pending | pending |
| 03 Ambiguous | pending | pending | pending | pending |

---

## 6. Risks and trade-offs

Ten risks and ten trade-offs are recorded in
[`tradeoffs-and-risks.md`](tradeoffs-and-risks.md). The ones that would matter first in
production:

- **Analytics coupling reintroduced by a later refactor** (R-2). The fail-open posture is
  invisible in the code; a well-meaning refactor wrapping resolution in one transaction
  would reverse it silently. Guarded by a fault-injection test in CI rather than by review
  convention, because conventions do not survive refactors.
- **Destination validation bypass** (R-1). The SSRF and stored-XSS protections are only as
  strong as their resistance to encoding tricks and DNS rebinding — hence validation against
  the *resolved* address, not the hostname string.
- **Hot-row contention** (R-3). Synchronous counters were accepted knowingly; the
  performance harness measures the cost rather than assuming it.
- **Coverage gate passing vacuously** (R-5). JaCoCo skips silently with no execution data.
  Recorded in `pom.xml`, `testing-strategy.md` and the risk register, because a gate that
  reports green without looking is worse than no gate.

---

## 7. Limitations

Stated plainly, because the alternative is letting a reviewer find them and wonder what else
went unsaid:

1. **No SLO is proven.** Every target is design intent. A laptop measurement is a regression
   signal, not evidence of production capacity — and the constitution was amended (v1.1)
   specifically to stop the performance gate from implying otherwise.
2. **Single node, single AZ.** Horizontal scalability is a property of the design — the read
   path holds no node-local state — demonstrated by construction, not by a cluster.
3. **API keys are seeded configuration.** No issuance, rotation or revocation flow exists.
4. **No caching, no async analytics, no circuit breaking, no retries.** Each is genuine
   engineering work; none can be *validated* in this environment, and shipping unvalidated
   reliability machinery improves the appearance of reliability rather than reliability.
5. **Abuse controls are scenario 03 and bounded** even there.
6. **Load figures are environment-bound.** Service and database share a host; the numbers
   describe that arrangement and no other.

---

## 8. What I would do next, given more time

In priority order, which is itself a judgment worth stating:

1. **Finish v1 implementation** and populate the traceability matrix — the specs currently
   promise more than the code delivers, and that gap is the honest headline of this
   submission.
2. **Measure before optimising.** Run performance scenarios A and B; only then decide
   whether the cache and the async analytics path are warranted. Both are currently deferred
   on the grounds that no measurement justifies them — a claim that should be tested rather
   than trusted.
3. **Key issuance and rotation**, which is the largest gap between this and something
   deployable.
4. **Abuse controls** — rate limiting per owner, and destination screening — before any
   public exposure. A public shortener without them is a phishing platform with extra steps.
