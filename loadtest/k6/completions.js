/**
 * One step of the pod sweep.
 *
 * Run by loadtest/scripts/sweep.sh once per pod count; it is not meant to be
 * run standalone except to check a setup:
 *
 *   k6 run -e RPS=500 -e GATEWAY=http://localhost:8080 \
 *     -e API_KEY=sk-local-dev-acme-00000000000000000000 \
 *     loadtest/k6/completions.js
 *
 * ------------------------------------------------------------------------
 * THE EXECUTOR CHOICE IS THE WHOLE MEASUREMENT
 * ------------------------------------------------------------------------
 * `constant-arrival-rate`, deliberately. NOT ramping-vus, and not
 * constant-vus.
 *
 * A VU-based test is a CLOSED loop: each virtual user sends a request, waits
 * for the response, then sends the next. When the system slows down, the test
 * slows down with it - offered load falls exactly when the system is struggling,
 * so saturation is never observed. The throughput curve then flattens smoothly
 * and looks like a well-behaved system approaching its limit, when what it
 * actually shows is the load generator backing off. That is coordinated
 * omission, and it is the single most common reason a load test reports a
 * system as healthier than it is.
 *
 * An OPEN loop keeps arriving at the configured rate regardless. When the
 * system cannot keep up, requests queue, latency climbs and k6 reports dropped
 * iterations - which is the honest signal, and the one the USL fit needs.
 * `dropped_iterations` is therefore a threshold below: a step that could not
 * sustain its offered rate is not a valid data point.
 */

import { check } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

import { nextPrompt, warmupPrompts } from './lib/corpus.js';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
// A key that gateway.auth.bootstrap-keys actually contains. A wrong default
// does not fail loudly here: the run completes, every response is a fast 401,
// and the throughput number looks excellent.
const API_KEY = __ENV.API_KEY || 'sk-local-dev-acme-00000000000000000000';
const HOST_HEADER = __ENV.HOST_HEADER || '';
const RPS = Number(__ENV.RPS || 200);
const DURATION = __ENV.DURATION || '2m';

/**
 * Discarded from the result.
 *
 * A JVM needs ~16 s to start and then JIT-compiles the hot path over the next
 * several thousand requests; the R2DBC and Redis pools fill on first use. A
 * measurement including that window reports a system slower than it is, and
 * reports it DIFFERENTLY at each step - the 6-pod step has six JVMs warming at
 * once.
 */
const WARMUP = __ENV.WARMUP || '45s';

/** The traffic mix. Identical at every step, or the steps are not comparable. */
const EXACT_PERCENT = Number(__ENV.EXACT_PERCENT || 0.55);
const PARAPHRASE_PERCENT = Number(__ENV.PARAPHRASE_PERCENT || 0.15);
// Remainder is unique -> upstream. 0.55 + 0.15 targets a ~70% hit ratio, which
// is the figure docs/architecture/scaling-model.md derives its economics from.

const MODEL = __ENV.MODEL || 'gpt-4o-mini';

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------
const servedByTier1 = new Counter('gateway_served_tier1');
const servedByTier2 = new Counter('gateway_served_tier2');
const servedByUpstream = new Counter('gateway_served_upstream');
const hitRate = new Rate('gateway_cache_hit_rate');

/** Latency split by tier, which the aggregate p99 hides completely. */
const tier1Latency = new Trend('gateway_tier1_latency', true);
const tier2Latency = new Trend('gateway_tier2_latency', true);
const upstreamLatency = new Trend('gateway_upstream_latency', true);

const rateLimited = new Counter('gateway_rate_limited');
const serverErrors = new Counter('gateway_server_errors');

export const options = {
  scenarios: {
    // Warm-up runs first and its metrics are tagged so they can be excluded.
    warmup: {
      executor: 'constant-arrival-rate',
      rate: Math.max(10, Math.round(RPS / 4)),
      timeUnit: '1s',
      duration: WARMUP,
      preAllocatedVUs: Math.max(10, Math.round(RPS / 10)),
      maxVUs: Math.max(50, RPS),
      exec: 'warmupRequest',
      tags: { phase: 'warmup' },
    },

    measure: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      startTime: WARMUP,

      // preAllocatedVUs has to be large enough that k6 is never the bottleneck.
      // If it runs out of VUs it drops iterations for its OWN reason, which is
      // indistinguishable in the output from the gateway being unable to keep
      // up - and would be read as saturation that is not there.
      preAllocatedVUs: Math.max(50, RPS),
      maxVUs: Math.max(200, RPS * 4),

      exec: 'measuredRequest',
      tags: { phase: 'measure' },
    },
  },

  thresholds: {
    // A step that dropped iterations could not sustain its offered rate, so it
    // is not a valid point for the fit. Failing the threshold makes the sweep
    // script discard it rather than fitting a number that means something else.
    dropped_iterations: ['count < 1'],

    // 429s mean the rate limiter is shaping the load, so the step measures the
    // limiter rather than the gateway. Raise the tenant's quota before a sweep.
    'gateway_rate_limited{phase:measure}': ['count < 1'],

    'http_req_failed{phase:measure}': ['rate < 0.01'],
  },

  // No summary trend for the warmup phase; it would be averaged into the
  // reported percentiles.
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

function headers() {
  const h = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${API_KEY}`,
  };
  // Set when reaching the gateway through ingress-nginx on a mapped host port,
  // where the Host header is what selects the Ingress rule.
  if (HOST_HEADER !== '') {
    h.Host = HOST_HEADER;
  }
  return h;
}

function body(prompt) {
  return JSON.stringify({
    model: MODEL,
    messages: [{ role: 'user', content: prompt }],
    // temperature omitted: the gateway defaults it to 0, which is what makes a
    // response cacheable at all. Sending a non-zero value here would opt every
    // request out of Tier 2 and the hit ratio would collapse to zero.
    max_tokens: 256,
  });
}

/** Fills the cache so step 1 is not measured against a cold one. */
export function warmupRequest() {
  const prompts = warmupPrompts();
  const prompt = prompts[Math.floor(Math.random() * prompts.length)];
  http.post(`${GATEWAY}/v1/chat/completions`, body(prompt), {
    headers: headers(),
    tags: { phase: 'warmup' },
  });
}

export function measuredRequest() {
  const { prompt } = nextPrompt(EXACT_PERCENT, PARAPHRASE_PERCENT);

  const response = http.post(`${GATEWAY}/v1/chat/completions`, body(prompt), {
    headers: headers(),
    tags: { phase: 'measure' },
  });

  if (response.status === 429) {
    rateLimited.add(1);
    return;
  }
  if (response.status >= 500) {
    serverErrors.add(1);
    return;
  }

  check(response, { 'status is 200': (r) => r.status === 200 });

  // X-Cache-Tier rather than parsing the body: it is a documented response
  // header (see docs/api/openapi.yaml) and reading it avoids JSON-parsing every
  // response inside the load generator, which at a few thousand RPS is enough
  // CPU to make k6 the bottleneck.
  const tier = response.headers['X-Cache-Tier'] || '';

  if (tier === 'tier1_exact') {
    servedByTier1.add(1);
    tier1Latency.add(response.timings.duration);
    hitRate.add(true);
  } else if (tier === 'tier2_semantic') {
    servedByTier2.add(1);
    tier2Latency.add(response.timings.duration);
    hitRate.add(true);
  } else {
    servedByUpstream.add(1);
    upstreamLatency.add(response.timings.duration);
    hitRate.add(false);
  }
}

/**
 * Writes one machine-readable line for the sweep script to collect.
 *
 * Emitted to stdout with a marker prefix rather than written to a file, so the
 * sweep script can capture it without the two agreeing on a path, and so a
 * human watching the run sees the same numbers.
 */
export function handleSummary(data) {
  const metric = (name, field) => {
    const m = data.metrics[name];
    if (m === undefined || m.values === undefined) {
      return 0;
    }
    return m.values[field] === undefined ? 0 : m.values[field];
  };

  const rps = metric('http_reqs', 'rate');
  const line = [
    `rps=${rps.toFixed(2)}`,
    `p50=${metric('http_req_duration', 'med').toFixed(2)}`,
    `p95=${metric('http_req_duration', 'p(95)').toFixed(2)}`,
    `p99=${metric('http_req_duration', 'p(99)').toFixed(2)}`,
    `hit_ratio=${metric('gateway_cache_hit_rate', 'rate').toFixed(4)}`,
    `tier1=${metric('gateway_served_tier1', 'count')}`,
    `tier2=${metric('gateway_served_tier2', 'count')}`,
    `upstream=${metric('gateway_served_upstream', 'count')}`,
    `dropped=${metric('dropped_iterations', 'count')}`,
    `rate_limited=${metric('gateway_rate_limited', 'count')}`,
    `errors=${metric('gateway_server_errors', 'count')}`,
  ].join(' ');

  return {
    stdout: `\nSWEEP_RESULT ${line}\n\n`,
    'summary.json': JSON.stringify(data, null, 2),
  };
}
