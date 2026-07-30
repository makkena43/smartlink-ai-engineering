Greenfield Requirements - SmartLink URL Shortener

## 1. Product objective

SmartLink enables users to create compact links for long web destinations, share those links, redirect recipients to the registered destination, and view basic link usage information.

The Greenfield prototype must be runnable end-to-end and demonstrate a production-minded approach to correctness, security, resilience, scalability, and safe change management.

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
| GF-14 | Input validation / scheme safety | The system shall accept only `http` and `https` destination URL schemes, and shall reject all other schemes, including but not limited to `javascript:`, `data:`, `file:`, `vbscript:`, and `blob:`. |
| GF-15 | Input validation / server-side request forgery | The system shall reject destination URLs whose host resolves to a private, loopback, link-local, multicast, or cloud-metadata address range. |
| GF-16 | Input validation / evasion resistance | The system shall reject a blocked destination address regardless of the notation used to express it, including decimal, octal, hexadecimal, IPv6-mapped, and credential-embedded forms. |
| GF-17 | Input validation / bounds | The system shall reject destination URLs and short codes that exceed documented maximum lengths. |
| GF-18 | Injection safety / response integrity | The system shall reject destination URLs containing control characters, and shall never emit unescaped user-supplied input into a response body or response header. |
| GF-19 | Redirect target integrity | The system shall redirect only to a destination that passed validation at creation time and has been stored without modification. |

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
| NFR-14 | Security / injection resistance | The system shall not construct data-store queries, log statements, or response headers by string concatenation of unvalidated user input. |
| NFR-15 | Security / validation placement | Destination validation shall be enforced within the application core, so that it cannot be bypassed by an alternative entry point or transport. |
| NFR-16 | Security / fail-closed validation | Where a destination cannot be conclusively validated, the system shall reject it rather than accept it. |

## 5. Scope assumptions

- Only HTTP and HTTPS destination URLs are supported.
- A URL shortener is an open redirector by design; the validation requirements bound that behavior rather than remove it.
- Destination address validation is evaluated against the resolved address, not the submitted hostname text.
- Destination validation is applied at creation time; the prototype does not re-validate stored destinations at redirect time.
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
10. A destination using a non-HTTP/HTTPS scheme, including `javascript:` and `data:`, is rejected with a client error and is never stored.
11. A destination resolving to a private, loopback, link-local, or cloud-metadata address is rejected with a client error.
12. A blocked destination address expressed in decimal, octal, hexadecimal, IPv6-mapped, or credential-embedded notation is rejected with the same result as its plain form.
13. A destination containing control characters, including carriage return or line feed, is rejected and cannot alter the headers of any response.
14. Error responses arising from an invalid destination do not contain the raw submitted value in unescaped form.

## 8. Requirement decisions recorded by the engineer

The assessment does not specify users, identity, duplicate-destination behavior, traffic volume, traffic geography, availability targets, analytics detail, abuse controls, or deployment environment. The scope assumptions above deliberately make those decisions visible. The subsequent engineering specification will define the technical design, quality gates, resilience mechanisms, observability, and future production evolution.