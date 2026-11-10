/**
 * Saturation capacity at a fixed pod count - the measurement the USL fit needs.
 *
 * ---------------------------------------------------------------------------
 * WHY THIS EXISTS ALONGSIDE completions.js
 * ---------------------------------------------------------------------------
 *
 * completions.js is an SLO test. It offers a FIXED arrival rate and asks "did
 * the system hold it?", discarding any step that could not. That is the right
 * shape for "do 4 pods serve 300 rps within p95 budget".
 *
 * It is the wrong shape for a scaling curve. The USL needs X(N), the MOST that
 * N pods can do, and a fixed offered rate cannot measure that: if one pod can
 * already sustain the rate, every step reports the same achieved throughput and
 * the fit sees no scaling at all - it returns a straight line with sigma and
 * kappa at zero, which reads as perfect linear scalability. The most optimistic
 * possible answer is produced by a sweep that never loaded the system.
 *
 * So this script is CLOSED-loop. A fixed number of VUs each send one request,
 * wait for the response, and immediately send the next. Throughput is then an
 * output rather than an input: it settles at whatever the system can actually
 * retire, which is the definition of X(N).
 *
 * Closed loop rather than an arrival rate above capacity, because an open loop
 * driven past saturation queues without bound - latency climbs until requests
 * time out, and the throughput that comes back describes the timeout, not the
 * service. A closed loop applies backpressure to itself, which is also how a
 * real client with a bounded connection pool behaves.
 *
 *   k6 run -e VUS=50 -e DURATION=60s -e GATEWAY=http://localhost:18080 \
 *     -e API_KEY=sk-local-dev-acme-00000000000000000000 \
 *     loadtest/k6/capacity.js
 *
 * The corpus and the exact/paraphrase mix are imported from the same module
 * completions.js uses, so the two scripts drive identical traffic and the hit
 * ratio is comparable between them.
 */

import { check } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

import { nextPrompt, warmupPrompts } from './lib/corpus.js';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'sk-local-dev-acme-00000000000000000000';
const HOST_HEADER = __ENV.HOST_HEADER || '';
const MODEL = __ENV.MODEL || 'gpt-4o-mini';

/**
 * Concurrent in-flight requests. This is the load, and it must be the SAME at
 * every step of a sweep: changing it between steps changes the operating point,
 * and the resulting curve mixes "more pods" with "more offered concurrency".
 *
 * It has to be high enough to keep the largest step busy - otherwise the top of
 * the curve measures the generator being idle and the fit reads that as the
 * system refusing to scale - and low enough that queueing does not dominate.
 */
const VUS = Number(__ENV.VUS || 50);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '30s';

const EXACT_PERCENT = Number(__ENV.EXACT_PERCENT || 0.55);
const PARAPHRASE_PERCENT = Number(__ENV.PARAPHRASE_PERCENT || 0.15);

const servedByTier1 = new Counter('gateway_served_tier1');
const servedByTier2 = new Counter('gateway_served_tier2');
const servedByUpstream = new Counter('gateway_served_upstream');
const hitRate = new Rate('gateway_cache_hit_rate');
const rateLimited = new Counter('gateway_rate_limited');
const serverErrors = new Counter('gateway_server_errors');
const inFlightLatency = new Trend('gateway_latency', true);

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-vus',
      vus: Math.max(1, Math.round(VUS / 4)),
      duration: WARMUP,
      exec: 'warmupRequest',
      tags: { phase: 'warmup' },
    },
    measure: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
      startTime: WARMUP,
      exec: 'measuredRequest',
      tags: { phase: 'measure' },
    },
  },

  thresholds: {
    // Both of these invalidate the point rather than merely degrading it, so
    // they are thresholds: a 429 means the limiter set the throughput, and a
    // 5xx means part of the load was answered by an error path that is far
    // cheaper than serving the request. Either way the number that comes back
    // is not the capacity of the gateway.
    'gateway_rate_limited{phase:measure}': ['count < 1'],
    'http_req_failed{phase:measure}': ['rate < 0.01'],
  },

  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],

  // Connections are reused. Without this k6 opens a fresh connection per
  // iteration and the measurement includes a TCP handshake that no real client
  // pays per request - at saturation that handshake is a large part of the CPU.
  noConnectionReuse: false,
  discardResponseBodies: false,
};

function headers() {
  const h = {
    Authorization: 'Bearer ' + API_KEY,
    'Content-Type': 'application/json',
  };
  if (HOST_HEADER) {
    h.Host = HOST_HEADER;
  }
  return h;
}

function body(prompt) {
  return JSON.stringify({
    model: MODEL,
    messages: [{ role: 'user', content: prompt }],
  });
}

export function warmupRequest() {
  const prompts = warmupPrompts();
  const prompt = prompts[Math.floor(Math.random() * prompts.length)];
  http.post(GATEWAY + '/v1/chat/completions', body(prompt), {
    headers: headers(),
    tags: { phase: 'warmup' },
  });
}

export function measuredRequest() {
  const { prompt } = nextPrompt(EXACT_PERCENT, PARAPHRASE_PERCENT);

  const response = http.post(GATEWAY + '/v1/chat/completions', body(prompt), {
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
  inFlightLatency.add(response.timings.duration);

  const tier = response.headers['X-Cache-Tier'] || '';
  if (tier === 'tier1_exact') {
    servedByTier1.add(1);
    hitRate.add(true);
  } else if (tier === 'tier2_semantic') {
    servedByTier2.add(1);
    hitRate.add(true);
  } else {
    servedByUpstream.add(1);
    hitRate.add(false);
  }
}

export function handleSummary(data) {
  const metric = (name, field) => {
    const m = data.metrics[name];
    if (m === undefined || m.values === undefined) {
      return 0;
    }
    return m.values[field] === undefined ? 0 : m.values[field];
  };

  // Not http_reqs.rate: that is the total count over the WHOLE run, warmup
  // included, so it reports a throughput no phase actually achieved. The
  // measure-phase count over the measure duration is the real figure.
  // Summed from the tier counters, NOT from the latency Trend: a k6 Trend
  // exposes avg/min/med/max/percentiles and no count at all, so reading
  // 'count' off it yields 0 - and a throughput of 0.00 printed next to a
  // perfectly healthy run of 1,487 requests.
  const measured = metric('gateway_served_tier1', 'count')
    + metric('gateway_served_tier2', 'count')
    + metric('gateway_served_upstream', 'count');
  const seconds = durationSeconds(DURATION);
  const throughput = seconds > 0 ? measured / seconds : 0;

  const line = [
    'vus=' + VUS,
    'throughput=' + throughput.toFixed(2),
    'completed=' + measured,
    'seconds=' + seconds,
    'p50=' + metric('gateway_latency', 'med').toFixed(2),
    'p95=' + metric('gateway_latency', 'p(95)').toFixed(2),
    'p99=' + metric('gateway_latency', 'p(99)').toFixed(2),
    'hit_ratio=' + metric('gateway_cache_hit_rate', 'rate').toFixed(4),
    'tier1=' + metric('gateway_served_tier1', 'count'),
    'tier2=' + metric('gateway_served_tier2', 'count'),
    'upstream=' + metric('gateway_served_upstream', 'count'),
    'rate_limited=' + metric('gateway_rate_limited', 'count'),
    'errors=' + metric('gateway_server_errors', 'count'),
  ].join(' ');

  return {
    stdout: '\nCAPACITY_RESULT ' + line + '\n\n',
    'summary.json': JSON.stringify(data, null, 2),
  };
}

function durationSeconds(value) {
  const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(String(value).trim());
  if (match === null) {
    return 0;
  }
  const n = Number(match[1]);
  switch (match[2]) {
    case 'ms': return n / 1000;
    case 's': return n;
    case 'm': return n * 60;
    default: return n * 3600;
  }
}
