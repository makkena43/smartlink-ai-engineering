// SmartLink redirect-path load test.
//
//   k6 run -e SCENARIO=spread scripts/performance-test/load-test.js
//   k6 run -e SCENARIO=hot    scripts/performance-test/load-test.js
//
// Two scenarios, run separately so neither perturbs the other:
//
//   spread - requests distributed across every seeded code. Baseline read cost.
//   hot    - every request resolves ONE code, so every redirect writes the same row.
//
// The delta between them is the actual deliverable. It measures the hot-row contention that
// the synchronous-counter decision knowingly accepted, converting NFR-08 from a claim into a
// number. Either run in isolation says very little.

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const codes = JSON.parse(open('./codes.json'));
const scenario = __ENV.SCENARIO || 'spread';
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';

const redirectDuration = new Trend('redirect_duration', true);

export const options = {
  // Ramp, then hold. The ramp is warm-up: JIT compilation, connection pool fill and page
  // cache all settle during it, and measuring through that would report the warm-up rather
  // than the service.
  stages: [
    { duration: '10s', target: 25 },
    { duration: '30s', target: 25 },
  ],
  thresholds: {
    // Correctness is gated. Latency is REPORTED, not gated: a threshold on a laptop
    // measurement would be a production claim this environment cannot support, and the
    // constitution was amended specifically to stop the performance gate implying one.
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  // Never follow the redirect. Following it would measure example.com, not this service -
  // and would send real traffic somewhere it has no business going.
  noVUConnectionReuse: false,
  discardResponseBodies: true,
};

export default function () {
  const code =
    scenario === 'hot'
      ? codes.hot
      : codes.spread[Math.floor(Math.random() * codes.spread.length)];

  const response = http.get(`${baseUrl}/${code}`, { redirects: 0 });

  redirectDuration.add(response.timings.duration);

  check(response, {
    'status is 302': (r) => r.status === 302,
    'Location present': (r) => !!r.headers['Location'],
    'not cacheable': (r) => r.headers['Cache-Control'] === 'no-store',
  });
}

export function handleSummary(data) {
  const m = data.metrics.http_req_duration.values;
  const failed = data.metrics.http_req_failed.values.rate;
  const total = data.metrics.http_reqs.values.count;
  const rps = data.metrics.http_reqs.values.rate;

  const line = (label, value) => `  ${label.padEnd(16)} ${value}`;
  const summary = [
    ``,
    `SmartLink redirect load - scenario: ${scenario}`,
    `----------------------------------------------`,
    line('requests', total),
    line('throughput', `${rps.toFixed(1)} req/s`),
    line('p50', `${m['p(50)'].toFixed(2)} ms`),
    line('p95', `${m['p(95)'].toFixed(2)} ms`),
    line('p99', `${m['p(99)'].toFixed(2)} ms`),
    line('max', `${m.max.toFixed(2)} ms`),
    line('error rate', `${(failed * 100).toFixed(3)} %`),
    ``,
    `These figures describe one machine, one JVM and a co-located database.`,
    `They are a regression signal, not evidence of production capacity.`,
    ``,
  ].join('\n');

  return { stdout: summary };
}
