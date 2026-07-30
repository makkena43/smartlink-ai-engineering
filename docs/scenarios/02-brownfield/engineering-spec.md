# Scenario 02 - Brownfield Engineering Specification

**Status:** Not started; blocked by Scenario 01 Gate D and Brownfield Gate A.  
**Requirements:** [requirements.md](requirements.md)  
**Pre-change analysis:** [impact-analysis.md](impact-analysis.md)

## 1. Change summary

This change evolves the existing SmartLink service. It adds optional expiry at link creation so campaign links can stop resolving at a defined UTC instant.

| Surface | Greenfield baseline | Brownfield change |
|---|---|---|
| Create API | Destination URL | Optional `expiresAt` field. |
| Link model | No lifecycle timestamp | Nullable `expiresAt`. |
| Schema | Existing `short_link` mapping | Nullable `expires_at` field. |
| Redirect | Known code returns 302 | Expired code returns 410 and never redirects. |
| Analytics | Successful click total | Expiry value and `ACTIVE` / `EXPIRED` status. |
| Tests | Greenfield suite | New lifecycle, migration and compatibility tests. |

## 2. API compatibility and versioning

The management API remains under `/api/v1`. This change is additive: `expiresAt` is optional, existing callers can omit it, and response fields are only added. Public shared links remain unversioned `GET /{code}`.

```http
POST /api/v1/links
Content-Type: application/json

{
  "destinationUrl": "https://www.example.com/campaign",
  "expiresAt": "2026-08-01T00:00:00Z"
}
```

Omitting `expiresAt` preserves Greenfield non-expiring behaviour. `/api/v2` is reserved for genuinely breaking management API changes and is not required for this scenario.

## 3. Domain and redirect rule

Expiry is evaluated against an authoritative clock and is deterministic in tests through an injected or controlled clock.

```text
active(link, now)  = expiresAt is absent OR now is before expiresAt
expired(link, now) = expiresAt is present AND now is equal to or after expiresAt
```

```text
resolve verified mapping
→ mapping missing: 404 LINK_NOT_FOUND
→ mapping expired: 410 LINK_EXPIRED; no Location header; no analytics increment
→ mapping active: increment successful redirect count, then 302 Location: destination
```

| Condition | HTTP | Public code | Safe message |
|---|---:|---|---|
| Malformed or missing expiry | 400 | `INVALID_EXPIRY` | The expiration time is invalid. |
| Expiry not in the future | 400 | `INVALID_EXPIRY` | The expiration time must be in the future. |
| Link expired | 410 | `LINK_EXPIRED` | This short link is no longer active. |

## 4. Data model and migration safety

Add a nullable field through a forward-only Flyway migration:

```text
short_link
----------
... existing Greenfield fields ...
expires_at    timestamp with time zone, nullable
```

Use expand-contract delivery:

1. Add nullable `expires_at`; do not modify existing rows.
2. Deploy compatible code that treats `NULL` as non-expiring.
3. Verify existing links, omitted expiry, active expiry and expired expiry.
4. Do not perform destructive schema work in this release.

Application rollback is safe because the prior version ignores the additive nullable column. Database rollback is not required for this harmless migration. No column is dropped or renamed in the same release.

## 5. Implementation tasks

| ID | Task | Dependencies | Acceptance evidence |
|---|---|---|---|
| B1 | Review API, model, redirect, analytics, migration and tests | Scenario 01 Gate D | Approved impact analysis. |
| B2 | Add nullable `expires_at` migration | B1 | Upgrade from Greenfield schema succeeds. |
| B3 | Extend entity, DTOs, OpenAPI and response mapping | B1, B2 | Existing payload works; optional field documented. |
| B4 | Add UTC validation and clock-based lifecycle rule | B3 | Boundary-time tests pass. |
| B5 | Extend creation service and response | B3, B4 | Create with and without expiry succeeds. |
| B6 | Extend redirect and analytics | B4, B5 | Active 302; expired 410; no expired increment. |
| B7 | Add regression, migration, API and lifecycle tests | B2-B6 | Greenfield and Brownfield suites pass. |
| B8 | Update evidence and release documentation | B7 | Reviewer understands change without Git history. |

## 6. Validation plan

| Level | Required proof |
|---|---|
| Unit | No expiry, future expiry, expired time, exact boundary, malformed and past expiry. |
| API / contract | Optional request field; 400 invalid expiry; 410 expired redirect; additive response compatibility. |
| Integration | Upgrade from Greenfield schema; existing `NULL` row; persisted expiry round trip. |
| Regression | Full Scenario 01 suite passes unchanged. |
| Acceptance | Create non-expiring and expiring links; resolve before and after expiry; inspect analytics status. |
| Release safety | Demonstrate additive migration and document application rollback safety. |

## 7. Risks and decisions

| Risk / decision | Treatment |
|---|---|
| Time-zone ambiguity | Accept UTC instants only and persist timezone-aware timestamps. |
| Boundary flakiness | Inject or control the clock in tests. |
| Existing-client breakage | Optional field, additive responses, nullable migration and regression suite. |
| Future cache staleness | Cache TTL must not outlive expiry; recheck active state if cache data can be stale. |
| Storage growth | Retain expired records in this scope; defer retention until business/compliance input exists. |

## 8. Definition of done

The Brownfield scenario is complete when BF-01 through BF-07 and BC-1 through BC-5 are met. The additive migration and compatibility tests pass. Active links redirect. Expired links return `410 Gone` without a `Location` header or successful-redirect increment. Final documentation describes rollout, rollback, limitations and validation without relying on Git history.
