// Supplied benchmark: compare implementations at the same input, rule count and client count.
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Gauge, Trend } from 'k6/metrics';

const target = __ENV.TARGET || 'component';
const implementation = __ENV.IMPL || 'baseline';
const ruleCount = Number(__ENV.RULE_COUNT || 100000);
const vus = Number(__ENV.VUS || 1);
const seconds = Number(__ENV.SECONDS || 30);
const warmup = Number(__ENV.WARMUP || 0);
const run = Number(__ENV.RUN || 1);
const runs = Number(__ENV.RUNS || 1);
if (!['component', 'end-to-end'].includes(target) || !['baseline', 'optimized'].includes(implementation) ||
    !Number.isInteger(ruleCount) || ruleCount < 1 || ruleCount > 1000000 ||
    !Number.isInteger(vus) || vus < 1 || vus > 128 ||
    !Number.isInteger(seconds) || seconds < 1 || seconds > 300 ||
    !Number.isInteger(warmup) || warmup < 0 || warmup > 60 ||
    !Number.isInteger(run) || !Number.isInteger(runs) || run < 1 || run > runs || runs > 20) {
  throw new Error('Use TARGET=component|end-to-end, IMPL=baseline|optimized, RULE_COUNT=1..1000000, VUS=1..128, SECONDS=1..300, WARMUP=0..60, RUN=1..RUNS, RUNS=1..20');
}
export const options = {
  scenarios: { experiment: { executor: 'constant-vus', vus, duration: `${seconds + warmup}s`, gracefulStop: '10s' } },
  maxRedirects: 0,
};
const latency = new Trend('operation_ms', true);
const elapsedRun = new Gauge('experiment_elapsed_ms');
const base = __ENV.BASE_URL || (target === 'component' ? 'http://127.0.0.1:8083' : 'http://127.0.0.1:8080');
const riskBase = __ENV.RISK_BASE_URL || (target === 'component' ? base : 'http://127.0.0.1:8083');
const url = base + (target === 'component' ? '/risk-evaluations' : '/api/risk-previews');
const input = {
  items: [{ sku: 'SKU-1001', category: 'BOOKS', quantity: 2, unitPrice: 12.90 },
    { sku: 'SKU-4002', category: 'ELECTRONICS', quantity: 1, unitPrice: 699.00 }],
  shippingAddress: 'Main Street 1, Berlin', shippingCountry: 'DE',
  // Deterministic pricing profile for C-2026; also used by RiskScorerBenchmark.setUp().
  customer: { accountAgeDays: 316, previousOrders: 22, chargebacks: 0, averageOrderValue: 377.00, homeCountry: 'SE' },
};
const payload = JSON.stringify(target === 'component' ? { input, ruleCount, implementation } : {
  customerId: 'C-2026', shippingAddress: input.shippingAddress, shippingCountry: input.shippingCountry,
  items: input.items.map(item => ({ sku: item.sku, quantity: item.quantity })), ruleCount, implementation,
});
const tags = {
  target, implementation, run: String(run), runs: String(runs), rule_count: String(ruleCount), input_id: 'risk-rules-v1',
  operation: target === 'component' ? 'risk-evaluation' : 'risk-preview', timing: 'http-duration-ms',
  vus: String(vus), seconds: String(seconds), warmup: String(warmup),
};

function correct(response, expected) {
  if (response.status !== 200) return false;
  try {
    const body = response.json();
    const evaluation = target === 'component' ? body : body.evaluation;
    const risk = evaluation.risk;
    if (evaluation.implementation !== implementation || evaluation.ruleCount !== ruleCount ||
        risk.score !== expected.score || risk.category !== expected.category ||
        Number(risk.totalAmount) !== Number(expected.totalAmount)) return false;
    if (target === 'component') return true;
    const quote = body.quote;
    return quote.lines.length === 2 && Number(quote.subtotal) === 724.8 && Number(quote.total) === 724.8 &&
      Number(quote.discount) === 0 && quote.customerHistory.orderCount === 0;
  } catch (_) { return false; }
}

export function setup() {
  // Reference check is outside operation_ms samples. It is not a response cache.
  const response = http.post(riskBase + '/risk-evaluations', JSON.stringify({ input, ruleCount, implementation: 'baseline' }), {
    headers: { 'Content-Type': 'application/json' }, timeout: '5s', redirects: 0,
  });
  if (response.status !== 200) throw new Error('Baseline risk reference failed; run lab-ready.');
  const expected = response.json().risk;
  if (!Number.isFinite(expected.score) || expected.score < 0 || expected.score > 100 ||
      !['LOW', 'MEDIUM', 'HIGH'].includes(expected.category) || Number(expected.totalAmount) !== 724.8) {
    throw new Error('Invalid baseline risk reference.');
  }
  return { startedAt: Date.now(), expected };
}

export default function (data) {
  const elapsed = Date.now() - exec.scenario.startTime;
  if (elapsed >= (warmup + seconds) * 1000) return;
  const phase = elapsed < warmup * 1000 ? 'warmup' : 'measurement';
  const response = http.post(url, payload, {
    headers: { 'Content-Type': 'application/json' }, timeout: '5s', redirects: 0,
  });
  const success = correct(response, data.expected);
  check(response, { 'valid risk result matches baseline': () => success });
  latency.add(response.timings.duration, { ...tags, elapsed_start_ms: String(elapsed), phase, success: String(success), status: String(response.status) });
}

export function teardown(data) {
  elapsedRun.add(Date.now() - data.startedAt, tags);
}
