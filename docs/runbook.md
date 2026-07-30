# SmartLink — Operational Runbook

**Scope:** the resolve path of a single deployment unit. Scenario 03 (R-6).

Every command here has been run against the service. Every endpoint and metric named below
exists and is asserted by a test — a runbook that references a dashboard nobody built is worse
than no runbook, because it is discovered to be fiction at the exact moment it is needed.

**What this runbook cannot do.** It assumes one deployment unit and one PostgreSQL. There is no
failover step, no replica promotion and no traffic-shifting step, because none of those exist in
this system. Where the correct answer is "escalate", it says so rather than inventing a
procedure.

---

## 1. Signals, and where to read them

| Signal | Where |
|---|---|
| Aggregate health | `GET /actuator/health` |
| Process alive | `GET /actuator/health/liveness` |
| Safe to route traffic here | `GET /actuator/health/readiness` |
| Request rate, status, latency | `GET /actuator/metrics/http.server.requests` |
| Degraded analytics | `GET /actuator/metrics/smartlink.analytics.write.failures` |
| Correlation id for a single failure | `requestId` in any error body; same value in the logs |

```bash
curl -s localhost:8080/actuator/health/readiness
```

```bash
curl -s "localhost:8080/actuator/metrics/http.server.requests?tag=uri:/{code}&tag=status:503"
```

> **Production note.** These endpoints are unauthenticated in this prototype, as everything else
> is (GF-03). Request metrics disclose traffic shape and error rates, so a real deployment binds
> the management port separately and authenticates it. This is stated here, not only in the
> config, because it is an operational decision rather than a formatting one.

---

## 2. Readiness is DOWN

**What it means.** The instance cannot verify a mapping against its database. It is telling the
load balancer to stop routing to it. That is the system working, not failing.

**Do not restart the instance.** Liveness is deliberately independent of the database, and this
is why. If readiness DOWN triggered restarts, an outage would restart every instance at once and
add a reconnect storm to a database that is already struggling — a recoverable dependency
failure converted into a self-inflicted one.

1. Confirm the split: liveness should still be UP.
   ```bash
   curl -s -o /dev/null -w "liveness %{http_code}\n" localhost:8080/actuator/health/liveness
   ```
   Liveness DOWN as well means the process itself is unhealthy — a different problem, and the
   only case where restarting is right.
2. Check the database directly from the instance's network position. Connectivity, credentials,
   connection count, and whether the host is accepting connections at all.
3. Check whether the pool is exhausted rather than the database being down. `connection-timeout`
   is 2 s, so pool starvation and an unreachable database look similar from outside and are not
   the same incident.

**Recovery is automatic.** Readiness returns UP within ~10 s of the database becoming reachable,
with no restart and no manual step. This is asserted by `ReadinessRecoveryIT`, not assumed. If
readiness does *not* return within a minute of the database demonstrably recovering, that is a
defect — capture the health body and the logs before restarting anything, because a restart
destroys the only evidence.

**Escalate to:** the datastore owner. **Do not** work around it by enabling a cache; see §6.

---

## 3. Resolve `503` rate is rising

**What it means.** The service cannot verify mappings and is refusing to guess. Users see an
error instead of being sent somewhere possibly wrong.

1. Split the rate by outcome first — a `503` rise with a flat request rate is a dependency
   problem; a `503` rise alongside a traffic spike is a capacity problem, and they have opposite
   fixes.
2. Check latency on the same URI. Requests failing at ~2 s are hitting `statement_timeout`,
   meaning the database is *slow*, not gone. Requests failing faster are connection failures.
3. Check readiness. If readiness is UP while resolves are failing, the health probe and the
   resolve path disagree — which is itself the finding, and worth capturing.

**Escalate to:** the datastore owner, with the latency split and the timeout distinction from
step 2 attached. That distinction is what tells them whether to look at load or at availability.

---

## 4. Resolve latency is up but errors are not

**What it means.** The database is answering, slowly. Nothing has failed yet, and the service is
absorbing the delay up to a 2 s ceiling.

1. Confirm requests are completing under the ceiling. Sustained latency near 2 s means the next
   increment turns into a `503` wall, and this is the warning before that.
2. Look at pool saturation before looking at the database. 20 connections with slow queries
   queues requests that are individually fine.
3. Check whether one code dominates traffic. Hot-key contention on the counter update is a known
   characteristic of this design (ADR-004), measured in the greenfield performance work.

**Do not add retries as a mitigation.** Retrying into a saturated dependency deepens the outage
it is meant to survive. Query timeouts are deliberately excluded from retry for this reason —
see [ADR-013](decisions.md#adr-013).

---

## 5. `smartlink.analytics.write.failures` is climbing

**What it means.** Redirects are still being served correctly, and their counts are not being
recorded. **This is not a user-facing incident** and must not be treated as one.

1. Confirm redirects are healthy: the `302` rate should be unaffected. If it is, the product is
   working and only its instrumentation is degraded.
2. Investigate the write path specifically — reads are evidently fine, or resolution would be
   failing too. An asymmetric read-healthy/write-failing database is the usual cause.

**The number to trust afterwards:** none. Counters missed during this window are lost, not
queued. Say so when reporting figures for the period rather than presenting an undercount as
fact.

**Escalate to:** the datastore owner, at normal priority. Do not page.

---

## 6. Shutdown takes longer than the grace period

**What it means.** In-flight requests are not finishing within 30 s
(`spring.lifecycle.timeout-per-shutdown-phase`).

1. Check for in-flight requests blocked on the database. With a 2 s statement timeout, no single
   request should approach 30 s, so this points at either a much slower dependency or a request
   that is not going through the normal path.
2. Confirm the platform's termination grace period is **longer** than the application's. If the
   platform's is shorter, it sends SIGKILL first and the graceful shutdown never gets to run —
   the symptom is dropped requests on every deploy, and it is a platform misconfiguration rather
   than an application bug.

**Do not shorten the application grace period to make deploys faster.** That trades a visible
delay for invisible dropped requests.

---

## 7. Things that are not remedies here

Named explicitly because each is a plausible-sounding thing to reach for during an incident, and
each would break something this system guarantees.

| Tempting action | Why not |
|---|---|
| Enable a cache to ride out a database outage | Serves stale destinations. A link whose owner has stopped it would keep redirecting, breaking the guarantee the product exists to provide (AS-4, ADR-002). A cache is reintroducible — with an explicit coherency bound, and as a decision, not as an incident workaround |
| Add retries to reduce the `503` rate | Multiplies load on a dependency that is already failing, and hides the signal that says so |
| Make liveness depend on the database | Turns a dependency outage into a fleet-wide restart storm |
| Restart instances showing readiness DOWN | Same, by hand |
| Raise `statement_timeout` to stop timeouts | Converts fast failures into held threads and pool exhaustion. The timeout is what stops one slow dependency exhausting the request pool |

---

## 8. What has not been proven

Stated here so an operator knows the edge of the evidence rather than discovering it during an
incident:

- Every behaviour above was validated **on a single laptop, against fault injection**. None of it
  is a production measurement, and the SLO targets in the engineering spec are design targets.
- There is no measured baseline for normal traffic, so "elevated" in §3 and §4 has no number
  attached yet. The first production week supplies it.
- Multi-instance behaviour is untested. The clock is database-authoritative
  ([ADR-012](decisions.md#adr-012)), so expiry decisions are consistent across instances by
  construction, but that is a design property here rather than an observed one.
