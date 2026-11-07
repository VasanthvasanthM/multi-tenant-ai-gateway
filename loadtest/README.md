# Load testing (P12)

> ## The campaign has not been run.
>
> Everything here is written and the USL fitter is verified, but **no
> measurement has been taken** — k6 is not installed on the development machine
> and the cluster needs ~2 GB against the ~460 MB available. So every throughput
> figure in [`../docs/architecture/scaling-model.md`](../docs/architecture/scaling-model.md)
> is still labelled **(C) calculated**, and nothing here changes that.
>
> What *is* done: running it is one command once a cluster exists.

```bash
./scripts/cluster-up.sh                       # k3d + MetalLB + ingress-nginx
./scripts/observability-up.sh                 # Prometheus, for the HPA metric
helm upgrade --install aigw deploy/helm/ai-gateway \
  -f deploy/helm/ai-gateway/values-scale.yaml
kubectl delete hpa aigw-ai-gateway-gateway    # a sweep sets replicas itself

./loadtest/scripts/sweep.sh
```

## What gets measured

| File | What it is |
|---|---|
| [`k6/completions.js`](k6/completions.js) | One step: constant arrival rate against `/v1/chat/completions` |
| [`k6/lib/corpus.js`](k6/lib/corpus.js) | The prompt corpus, with a declared repeat structure |
| [`scripts/sweep.sh`](scripts/sweep.sh) | Scales 1→6 pods, runs a step at each, writes a CSV |
| [`analysis/usl.py`](analysis/usl.py) | The USL fit |
| [`analysis/fit.py`](analysis/fit.py) | CSV → markdown report |
| [`analysis/test_usl.py`](analysis/test_usl.py) | 13 tests, including exact coefficient recovery |

## Four things this is careful about

Each of these is a way to produce a confident wrong answer, and all four are
easy to get wrong by accident.

### 1. An open loop, not a closed one

`constant-arrival-rate`, never `ramping-vus`.

A VU-based test is a **closed loop**: each virtual user waits for its response
before sending the next request. When the system slows, the test slows with it
— offered load falls exactly when the system is struggling, so **saturation is
never observed**. The curve flattens smoothly and looks like a well-behaved
system approaching its limit, when it actually shows the generator backing off.
That is coordinated omission, and it is the most common reason a load test
reports a system as healthier than it is.

An open loop keeps arriving regardless, so requests queue, latency climbs and
k6 reports dropped iterations. `dropped_iterations` is a threshold, and a step
that drops any is **excluded from the fit** rather than recorded — an
unsustained step measures the generator's queue, and the fit cannot tell the
difference.

### 2. The hit ratio is an input, not an accident

The corpus declares its mix: 55% exact repeats, 15% paraphrases, 30% unique,
targeting the ~70% hit ratio the economics in `scaling-model.md` assume.

This matters more here than in most systems, because which tier answers *is*
the latency: ~4 ms for Tier 1 against 200 ms–30 s upstream. A test firing random
strings measures the upstream path; one firing a single repeated string measures
Redis. Neither describes the gateway.

The sweep pre-warms **once, before step 1**. Without that, step 1 runs against a
cold cache and step 6 against a warm one, the hit ratio climbs across the sweep,
and the fit describes a cache filling up rather than a system scaling. `fit.py`
warns when the ratio moved more than 5 percentage points across the steps, which
means the steps were not comparable.

### 3. k6 never shares cores with the gateway

Pinned with `taskset -c 6-7`, or `--cpuset-cpus` when running the container.

Without it the load generator competes with the thing it is measuring, and the
curve bends because **the generator** ran out of CPU — which looks identical to
the gateway saturating. On a 4-core laptop running the cluster, Redis and
PostgreSQL as well, this is not a theoretical risk.

`preAllocatedVUs` is also sized generously, because k6 running out of VUs drops
iterations for its own reason, and that is indistinguishable in the output from
the gateway being unable to keep up.

### 4. The HPA is deleted for the duration

The sweep sets the replica count explicitly. An HPA would move it back between
steps, so each step would be measured at a pod count that is not the one
recorded. `sweep.sh` refuses to start if one exists.

The HPA's *own* behaviour is a separate and worthwhile measurement — but it
needs the custom metric actually being served. Check `kubectl get hpa` shows a
number rather than `<unknown>` first; an HPA whose metric is unavailable falls
back to CPU **without erroring**, and an I/O-bound gateway on a CPU target
produces a flat replica count that reads as evidence it does not need to scale.

## The fit

$$X(N) = \frac{\lambda N}{1 + \sigma(N-1) + \kappa N(N-1)}$$

The model is nonlinear in its parameters but becomes linear after one
rearrangement — with $y = N/X(N)$:

$$y = \frac{1}{\lambda} + \frac{\sigma}{\lambda}(N-1) + \frac{\kappa}{\lambda}N(N-1)$$

which is ordinary least squares against three basis functions. One
`numpy.linalg.lstsq` call: deterministic, no initial guess, and no scipy
dependency.

**The caveat is reported, not hidden.** Minimising residuals in $N/X$ is not the
same as minimising them in $X$; the transform weights low-$N$ points more
heavily. So `usl.fit()` returns **both** $R^2$ values — the linearised one that
was actually minimised, and the one on throughput itself. The report prints both
and the prose quotes the second.

### Verified, not just written

```bash
python loadtest/analysis/test_usl.py
```

13 tests. The one that matters generates throughput from coefficients we chose
and asserts the fitter returns them: λ=1200, σ=0.035, κ=0.00012 recovered to
within 1e-6, across three different parameter shapes. A fitting routine that
cannot do that cannot be trusted with real data, and plausible-looking output on
real data would never reveal it.

The others cover the honest-reporting cases:

- **κ ≈ 0 reports `None`, not a huge number.** "No peak within the model" is a
  materially different finding from "the peak is far away", and rendering it as
  a number invites someone to quote a ceiling the data does not support.
- **Negative coefficients are clamped to zero.** Noise can fit a negative σ, and
  negative contention would mean the system gets *more* efficient the more
  concurrency it has — a measurement error, not a discovery.
- **Fewer than three points is refused.** Three parameters need three points;
  two fit perfectly and mean nothing.
- **Zero throughput is refused.** A zero means the step failed and should be
  excluded, not fitted.

## What the answer would mean

$$N_{max} = \sqrt{\frac{1-\sigma}{\kappa}}$$

- **$N_{max}$ below ~20** — coherency dominates. No amount of horizontal scaling
  fixes crosstalk, so the work belongs in reducing shared state rather than
  adding capacity. **This would falsify the design as written**, which is
  precisely why the measurement is worth taking.
- **$\kappa \approx 0$** — scales linearly over the measured range, and the next
  question is which resource binds first. See the what-breaks-first ordering in
  [`scaling-model.md`](../docs/architecture/scaling-model.md).

Either way, `λ` is single-pod throughput against `MockLlmProvider`, not a real
model. That is deliberate — it measures the **gateway**
([ADR-0011](../docs/adr/0011-mock-provider-default.md)) — and it means the
number is not a production throughput figure and must never be quoted as one.

## Reading a report

`fit.py` prints **"Reasons to distrust this fit" first**, before the
coefficients. A fit is easy to produce and easy to quote; that section lists the
conditions under which it should not be. It also exits non-zero when
$R^2 < 0.95$, so a pipeline cannot publish an untrustworthy fit silently.

The condition worth knowing: **p99 rising while CPU stays flat** means the limit
is I/O or a lock, not capacity — so the coefficients describe whatever actually
saturated, which may not be the gateway. Check ingress-nginx connections
(`nginx_ingress_controller_nginx_process_connections`) and the database pool
before quoting them.
