# API Overview

**Authoritative contract:** the generated OpenAPI document at `/v3/api-docs`, browsable at
`/swagger-ui.html`. This page is the human-readable summary and the reasoning behind the
shape; where the two ever disagree, the generated document wins and this page is the bug.
Documentation is generated from the implementation, never hand-maintained (AC-6.6).

---

## Surface

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/links` | API key | Create a short link |
| `GET` | `/{code}` | none | Resolve — the product |
| `GET` | `/api/v1/links/{code}/stats` | API key, owner-scoped | Read analytics |
| `GET` | `/actuator/health/liveness` | none | Process health |
| `GET` | `/actuator/health/readiness` | none | Dependency health |
| `GET` | `/v3/api-docs` · `/swagger-ui.html` | none | Generated contract |

Resolution is mounted at the **root** so short links stay short — a `/r/` or `/api/v1/`
prefix on the resolve path would defeat the product's only reason to exist. Everything else
is namespaced under `/api/v1`. That is precisely why the reserved-word denylist (ADR-003)
is necessary rather than merely tidy: a custom alias of `api` or `actuator` would otherwise
shadow the management surface.

---

## Create — `POST /api/v1/links`

```http
POST /api/v1/links
X-API-Key: <key>
Idempotency-Key: 7d3f...        # optional
Content-Type: application/json

{
  "destinationUrl": "https://example.com/campaign?utm_source=email",
  "alias": "spring-sale"        # optional
}
```

```http
HTTP/1.1 201 Created
Location: /api/v1/links/spring-sale

{
  "code": "spring-sale",
  "shortUrl": "http://localhost:8080/spring-sale",
  "destinationUrl": "https://example.com/campaign?utm_source=email",
  "codeKind": "CUSTOM",
  "createdAt": "2026-07-30T09:14:22Z"
}
```

| Status | When |
|---|---|
| `201` | Created |
| `401` | Missing or invalid API key (AC-1.6) |
| `409` | Alias already claimed (AC-3.2), or idempotency key reused with a different body (AC-1.4) |
| `422` | Invalid destination or malformed alias (FR-4, AC-3.3, AC-3.4) |

**Idempotency is explicit, never inferred.** Submitting the same destination twice without a
key produces two independent links, on purpose — implicit deduplication would merge two
campaigns into one analytics bucket, irreversibly (ADR-001's sibling decision, A-02).

---

## Resolve — `GET /{code}`

```http
GET /spring-sale
```

```http
HTTP/1.1 302 Found
Location: https://example.com/campaign?utm_source=email
Cache-Control: no-store
```

| Status | When |
|---|---|
| `302` | Resolved |
| `404` | Unknown code |
| `503` | Datastore unavailable — never a guessed or stale destination (AC-6.4) |

`302` rather than `301`, with `no-store`, so every click reaches the service and analytics
stay complete. A cached `301` cannot be recalled; the decision and its asymmetry are in
ADR-001.

The destination is returned **byte-identical**, including query string and fragment
(AC-2.4). Normalising it would silently break signed URLs and tracking parameters.

---

## Stats — `GET /api/v1/links/{code}/stats`

```http
GET /api/v1/links/spring-sale/stats
X-API-Key: <key>
```

```json
{
  "code": "spring-sale",
  "totalResolutions": 1432,
  "firstResolvedAt": "2026-07-30T09:15:01Z",
  "lastResolvedAt": "2026-07-30T18:42:55Z"
}
```

Counters only — no IP, no user-agent, no referrer, by decision (ADR-005).

A key that is valid but does not own the link receives **`404`, not `403`**. Distinguishing
"not yours" from "does not exist" would turn this endpoint into an enumeration oracle: a
caller could map the entire namespace by reading which errors come back (AC-5.3).

---

## Errors

RFC 9457 `application/problem+json` throughout.

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json
X-Correlation-Id: 9f2c1a7e-...

{
  "type": "https://smartlink.dev/problems/invalid-destination",
  "title": "Invalid destination URL",
  "status": 422,
  "detail": "Scheme 'javascript' is not permitted. Allowed schemes: http, https.",
  "rule": "destination.scheme"
}
```

Two properties hold on every error response:

- **The violated rule is named** (`rule`), so a caller can act programmatically rather than
  string-matching prose.
- **Raw input is never echoed back unescaped** (AC-4.5). Reflecting an attacker-supplied
  destination into an error body is how a validation endpoint becomes an XSS vector.

Every response — success or failure — carries `X-Correlation-Id`, echoed from the request
when supplied (AC-6.3).

---

## Authentication

`X-API-Key` on creation and stats. Resolution is anonymous, because that is the product
(A-08).

Keys are seeded configuration supplied by environment, compared in constant time. There is
no issuance, rotation or revocation flow — deliberately out of scope, and stated in
`tradeoffs-and-risks.md` §4 rather than left for a reviewer to discover.

---

## Versioning

`/api/v1` is explicit from the first commit. The resolve path is deliberately unversioned:
short links are printed and messaged, and a version prefix in them would make every issued
link a hostage to an internal decision.
