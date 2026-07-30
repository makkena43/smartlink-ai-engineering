# API Overview

**Authoritative contract:** the generated OpenAPI document at `/v3/api-docs`, browsable at
`/swagger-ui.html`. This page is the human-readable summary and the reasoning behind the
shape; where the two disagree, the generated document wins and this page is the bug.
Documentation is generated from the implementation, never hand-maintained.

Full design detail: [`scenarios/01-greenfield/engineering-spec.md`](scenarios/01-greenfield/engineering-spec.md) §4.

---

## Surface

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/links` | none | Create a short link |
| `GET` | `/{code}` | none | Resolve — the product |
| `GET` | `/api/v1/links/{code}/analytics` | none | Total redirect count |
| `GET` | `/actuator/health` | none | Aggregate health |
| `GET` | `/actuator/health/liveness` | none | Process health |
| `GET` | `/actuator/health/readiness` | none | Dependency health |
| `GET` | `/v3/api-docs` · `/swagger-ui.html` | none | Generated contract |

**Nothing is authenticated.** That is a deliberate prototype boundary (GF-03), not an
oversight — and it is why the short code carries the entire weight of confidentiality, which
in turn is why codes are cryptographically random rather than sequential.

Resolution is mounted at the **root** so short links stay short; a `/r/` prefix would defeat
the product's only reason to exist. Application routes are matched **before** code
resolution, so `/api/v1/**`, `/actuator/**`, `/v3/api-docs` and `/swagger-ui**` can never be
shadowed by an issued code.

---

## Create — `POST /api/v1/links`

```http
POST /api/v1/links
Content-Type: application/json

{
  "destinationUrl": "https://www.example.com/campaign",
  "expiresAt": "2026-08-01T00:00:00Z"
}
```

`expiresAt` is **optional**. Omit it for a link that never expires — which is exactly the
behaviour before scenario 02, so existing callers are unaffected. It must be an ISO-8601 instant
carrying an offset; a zone-less local date-time is refused rather than guessed at, because
guessing is how a campaign silently expires five and a half hours early.

```http
HTTP/1.1 201 Created
Location: /api/v1/links/aB92xK7

{
  "code": "aB92xK7",
  "shortUrl": "http://localhost:8080/aB92xK7",
  "destinationUrl": "https://www.example.com/campaign",
  "createdAt": "2026-07-30T10:15:30Z",
  "expiresAt": "2026-08-01T00:00:00Z"
}
```

| Status | When |
|---|---|
| `201` | Created |
| `400` | Request body could not be parsed, **or `expiresAt` is malformed, zone-less or not in the future** |
| `422` | Destination rejected by policy |
| `503` | Dependency unavailable, or code allocation exhausted its attempts |

**Submitting the same destination twice returns two different codes**, on purpose (GF-04).
No lookup by destination happens anywhere in the create path — that absence is what
implements the requirement.

---

## Resolve — `GET /{code}`

```http
GET /aB92xK7
```
```http
HTTP/1.1 302 Found
Location: https://www.example.com/campaign
Cache-Control: no-store
```

| Status | When |
|---|---|
| `302` | Resolved |
| `404` | Unknown **or malformed** code |
| `410` | **Link existed and has expired.** No `Location` header — a redirect-following client cannot reach the destination |
| `503` | Mapping could not be verified |

`302` rather than `301`, with `no-store`, so every click reaches the service and the redirect
count stays complete. A cached `301` cannot be recalled, so `302 → 301` remains available
later while the reverse never will.

A malformed code returns `404`, identical to an unknown one. Distinguishing the two would
turn this endpoint into a probing oracle.

The destination is returned **byte-identical**, including query string and fragment.
Normalising it would silently break signed URLs and tracking parameters.

---

## Analytics — `GET /api/v1/links/{code}/analytics`

```json
{
  "code": "aB92xK7",
  "destinationUrl": "https://www.example.com/campaign",
  "createdAt": "2026-07-30T10:15:30Z",
  "totalRedirects": 1432,
  "expiresAt": "2026-08-01T00:00:00Z",
  "status": "ACTIVE"
}
```

Aggregate count only — no IP, user-agent, referrer, geography or device (NFR-13). That is
enforced by the schema, which has nowhere to put them, rather than by convention.

A failure to record a redirect **never fails the redirect**. The count is best-effort by
design: blocking a user from a page that works, in order to protect a number, inverts the
priority between the product and its instrumentation.

---

## Errors

RFC 9457 `application/problem+json`.

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/problem+json

{
  "status": 422,
  "code": "INVALID_URL",
  "detail": "The destination URL is invalid or unsupported.",
  "requestId": "9f2c1a7e-4b31-4c8e-bb17-2d5f0a9c1e33"
}
```

| Condition | HTTP | Public code |
|---|---:|---|
| Malformed request body | 400 | `MALFORMED_REQUEST` |
| Destination rejected by policy | 422 | `INVALID_URL` |
| Unknown short code | 404 | `LINK_NOT_FOUND` |
| Link expired | 410 | `LINK_EXPIRED` |
| Invalid expiry | 400 | `INVALID_EXPIRY` |
| Rate limited *(production only — not implemented)* | 429 | `RATE_LIMITED` |
| Dependency unavailable | 503 | `SERVICE_UNAVAILABLE` |
| Unexpected failure | 500 | `INTERNAL_ERROR` |

Three distinctions carry operational weight:

- **400 vs 422.** Unparseable is `400`; well-formed but declined by policy is `422`. Collapsing them tells a caller nothing about whether a corrected URL is worth retrying.
- **503 vs 500.** `503` means come back; `500` means someone must look. Collapsing them destroys the signal that decides whether to alert.
- **Collision exhaustion is `503`, not `500`.** Nothing is broken — the attempts were consumed, and the request is safely retryable.

Error bodies never contain stack traces, SQL state, database messages, internal hostnames, or
credentials, and **never echo submitted input unescaped** — reflecting an attacker-supplied
destination into an error body is how a validation endpoint becomes the XSS vector it was
added to prevent.

---

## Destination policy

A shortener is an open redirector by construction. The policy bounds what can be redirected
to, and what a submitted string can do on its way through. Rejected with `422`:

| Rule | Examples |
|---|---|
| Non-`http(s)` scheme | `javascript:`, `data:`, `file:`, `vbscript:`, `blob:` |
| Private / loopback / link-local / metadata address | `127.0.0.1`, `10.0.0.1`, `169.254.169.254` |
| Blocked address in any notation | `2852039166`, `0xA9FEA9FE`, `0251.0376.0251.0376`, `[::ffff:169.254.169.254]`, `expected.com@169.254.169.254` |
| Over 2 048 characters | — |
| Control characters | CR, LF, NUL, raw tab |

The credential-embedded form is the one worth knowing about: everything before `@` is
userinfo and is discarded by the parser, so `http://expected.com@169.254.169.254/` reads as
a legitimate host to a human and to any substring check, while actually addressing the cloud
metadata endpoint.

Control characters matter because the destination is written into a `Location` **response
header** — a `%0d%0a` payload is a response-splitting primitive, not merely an XSS one.

Full rationale, including the accepted time-of-check-to-time-of-use limitation:
engineering-spec §8.1.

---

## Versioning

`/api/v1` is explicit from the first commit. The resolve path is deliberately **unversioned**:
short links are printed and messaged, and a version prefix would make every issued link a
hostage to an internal decision.
