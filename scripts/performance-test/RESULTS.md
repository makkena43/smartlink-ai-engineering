# Performance results

**These figures describe one laptop, and they vary by a factor of nine depending on what else
that laptop is doing.** That is the headline finding, and it is the reason nothing here is quoted
as a production capability.

What the measurement legitimately supports is a **ratio** — the cost of hot-key contention —
which turns out to be stable even while the absolute numbers are not.

---

## Environment

| | |
|---|---|
| Machine | Apple M1, 8 cores, 8 GB RAM |
| Container runtime | Colima (Virtualization.framework), aarch64, Docker 29.5.2 |
| Application | Java 21.0.11, Spring Boot 3.5.16, single instance |
| Database | PostgreSQL 16-alpine, **co-located on the same host** |
| Load generator | k6 v2.1.0, **also on the same host** |
| Profile | 25 virtual users, 10 s ramp + 30 s steady, redirects not followed |
| Fixture | 500 seeded links |

Three processes competing for eight cores, with no network between them, on a desktop machine
running its owner's applications. Every one of those facts matters below.

---

## The two scenarios

Run separately. **The delta is the deliverable** — either in isolation says very little.

| Scenario | What it does |
|---|---|
| **spread** | Requests distributed across all 500 codes. Baseline read cost. |
| **hot** | Every request resolves **one** code, so every redirect writes the same row. |

---

## Three runs of identical code

The same build, measured three times as host conditions changed. Load average is the 1-minute
figure at the start of each run, on an 8-core machine.

| Run | Host load | Scenario | Throughput | p50 | p90 | p95 |
|---|---:|---|---:|---:|---:|---:|
| 1 | low | spread | 869 req/s | 20.5 ms | 45.0 ms | 55.7 ms |
| 1 | low | hot | 634 req/s | 23.3 ms | 76.8 ms | 101.8 ms |
| 2 | ~11.6 | spread | 150 req/s | 92.5 ms | 291.9 ms | 507.1 ms |
| 2 | ~11.6 | hot | 96 req/s | 167.1 ms | 614.4 ms | 1 100 ms |
| 3 | 10.4 / 17.6 | spread | 221 req/s | 69.8 ms | 176.5 ms | 230.4 ms |
| 3 | 10.4 / 17.6 | hot | 145 req/s | 75.3 ms | 312.4 ms | 492.6 ms |

Error rate was **0.000 %** and check pass rate **100 %** in all six measurements. Every response
was a correct `302` with a `Location` header and `Cache-Control: no-store`. Correctness never
degraded — only latency did.

During run 2 the service itself was consuming **7.6 % CPU**; the load came from Safari, a
browser-based desktop application and the window server. **p95 for identical code moved from
55.7 ms to 507 ms because of processes that have nothing to do with this system.**

Anyone tempted to read a laptop benchmark as a capability statement should read that sentence
twice. It is also why the k6 thresholds gate on error rate only and never on latency.

---

## What survives: the contention ratio

| Run | p95 hot ÷ spread | throughput hot ÷ spread |
|---|---:|---:|
| 1 | 1.83× | 0.73 |
| 2 | 2.17× | 0.64 |
| 3 | 2.14× | 0.66 |

**The ratio holds within about 15 % while the absolute values swing by 9×.** Both scenarios are
perturbed by host load in the same way, so the comparison between them survives conditions that
destroy the individual measurements.

### What this says about NFR-08

The synchronous counter was accepted knowingly, with hot-row contention named as its known
failure mode. Measured rather than assumed:

> **Concentrating all traffic on a single link roughly doubles p95 latency and costs about a
> third of throughput, while leaving correctness untouched.**

The *shape* is as informative as the size. In run 1 the median moved 14 % while p95 moved 83 % —
the signature of requests queueing behind a row-level lock, not of the service being uniformly
slower. Most requests are unaffected; the unlucky ones wait for whoever holds the row.

That also sets the trigger for the documented evolution. When hot-key p95 stops being acceptable,
the answer is to move the counter **off** the redirect path (asynchronous events), not to add
database capacity — the contention is on one row, and more machines do not make a single row
wider.

---

## Against the design targets

The spec proposes p95 < 100 ms for redirects in production. Run 1 lands at 55.7 ms spread.

**That is not the target being met.** Runs 2 and 3 of the same code land at 507 ms and 230 ms. A
number that falls on the favourable side of a line in one run out of three is not evidence the
line has been cleared — it is evidence the measurement is dominated by something other than the
service.

What these figures legitimately support:

- **Regression detection**, when runs are compared under similar host conditions.
- **The contention ratio**, which is a like-for-like comparison and demonstrably robust.

Nothing else.

---

## Reproducing

```bash
docker compose up --build -d
./scripts/performance-test/seed.sh 500
k6 run -e SCENARIO=spread scripts/performance-test/load-test.js
k6 run -e SCENARIO=hot    scripts/performance-test/load-test.js
```

Record `uptime` alongside any result. A figure without the host load beside it is not
reproducible, as the table above demonstrates.

---

## Not measured

1. **Multi-instance throughput.** One process. Horizontal scaling is a property of the design — no node-local state, asserted by ArchUnit — not something demonstrated here.
2. **Sustained load.** 30 seconds. Nothing about pool exhaustion, memory growth or GC over hours.
3. **Realistic key distribution.** Uniform-random and single-key are the two extremes; real traffic is Zipfian and sits between them.
4. **Creation throughput under load.** The write path was exercised only by seeding.
5. **Cold start.** Every figure is post-warm-up, deliberately.
6. **Network effects.** There is no network here. In production it would likely dominate everything above.
7. **A quiet machine.** Never achieved. Stated rather than hidden.
