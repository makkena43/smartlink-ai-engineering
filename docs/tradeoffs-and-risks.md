# Trade-offs and Risks

A trade-off is a deliberate choice: what is gained now, what is accepted as cost, and how
the design can evolve. Anything listed here without a cost is not a trade-off — it is a
claim, and it does not belong on this page.

---

## 1. Engineering trade-offs

| Decision | Gained now | Cost accepted | Evolution path |
|---|---|---|---|
| **PostgreSQL as system of record** (ADR-008) | Transactional, indexed, one source of truth, simple ownership | Every resolve hits the database — and ADR-001 guarantees every click arrives | Read-through cache with stampede protection, when scenario A shows read cost dominating p95 |
| **Modular monolith** (ADR-007) | Fast delivery, one deployment, coherent tests | Create and resolve cannot scale independently; they share a process and its failures | Split on measured need; the zero-dependency domain layer keeps that split cheap |
| **Synchronous analytics** (ADR-004) | Simple, immediately consistent, easy to validate | Work on the hot path; hot-row contention when one code dominates | Async events and aggregates — after scenario B quantifies the contention |
| **No cache in v1** (ADR-008) | Fewer moving parts, no invalidation problem, no stale-read bug class | Popular links pay full database cost every time | Introduce with explicit coherency bound, not by reflex |
| **302 over 301** (ADR-001) | Complete analytics; expiry and revocation remain honourable | Every click costs an origin request; repeat visitors marginally slower | 301 remains available; the reverse never will be |
| **No PII in analytics** (ADR-005) | No retention policy, no deletion machinery, no accidental compliance surface | Referrer, device and geography can never be answered retroactively | Designed in deliberately, with minimisation, if a real requirement appears |
| **Random 7-char codes** (ADR-009) | No enumeration, no oracle, no check-then-insert race | Creation latency has a retry tail; code length is effectively fixed | Widen the alphabet or length; both affect only future codes |
| **No authentication or rate limiting in the prototype** | Focused anonymous-create and resolve flows | A public deployment would be vulnerable to abuse | Add identity, quota policy, and distributed enforcement together; do not add a rate limit with no accountable subject |
| **Single local deployment** | Repeatable reviewer experience via Docker Compose | Proves nothing about HA | Stateless replicas across AZs behind a load balancer |

---

## 2. Risk register

Severity is *impact if it happens*, not likelihood alone.

| # | Risk | Severity | Detection | Mitigation |
|---|---|---|---|---|
| R-1 | **Destination validation bypassed** via DNS rebinding or an encoding the parser normalises differently than the resolver | High — SSRF into internal networks | Explicit tests for decimal, octal, IPv6-mapped forms | Validate the *resolved address*, not the hostname string; re-validate at fetch time if a fetcher is ever added |
| R-2 | **Analytics coupling reintroduced** by a later refactor, so a counter failure starts failing redirects | High — outage from a non-essential path | Fault-injection test AC-5.4 in CI | Structural test, not a review convention. It fails the build, not a reviewer's memory |
| R-3 | **Hot-row contention** on a viral link serialises writes and inflates p95 | Medium | Performance scenario B | Measured before it is a surprise; async path is designed and documented, not improvised under pressure |
| R-4 | **Stale destination served** after a future revocation, once a cache exists | High — the security property in ADR-002 silently lapses | Coherency-bound test, added with the cache | Cache is deliberately absent in v1 so this risk does not exist yet |
| R-5 | **Coverage gate passes vacuously** — JaCoCo skips when no exec data exists | Medium — false confidence, the worst kind | Visible in build output | Noted in `pom.xml`; gate becomes load-bearing at T-02. A skipped gate is treated as a defect, not a pass |
| R-6 | **Secrets in logs** via destination query strings | High — credential leak through an access log | Log assertion test (AC-6.5) | Destinations never logged at INFO; error responses never echo raw input |
| R-7 | **AI-suggested dependency** that is hallucinated, unmaintained, or typosquatted | High — supply chain | Every dependency verified on Maven Central before adoption | Versions in `pom.xml` were queried live, not recalled |
| R-8 | **Clock skew** across instances making expiry decisions inconsistent | Low | Expiry integration tests | Database-side time is selected with the link row, giving one authoritative clock |
| R-9 | **Management metrics exposed without network controls** disclose traffic shape | Medium | `ReliabilitySignalsIT` confirms a narrow endpoint surface | Bind management separately and authenticate it in a real deployment |
| R-10 | **Reviewer cannot run it** — environment drift between author and grader | Medium — the submission fails on its own terms | Clean-clone rehearsal before submission | Docker Compose path; pinned image tags; documented prerequisites |

---

## 3. What would change my mind

Kept explicit so the decisions above stay falsifiable rather than merely defended:

- **ADR-001 (302).** If analytics moved to a client-side beacon, the argument for 302
  collapses entirely and 301 becomes correct.
- **ADR-004 (sync analytics).** If scenario B shows contention at realistic load, the async
  path stops being an evolution and becomes overdue work.
- **ADR-007 (monolith).** If the create path ever destabilises the resolve path in
  production, the shared process becomes the problem rather than the simplification.
- **ADR-005 (no PII).** A stated, lawful product requirement for richer analytics — not a
  vague "we might want it later", which is what the decision is designed to resist.

---

## 4. Limitations of this prototype

Stated plainly, because the alternative is letting a reviewer discover them and wonder what
else went unsaid:

1. **No SLO is proven.** The targets in the engineering spec are design intent. A laptop
   measurement is a regression signal, not evidence of production capacity.
2. **Single node, single AZ.** Horizontal scalability is a property of the design (NFR-4),
   demonstrated by statelessness, not by a running cluster.
3. **No authentication or abuse controls.** Rate limiting needs an identity and traffic policy;
   both are deferred rather than simulated.
4. **No production topology.** Multi-instance, multi-AZ, and multi-region behavior remain
   design evolution, not demonstrated properties.
5. **Load figures are environment-bound.** Database and service share a host; the numbers
   describe that arrangement and no other.
