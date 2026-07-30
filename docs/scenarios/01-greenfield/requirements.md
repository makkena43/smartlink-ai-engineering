# Greenfield Requirements — SmartLink URL Shortener

- **Scenario:** 01 — Greenfield
- **Status:** Revision 2 — awaiting Gate A
- **Engineer of record:** Srinivas Makkena

---

## 1. Product objective

SmartLink enables users to create compact links for long web destinations, share those links,
redirect recipients to the registered destination, and view basic link usage information.

The Greenfield prototype must be runnable end-to-end and demonstrate a production-minded
approach to correctness, security, resilience, scalability, and safe change management.

## 2. Users and primary journeys

| User | Goal |
|---|---|
| Link creator | Submit a destination URL and receive a short URL that can be shared. |
| Link recipient | Open a short URL and be redirected to the registered destination in the same client. |
| Operator | Determine whether the service is healthy and diagnose safe, customer-facing failures. |

## 3. In-scope functional requirements

| ID | Requirement type | Requirement |
|---|---|---|
| GF-01 | Core link creation | The system shall accept a valid HTTP or HTTPS destination URL and create a unique short code. |
| GF-02 | Core link creation | The system shall return a canonical short URL containing the created short code. |
| GF-03 | Access policy | The prototype shall allow anonymous link creation. |
| GF-04 | Link lifecycle policy | Each successful create-link request shall create an independent short link, including when the same destination URL was previously submitted. |
| GF-05 | Data correctness | The system shall ensure that one short code identifies only one destination URL. |
| GF-06 | Concurrency correctness | The system shall preserve correctness when concurrent create-link requests are processed. |
| GF-07 | Core redirect behavior | When an active short code is found, the system shall redirect the recipient to the exact registered destination URL. |
| GF-08 | Client compatibility | The redirect shall work in the same standards-compliant requesting client without requiring client-side software or a separate browser context. |
| GF-09 | Error behavior | When a short code is not found, the system shall not redirect and shall return a clear not-found response. |
| GF-10 | Input validation | The system shall reject malformed destination URLs and unsupported URL schemes with a client error. |
| GF-11 | Analytics | The system shall expose basic analytics for a known short link, including its total successful redirect count. |
| GF-12 | Analytics access policy | Basic analytics for a known short code shall be available without authentication in the prototype. |
| GF-13 | Operational capability | The system shall expose operational health information suitable for local checks and future automated routing decisions. |
| **GF-14** | **Security / destination policy** | **The system shall reject destination URLs whose host resolves to private, loopback, link-local, or cloud-metadata address ranges, including decimal, octal, and IPv6-mapped encodings of those addresses.** |
| **GF-15** | **Security / destination policy** | **The system shall reject destination URLs exceeding a documented maximum length.** |
| **GF-16** | **Routing safety** | **Resolution of a short code shall never shadow an application route, and the precedence between the two shall be explicit rather than incidental.** |
| **GF-17** | **Analytics resilience** | **A failure to record a redirect shall not prevent that redirect from being served.** |
| **GF-18** | **Diagnostics** | **Every response shall carry a correlation identifier, echoed from the caller when one is supplied.** |

## 4. Quality and operational requirements

| ID | Requirement type | Requirement |
|---|---|---|
| NFR-01 | Reliability / durability | The system shall preserve registered link mappings across normal application restarts. |
| NFR-02 | Reliability / safety | The system shall fail safely and shall never issue an unverified or incorrect redirect. |
| NFR-03 | Resilience | When a required dependency temporarily fails, the system shall make only bounded retry attempts and shall return a safe failure response when a verified mapping cannot be resolved. |
| NFR-04 | Security | Client-facing errors shall be actionable but shall not expose stack traces, credentials, database details, hostnames, or other sensitive implementation information. |
| NFR-05 | Compatibility | The system shall be browser agnostic for standards-compliant HTTP clients. |
| NFR-06 | Scalability | The production design shall support horizontal scaling of redirect capacity. |
| NFR-07 | Performance / workload | The production design shall support a read-heavy, burst-prone workload in which redirects substantially exceed link-creation requests. |
| NFR-08 | Performance / hot-key resilience | The production design shall remain responsive when a small number of short links receive disproportionately high traffic. |
| NFR-09 | Security / abuse prevention | The production design shall define protection for customer-facing endpoints against abusive or excessive request rates. |
| NFR-10 | Observability / SLO | The system shall define measurable objectives for redirect availability, redirect latency, and error rate. |
| NFR-11 | Quality / testability | The system shall provide automated coverage for critical successful and failure paths. |
| NFR-12 | Operability | The repository shall provide repeatable setup, execution, and validation instructions. |
| NFR-13 | Privacy | The prototype shall not collect IP address, geographic location, browser, device, referrer, or other personal data. |
| **NFR-14** | **Security / log hygiene** | **Destination URLs shall not be written to application logs at INFO level or below.** |
| **NFR-15** | **Security / enumeration resistance** | **Short codes shall not be sequential, and shall not be derivable from a known adjacent code.** |
| **NFR-16** | **Data integrity** | **A short code, once issued, shall never be reassigned to a different destination.** |

## 5. Scope assumptions

- Only HTTP and HTTPS destination URLs are supported.
- The prototype supports anonymous link creation.
- The prototype does not maintain link-creator identity.
- The prototype does not enforce per-creator quotas.
- Every valid create-link request produces a new independent short link.
- Basic analytics means total successful redirect count only.
- Basic analytics are publicly accessible to a caller who knows the short code.
- Links remain active indefinitely in this Greenfield scope unless the service cannot resolve them.
- The prototype targets one primary deployment region.
- The production design must support customers in that primary region with low-latency redirects and resilient operation.
- The supported delivery target is a documented local end-to-end runtime.
- Exact traffic targets, SLI/SLO values, short-code format, redirect status behavior, persistence technology, deployment mechanics, retry settings, and error-response schema are engineering-spec decisions.

## 6. Explicitly out of scope for the prototype

- Production authentication and authorization for link creators
- Per-user or per-client quotas
- Implemented distributed rate limiting and abuse protection
- Custom aliases and branded domains
- Link update, deletion, expiration, or scheduled lifecycle management
- QR code generation
- Detailed event analytics, analytics dashboard, or personal-data collection
- Global multi-region routing, replication, and disaster recovery
- Cloud infrastructure, blue-green/canary deployment automation, and automated rollback
- Production cache, read replicas, event streaming, CDN, and hot-key mitigation infrastructure
- Formal penetration testing and a full chaos-engineering platform

## 7. Acceptance criteria

1. A valid HTTP or HTTPS destination URL can be shortened and returns a unique short URL.
2. Opening a known short URL redirects the requester to exactly the registered destination in the same HTTP client.
3. Invalid destination URLs receive a safe client error.
4. An unknown short code returns a safe `404 Not Found` response and does not redirect.
5. A known short code exposes its total successful redirect count.
6. Concurrent create-link requests do not create conflicting short-code mappings.
7. A dependency-resolution failure results in a safe temporary-failure response and never redirects to an unverified destination.
8. The service can be started and validated end-to-end through documented repository instructions.
9. Automated tests cover creation, redirect, validation, not-found behavior, analytics, concurrency correctness, and dependency-failure behavior.
10. **A destination resolving to a private, loopback, link-local, or cloud-metadata address is rejected with a client error, including when supplied in an encoded address form.**
11. **A failure of the analytics write path does not prevent a valid redirect from being served.**

## 8. Requirement decisions recorded by the engineer

The assessment does not specify users, identity, duplicate-destination behavior, traffic
volume, traffic geography, availability targets, analytics detail, abuse controls, or
deployment environment. The scope assumptions above deliberately make those decisions
visible. The subsequent engineering specification will define the technical design, quality
gates, resilience mechanisms, observability, and future production evolution.

---

## 9. Rationale for requirements added at revision 2

Each addition closes a gap where the original list permitted a compliant implementation that
would nonetheless be wrong. They are recorded here rather than asserted, so a reviewer can
disagree with any of them individually.

### GF-14 — Private and metadata address ranges

GF-10 requires rejecting malformed URLs and unsupported schemes. `http://169.254.169.254/`
is neither: it is well-formed and uses a supported scheme, and it is the AWS instance
metadata endpoint.

A URL shortener is an open redirector by construction — that is its function, not a defect.
The exposure appears the moment any server-side component fetches a destination, which
link-preview or metadata enrichment plausibly will, at which point the service becomes an
SSRF pivot into the private network. Validating at creation costs almost nothing; retrofitting
after such a component exists is expensive, and exploitable in the interim.

Encoded forms are named explicitly because `http://2852039166/` and
`http://0xA9FEA9FE/` are the same address, and a validator that only inspects the hostname
string rejects neither.

### GF-15 — Destination length bound

Unbounded input reaching storage is a denial-of-service vector and a schema decision made by
accident. A stated limit makes it a decision.

### GF-16 — Routing precedence

Short codes resolve at the root, so `/{code}` and application routes such as
`/actuator/health` occupy the same namespace. With fixed-length random codes an accidental
collision is very unlikely — but "unlikely" is a property of the current code format, not of
the design. Making precedence explicit means a future change to code length or alphabet
cannot silently begin shadowing operational endpoints.

### GF-17 and acceptance criterion 11 — Analytics must fail open

The original list requires analytics (GF-11) and requires never issuing an incorrect redirect
(NFR-02), but is silent on what happens when the counter write fails. The silence permits the
obvious implementation — one transaction covering lookup and increment — under which a
counter failure returns an error to a visitor whose destination was perfectly available.

Blocking a user from a page that works, in order to protect a number, inverts the priority
between the product and its instrumentation. Stating it as a requirement means it is tested
rather than assumed, which matters because the correct behaviour is invisible in the code and
a well-meaning refactor will reverse it.

**This requirement is in direct tension with NFR-08** — see open decision D-2.

### GF-18 — Correlation identifier

NFR-04 requires errors to be actionable without exposing internals. Those two pull against
each other: the detail that makes an error diagnosable is usually the detail that must not be
disclosed. A correlation identifier resolves the tension — the caller receives an opaque
handle, and the operator can join it to internal logs.

### NFR-14 — Log hygiene

Destination URLs are attacker-controlled and routinely carry credentials in query strings —
password-reset tokens, signed URLs, session identifiers. Logging them at INFO reproduces
those secrets into every log sink, backup and aggregation pipeline that touches the service.
NFR-04 covers what errors expose to a client; this covers what the service records about
itself, which is a different and more persistent exposure.

### NFR-15 — Enumeration resistance

With anonymous creation (GF-03) and unauthenticated analytics (GF-12), possession of a code
is the only access control that exists. Sequential codes would therefore make the entire link
corpus — and its traffic figures — walkable by counting.

This requirement is a direct consequence of GF-03 and GF-12 rather than a general principle:
those two decisions are defensible, but they place the whole weight of confidentiality on the
code being unguessable.

### NFR-16 — Codes are never reassigned

A printed or messaged short link outlives the service's memory of it. If a code can be
reissued to a different destination, every historical holder of that link is silently
redirected somewhere chosen by someone else, with no way to detect the substitution.

Deletion is out of scope for this prototype, so nothing in the current feature set can reuse
a code. The requirement is stated now because the **data model** either permits reuse or
forecloses it, and that is decided during this scenario whether or not it is discussed.
Scenario 02 introduces expiration, which is the first feature that makes the question live.

---

## 10. Open decisions requiring sign-off

These are not gaps in the requirements. They are points where two requirements pull in
different directions and the resolution changes the engineering spec.

### D-1 — Retry safety versus GF-04

**Tension.** GF-04 states that every successful create-link request produces an independent
short link. Read strictly, this makes creation non-idempotent: a client that times out and
retries cannot know whether the first attempt succeeded, and will create a duplicate link.

For a prototype with anonymous creation this is a minor cost — a stray extra row. It is
recorded because the alternative (an optional client-supplied idempotency key, where a
repeated key returns the original link rather than creating a second one) would be an
*exception* to GF-04 and therefore cannot be introduced by the engineering spec without
amending this document.

| Option | Consequence |
|---|---|
| **A. Keep GF-04 strict** | Simplest; matches the stated assumption exactly. Retries create duplicates |
| **B. Add an optional idempotency key** | Retry-safe. Requires GF-04 to be reworded as "each successful request without a repeated idempotency key" |

**Recommendation: A.** Duplicate links are harmless here — there is no owner, no quota, and
no analytics aggregation that a duplicate would corrupt. Option B adds an API concept and a
uniqueness constraint to solve a problem this prototype does not actually have, and B remains
available later as a purely additive change.

### D-2 — NFR-08 (hot-key resilience) versus GF-17 (synchronous counting)

**Tension.** NFR-08 requires the design to remain responsive when a few links receive
disproportionate traffic. The straightforward implementation of GF-11 — increment a counter
column on the link row during each redirect — makes every redirect of a hot link a write to
**the same database row**, which serialises them. The hot-key scenario is precisely where the
naive analytics implementation degrades worst.

Note that §6 places "hot-key mitigation infrastructure" out of scope, so NFR-08 is a
*design* obligation, not an implemented one.

| Option | Consequence |
|---|---|
| **A. Synchronous counter, measured** | Simple and immediately consistent. Contention is real; the prototype measures it under a deliberately hot key and documents the mitigation path |
| **B. In-process batched counter** | Removes per-request row contention. Introduces loss on ungraceful shutdown, so the count becomes approximate |
| **C. Async event pipeline** | Correct at scale. Out of scope per §6, and unjustifiable in a prototype |

**Recommendation: A, with the contention measured rather than asserted.** The measurement is
the deliverable — it converts NFR-08 from a claim into a number, and gives the documented
production evolution an evidence base. B trades exact counts for throughput and should be a
decision taken against data, not ahead of it.

---

## 11. Change log

| Rev | Change | Reason |
|---|---|---|
| 1 | Initial draft | — |
| 2 | Added GF-14…GF-18, NFR-14…NFR-16, acceptance criteria 10–11 | Close security and failure-mode gaps (§9) |
| 2 | Removed custom-alias requirements | Out of scope per §6 |
| 2 | Removed authenticated creation and owner-scoped analytics | Superseded by GF-03 and GF-12 |
| 2 | Recorded open decisions D-1, D-2 | Requirement tensions needing sign-off before the engineering spec |

---

## 12. Gate A — approval required

- [ ] The added requirements in §3 and §4 are accepted, or individually rejected with reasons.
- [ ] **D-1** resolved — recommendation: keep GF-04 strict, no idempotency key.
- [ ] **D-2** resolved — recommendation: synchronous counter, contention measured.
- [ ] Acceptance criteria 10 and 11 are accepted.

**Approved by:** _________________  **Date:** __________
