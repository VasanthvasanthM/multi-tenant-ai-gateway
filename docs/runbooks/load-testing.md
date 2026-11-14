# Load testing with k3d and k6

How to run the capacity campaign, read the result, and — more importantly — tell
a real measurement from a number that merely looks like one.

Nine sweeps were run on a laptop for this project. **Eight were rejected.** Every
rejection had a cause worth knowing, and most of this file is those causes.

---

## What the two tools are for

**k3d** runs a real Kubernetes cluster (k3s) inside Docker containers. It is the
system under test: real pods, a real ingress, real service networking. Not a
simulation — the same manifests deploy to a managed cluster unchanged.

**k6** is the load generator. It runs **on the host, not in the cluster**, so it
does not compete with the pods it is measuring. That separation is step 1 of the
measurement protocol and it is not optional: a generator short of CPU produces a
curve that bends, and a bending curve is indistinguishable from the system
saturating.

### Installing

```bash
winget install k3d.k3d          # NOT k3d-io.k3d - that id does not exist
```

k6 is **not** in winget under `k6.k6`. Download the Windows zip from
<https://github.com/grafana/k6/releases> and extract to
`C:\Users\<you>\AppData\Local\Programs\k6`, then:

```bash
export PATH="/c/Users/vasan/AppData/Local/Programs/k6:$PATH"
export PATH="/c/Users/vasan/AppData/Local/Microsoft/WinGet/Packages/k3d.k3d_Microsoft.Winget.Source_8wekyb3d8bbwe:$PATH"
k6 version && k3d version
```

---

## Before measuring anything

### 1. Give the VM enough CPU — and leave some for k6

`C:\Users\<you>\.wslconfig`:

```ini
[wsl2]
memory=4GB
processors=6
swap=4GB
```

Then `wsl --shutdown` and restart Docker Desktop.

On an 8-logical-CPU host this leaves 2 for k6. **This file was once set to
`processors=3`, and six sweeps then blamed "the laptop" for a limit that was a
config choice.** Check it before concluding anything about hardware.

### 2. Give each pod a CPU limit — sized to the node

```bash
kubectl set resources deploy/aigw-ai-gateway-gateway \
  --limits=cpu=1000m,memory=384Mi --requests=cpu=400m,memory=384Mi
```

Without a limit, one pod expands to the whole node, extra pods add nothing, and
the fit reports the node's core count as the architecture's ceiling.

**A limit is only meaningful relative to its node.** At 6 VM CPUs with a 400m
limit, the JVM saw 6 processors, created ~6 event loops, and they shared an
unchanged 40 ms-per-100 ms quota: throttled in 96.9% of periods, p50 177 ms →
704 ms. *More cores made the pod slower.*

### 3. Raise the rate limiter out of the way

```bash
--set services.gateway.env.GATEWAY_RATELIMIT_CAPACITY=2000000 \
--set services.gateway.env.GATEWAY_RATELIMIT_REFILLPERSECOND=1000000
```

The default sustains **20 rps**. Any sweep above that measures the limiter.

### 4. Check the path is healthy

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: ai-gateway.local' \
  http://localhost:18080/actuator/health
```

Must be `200`. An `ingress-nginx` that has restarted many times will forward
requests and time out upstream, returning 503 while the gateway is perfectly
healthy — a sick ingress is indistinguishable from a slow gateway, and it
corrupted one run here.

---

## Running a sweep

```bash
kubectl exec deploy/redis -- redis-cli FLUSHALL

MAX_PODS=3 DURATION=45s WARMUP=25s SETTLE_SECONDS=30 PREWARM=60s \
GATEWAY=http://localhost:18080 ./loadtest/scripts/capacity-sweep.sh
```

What it does at each pod count: scales the Deployment, waits for Ready **and**
settles (Ready means a probe passed, not that the JIT is warm), then walks a
**concurrency ladder** and keeps the best valid point.

The ladder matters. X(N) is the *most* N pods can do, which is a peak over
concurrency — a single fixed VU count cannot find it at every step. One pod here
measured **210 rps at 32 VUs and 55 rps at 90**: the same pod twice, because
past a point the Redis timeout cascade sets in (below).

A single run of one step, for a quick check:

```bash
k6 run --quiet -e VUS=64 -e DURATION=45s -e WARMUP=25s \
  -e RUN_ID="probe-$(date +%s)" \
  -e GATEWAY=http://localhost:18080 -e HOST_HEADER=ai-gateway.local \
  loadtest/k6/capacity.js
```

`RUN_ID` is **not** cosmetic — see "unique prompts" below.

### Two scripts, two questions

| Script | Asks | Shape |
|---|---|---|
| `capacity.js` | *how much can N pods do?* | closed loop, fixed VUs, throughput is an **output** |
| `completions.js` | *did N pods hold 300 rps within p95 budget?* | open loop, fixed arrival rate — an SLO test |

Using the SLO script for capacity is the first mistake this project made: once
one pod sustains the offered rate, every step reports the same number and the
fit returns σ = κ = 0. **Perfect linear scalability is the conclusion a sweep
reaches when it never loaded the system.**

---

## Reading the result

```bash
python loadtest/analysis/fit.py loadtest/results/capacity-<timestamp>.csv
```

The fitter prints **"Reasons to distrust this fit" first, on purpose**, and
exits non-zero if any apply. It refused eight of nine runs here. Treat a refusal
as the finding, not an obstacle.

| Symbol | Means |
|---|---|
| λ | throughput of one pod |
| σ | contention — serialised work |
| κ | coherency — the cost pods impose on each other |
| N_max | √((1−σ)/κ) — where throughput peaks |

**κ is the number that matters.** Near zero means no crosstalk term and the
fleet scales linearly until something else binds. Large κ falsifies the design.

Things that make a fit meaningless even when it prints:

- **σ > 1** is outside the model's domain. It does not mean heavy contention; it
  means *no curve of this family fits*, which is what a throughput that falls
  from N=1 forces on the optimiser.
- **3 points for 3 parameters** is determined, not tested — R² is 1.0 by
  construction. Four points minimum before R² means anything.
- **A step that peaked at the top ladder rung** is a lower bound, not X(N).

---

## What invalidates a measurement

The sweep discards a point rather than reporting it when any of these hold. Each
exists because it happened:

### The cache switches itself off

`spring.data.redis.timeout` is 500 ms. Drive the queue past that and Tier-1
lookups time out, **each timeout becomes an upstream call**, the extra work
deepens the queue, and more lookups time out. Throughput collapses rather than
plateauing — 347 rps at 64 VUs, 55 rps at 90.

The only visible symptom is the **hit ratio falling**. Hence the gate: a point
whose hit ratio is below `HIT_RATIO_FLOOR` (0.62 against a designed 0.70) is
rejected, because Tier 1 was failing rather than serving.

### Every rerun looks better than the last

The corpus's "unique" prompts were a function of the VU id and a per-VU counter,
both of which restart every run. A second run against the same Redis regenerates
byte-identical prompts that are all already cached — a designed 30% upstream
share becomes a 30% cache hit. In a pod sweep each step is a separate run, so
the hit ratio climbed monotonically and the fit described a filling cache.

Fixed with a per-run nonce, which is what `RUN_ID` is for.

### The generator becomes the bottleneck

At 3 pods: 155 rps at 48 VUs, 229 at 96, **159 at 192** — throughput falling as
offered concurrency rises, while the pods sat at 54.5% CPU, throttled in only
31% of periods. A system under test that is half idle cannot be what slows down
when you offer it more load.

Watch the pod CPU column. If the top step is not near its limit, the number is
about the generator.

### Measuring CPU wrongly

`kubectl top` reports a decaying average on metrics-server's own scrape cycle.
Read after a run, it samples the *idle period afterwards* — it reported 51–76%
for pods that cgroup counters showed were throttled in 46–72% of periods. The
sweep reads `usage_usec` and `nr_throttled` from the container's `cpu.stat`
across the measured window instead.

### Nothing changed and the number is identical

A run reported 65% twice, byte for byte. The config had never been patched — the
file has CRLF endings and the patterns being applied ended in `\n`. **An
unchanged number after a change is a result, not a near miss.**

---

## Honest reporting

What the campaign produced: **λ ≥ 271.3 rps** for one 1000m pod at a 0.696 hit
ratio. `>=` because that step peaked at its top ladder rung.

What it did not: σ and κ. The fleet arithmetic in
[`scaling-model.md` §4b](../architecture/scaling-model.md) therefore says
"capacity plan conditional on linear scaling", not "proven capacity".

If you take one thing from this file: **a capacity number without the conditions
under which it was measured is decoration.** The gates above exist so the
tooling refuses to produce decoration, and it refused eight times out of nine.
