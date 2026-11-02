// Minimal Grafana k6 example: load test of Use Case 2 (synchronous price quote)
// through the gateway's REST API. Intentionally small - extend it for your experiments.
//
// Run (from the repository root, while the system is up):
//   docker compose --profile performance run --rm k6 run /scripts/example.js
//   docker compose --profile performance run --rm -e VUS=10 -e DURATION=60s k6 run /scripts/example.js

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: Number(__ENV.VUS || 5),             // concurrent virtual users (closed workload)
  duration: __ENV.DURATION || '30s',       // test duration
  thresholds: {
    http_req_failed: ['rate<0.01'],        // less than 1% failed requests
    http_req_duration: ['p(95)<1000'],     // 95% of requests faster than 1 s
  },
};

const baseUrl = __ENV.BASE_URL || 'http://gateway-service:8080';
const thinkTimeSeconds = Number(__ENV.THINK_TIME || 0); // pause between iterations of one VU

const quoteRequest = JSON.stringify({
  customerId: 'C-1001',
  items: [
    { sku: 'SKU-1001', quantity: 2 },
    { sku: 'SKU-4001', quantity: 1 },
  ],
});

export default function () {
  const response = http.post(`${baseUrl}/api/quotes`, quoteRequest, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(response, {
    'status is 200': (r) => r.status === 200,
    'quote has two lines': (r) => r.status === 200 && r.json('lines').length === 2,
  });

  if (thinkTimeSeconds > 0) {
    sleep(thinkTimeSeconds);
  }
}
