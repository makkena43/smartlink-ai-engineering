# Performance test

**What this is:** a bounded local load run against the redirect path.

**What this is not:** evidence of production capacity. Numbers produced here describe one
laptop, one JVM, one container runtime, and a database on the same machine as the service.
They do not extrapolate, and this project does not extrapolate them. Constitution v1.1
amended the performance gate specifically so that it gates on *honest reporting of method
and limits* rather than on hitting a number the environment cannot legitimately produce.

## Why measure at all, then

Two things a local run genuinely establishes:

1. **Regression detection.** If p95 doubles between v1 and v2, that is real and worth
   knowing, whatever the absolute number is.
2. **Hot-path cost of the synchronous counter.** Spec v1 A-05 accepts synchronous analytics
   as a trade-off and names hot-row contention as the known failure mode. A run that
   concentrates load on a *single* code, compared against load spread across many codes,
   measures that contention directly instead of assuming it.

The second is the one that matters. It converts an accepted trade-off from an assertion
into a measurement.

## Method

Run against the compose stack, service warmed, database seeded.

| Parameter | Value |
|---|---|
| Scenario A | resolve 1 000 distinct codes, uniformly — baseline read cost |
| Scenario B | resolve a single hot code — isolates counter contention |
| Duration | 60 s per scenario, after a 30 s warm-up |
| Concurrency | stepped: 10, 25, 50 virtual users |
| Reported | p50, p95, p99, error rate, throughput |

Every reported result must state: machine and core count, JVM version, container runtime,
whether the database was containerised on the same host, and sample size. A number without
those is not a result.

## Files

Populated at implementation (task T-14). Until the redirect path exists there is nothing to
measure, and a load script committed against endpoints that return 404 would produce
confident-looking numbers that mean nothing at all.

- `load-test.js` — k6 script covering scenarios A and B
- `seed.sh` — creates the fixture links both scenarios resolve
- `RESULTS.md` — measured output, with the environment stated
