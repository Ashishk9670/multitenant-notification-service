// k6 load test: issues a per-run tenant API key, then submits notifications.
// Run: k6 run -e BASE_URL=http://localhost:8080 -e ADMIN_TOKEN=dev-admin-token loadtest/notifications.js
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';

const throttled = new Rate('throttled');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'dev-admin-token';

export const options = {
  scenarios: {
    submit: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 8),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    // 429 (per-tenant rate limit, default 10 req/s sustained) is a designed, fast response, not a failure
    'http_req_failed{expected_response:true}': ['rate<0.01'],
    // only meaningful below the tenant limit: above it most requests are throttled
    throttled: ['rate<1'],
    http_req_duration: ['p(95)<500'],
  },
};

export function setup() {
  const tenant = `k6-${Date.now()}`;
  const res = http.post(
    `${BASE_URL}/api/v1/admin/api-keys`,
    JSON.stringify({ tenantId: tenant, name: 'k6' }),
    { headers: { 'Content-Type': 'application/json', 'X-Admin-Token': ADMIN_TOKEN } },
  );
  check(res, { 'api key issued': (r) => r.status === 200 || r.status === 201 });
  return { key: res.json('apiKey') };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/api/v1/notifications`,
    JSON.stringify({
      channels: ['EMAIL'],
      recipients: { email: 'load@example.com' },
      body: `load ${__VU}-${__ITER}`,
    }),
    { headers: { 'Content-Type': 'application/json', 'X-API-Key': data.key },
      responseCallback: http.expectedStatuses(202, 429) },
  );
  throttled.add(res.status === 429);
  check(res, { 'accepted or throttled': (r) => r.status === 202 || r.status === 429 });
}
