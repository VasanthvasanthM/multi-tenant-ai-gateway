# B12 — Testing with k3d and k6

How the load campaign works, and why eight of its nine runs were thrown away.

---

## The cluster, on one laptop

```yaml
servers: 1
agents: 1
ports:
  - port: "8080:30080"
  - port: "8443:30443"
```

**k3d** runs k3s - a full Kubernetes distribution - inside Docker containers. One
server node, one agent node, both as containers on the same machine.

### Why ports 8080 and 8443 rather than 80 and 443

> *"Deliberately NOT 80:80."*

Binding port 80 needs privileges on some platforms and collides with whatever is
already listening on others. Mapping a high host port onto a node port gives a
working path on every platform, and MetalLB still hands out a real LoadBalancer
IP inside the cluster, so the Service type being tested is the one production
uses.

### Why one agent

> *"Raise it with `--agents 2` on a machine that has the room."*

The laptop has 7.4 GB with ~215 MB free. A second agent is another container,
another kubelet, another set of system pods. **The cluster shape is bounded by
the host, not by the design.**

---

## The load generator

Two scripts, one purpose each:

```
loadtest/k6/completions.js   a steady run against a fixed rate
loadtest/k6/capacity.js      a SATURATION ladder, used for the measurement
loadtest/scripts/capacity-sweep.sh   drives the ladder at 1..N pods and fits the USL
```

### Warm-up is a separate stage, not a flag

```javascript
warmup: {
  executor: 'constant-vus',
  vus: Math.max(1, Math.round(VUS / 4)),
  duration: WARMUP,
},
measure: {
  executor: 'constant-vus',
  vus: VUS,
  duration: DURATION,
}
```

> *"The measure-phase count over the measure duration is the real figure."*

A JVM's first thousand requests are interpreted bytecode, not JIT-compiled
machine code, and the cache is empty. Including them measures the warm-up, not
the service.

### The corpus — and the bug that made every rerun look better

The mix is exact repeats, paraphrases, and unique prompts. The unique ones have
to be **genuinely** unique, and getting that wrong invalidated several runs:

```javascript
/** A monotonically increasing suffix, so UNIQUE prompts really are unique. */
let uniqueCounter = 0;

// __VU id has to be part of the string or two VUs generate the same
// "unique" prompts and quietly create Tier-1 hits.
const vu = typeof __VU === 'number' ? __VU : 0;
prompt: `Explain requirement ${RUN_ID}-${vu}-${uniqueCounter} of the onboarding process.`
```

Three ingredients, each fixing a separate failure:

| part | without it |
|---|---|
| `RUN_ID` | *"the 'unique' prompts are a pure function of the VU id and a counter"* - so a second run finds them all cached and reports a better hit ratio |
| `__VU` | two virtual users generate the same "unique" prompts and **quietly create Tier-1 hits** |
| `uniqueCounter` | one VU repeats itself within a run |

> **Every one of those makes the system look better than it is.** A load test that cannot report a bad result is not a measurement.

### How the hit ratio is captured

```javascript
const tier = response.headers['X-Cache-Tier'] || '';
if (tier === 'tier1_exact') {
  servedByTier1.add(1);
  hitRate.add(true);
}
```

The gateway declares which tier answered; k6 tallies it. Not inferred.

---

## The gates — what makes a run invalid

This is the part worth copying into any load-testing setup. The sweep **discards**
a data point rather than reporting it:

```bash
HIT_RATIO_FLOOR="${HIT_RATIO_FLOOR:-0.62}"
```

```bash
if ! awk -v h="$step_hit_ratio" -v f="$HIT_RATIO_FLOOR" 'BEGIN { exit !(h >= f) }'; then
  warn "vus=$vus: hit ratio ${step_hit_ratio} below floor $HIT_RATIO_FLOOR \
- Tier 1 was failing, not serving"
```

A hit ratio below 0.62 against a designed 0.70 means **Tier 1 was failing, not
serving** - the Redis-timeout cascade. Reporting that point as throughput would
record a collapse as a capacity figure.

And an invalid point is treated as **absent**, never as zero:

> *"The caller treats an invalid point as absent rather than as a zero - a zero
> would be [fitted] by the fit to sigma and read as a property of the gateway."*

A zero would make the scalability model conclude the *software* contends, when
the truth was a broken test.

### The refusal that saves the most time

```bash
die "the gateway container has no CPU limit."
```

Without a CPU limit, a pod uses whatever the node has spare, so "throughput per
pod" means nothing - it is a measurement of the host's idle capacity. The sweep
refuses to run and prints the `helm upgrade` that fixes it.

---

## Measuring CPU — not with `kubectl top`

```bash
kubectl exec <pod> -- sh -c 'cat /sys/fs/cgroup/cpu.stat'
```

```
usage_usec      CPU microseconds consumed
nr_periods      scheduling periods elapsed
nr_throttled    periods in which the pod was throttled
```

Raw cumulative kernel counters, delta-ed across the measure window.

`kubectl top` was rejected for a measured reason: *"it reports a decaying average
on the metrics-server scrape cycle, so read after a run it samples the idle
period afterwards."* It said 51-76% for pods throttled in 46-72% of periods.

**`nr_throttled` is the number that matters** and `kubectl top` cannot show it at
all. A pod at "60% CPU" that is throttled in 92.9% of periods is not at 60% - it
is at its ceiling, being stopped constantly.

---

## The nine runs, and the eight that were discarded

| what went wrong | why it was invisible |
|---|---|
| The sweep could only report success - it offered a fixed rate and asked "did it keep up?" | once one pod sustains the rate every step reports the same number, and the model concludes perfect linear scaling from a test that never loaded anything |
| Every rerun looked better than the last | "unique" prompts were a function of VU id and a counter, both of which reset |
| The cache switched itself off under load | a 500 ms Redis timeout against a deep queue; each timeout became an upstream call, deepening the queue. Throughput collapsed instead of plateauing, and the only symptom was the hit ratio falling |
| The default API key was never configured | every request 401'd in under a millisecond, and the "excellent throughput" was the speed of saying no |
| The hardware wall was a config file I had written | `.wslconfig` capped the VM at 3 CPUs; six runs blamed the laptop |
| More cores made pods *slower* | a 400m limit on a 6-CPU node: the JVM saw 6 processors, made 6 event loops, and they shared an unchanged 40 ms quota - throttled in 96.9% of periods |
| `kubectl top` reported 51-76% for pods throttled in 46-72% of periods | a decaying average, sampled after the run |
| A run reported 65% twice, byte for byte | the config had never been patched - CRLF line endings against patterns ending in `\n` |

**Eight rejections, eight gates.** Every gate in the sweep script exists because
a run was once accepted that should not have been.

> The pattern across all eight: **not one of them produced an error.** Each
> produced a plausible number that happened to be measuring the wrong thing.

---

## What the ladder actually fits

Three points - 1, 2 and 3 pods - fitted to the Universal Scalability Law:

```
| Pods | X(N)       | p50    | p95      | Pod CPU | Throttled |
|    1 | 271.3 rps  | 198 ms | 476 ms   | 89.6%   | 92.9%     |
|    2 | 288.4 rps  | 187 ms | 402 ms   | 56.3%   | 61.0%     |
|    3 | 229.1 rps  | 310 ms | 1,154 ms | 54.5%   | 31.0%     |

Fitted: lambda 271.3, sigma 0.0913, kappa 0.395, N_max 1.5, X_max 303
```

**Throughput goes DOWN at three pods** - because three JVMs plus Redis plus
Postgres plus the ingress controller do not fit in a 6-CPU VM. The fit is
therefore measuring the laptop, not the software, past N=2.

Which is why only `lambda` is quoted as a result:

> *"`lambda >= 271 rps for a single 1000m pod` is a real measured figure - the
> first in this document. It is a LOWER BOUND."*

`sigma` and `kappa` are fitted to three points, two of which are
host-constrained. **They are not claims about the software.**

---

## How to run it

```bash
# bring the cluster up
k3d cluster create --config deploy/k3d/cluster.yaml
kubectl apply -f deploy/k8s/dev-infra.yaml
helm upgrade --install ai-gateway deploy/helm/ai-gateway -f deploy/helm/ai-gateway/values-dev.yaml

# one steady run
k6 run loadtest/k6/completions.js

# the saturation ladder with the USL fit
./loadtest/scripts/capacity-sweep.sh
```

The sweep will refuse to start if the gateway has no CPU limit, and will discard
any step whose hit ratio falls below the floor.

---

## The lesson, if you only keep one

**A load test that cannot fail is not a measurement.**

Eight of nine runs were wrong, and every single one of them returned a number
that looked fine. The gates - a hit-ratio floor, a CPU-limit check, treating an
invalid point as absent, a run-unique prompt id - are what turned the ninth into
evidence.

---

**Previous:** [B11 — start and stop](B11-start-and-stop.md).
**Next:** [B13 — challenges](B13-challenges.md).
