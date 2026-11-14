# Scaling Model

The stated goal is to scale to billions of users. This document is the argument that
the architecture can, together with an explicit account of what has been **measured**,
what is **modelled**, and where the model stops being trustworthy.

> ## Status: the campaign has been RUN six times and produced no valid fit
>
> This is not "not done yet". The harness is finished, it has been run against a
> real cluster six times, and it **refused every run** - see
> [section 3b](#3b-the-load-campaign-what-six-runs-established). No USL
> coefficients are published, because none of the runs produced a fit inside the
> model's domain.
>
> Every throughput number below is therefore still either **(M)** a measured
> single-request latency, or **(C)** a calculation from stated assumptions.
> **There is no measured throughput figure in this document, and none should be
> quoted as if there were.**
>
> What the runs DID establish is in 3b and is more useful than the coefficients
> would have been: a self-amplifying Tier-1 timeout under load, a floor below
> which pods cannot be shrunk, and a 6x spread on one unchanged configuration
> that makes any single lambda meaningless. What is missing is a host where
> three pods each get a real core - not code.

---

## 1. Why a headline RPS number would be dishonest

The original blueprint proposed "3 nodes × 5,000 RPS = 15,000 RPS".

That figure cannot be produced on this machine, and a figure produced anyway would be
measuring the laptop, not the design:

- 7.4 GB total RAM, with ~460 MB free while Docker Desktop runs.
- The load generator, the gateway, Redis and PostgreSQL all contend for the same
  4 physical cores. At saturation the benchmark is measuring the **scheduler**.
- `MockLlmProvider` is the default precisely so that a real provider's latency and
  rate limits do not dominate every measurement — but that also means a measured RPS
  reflects a mock, not production.

So the claim this project makes is narrower and testable: **measure the per-pod
scalability curve from 1 to 6 pods, fit it, and state what the fit implies.** A curve
with published coefficients can be argued with. A single number cannot.

[ADR-0013](../adr/0013-usl-not-headline-rps.md)

---

## 2. The Universal Scalability Law

$$X(N) = \frac{\lambda N}{1 + \sigma(N-1) + \kappa N(N-1)}$$

| Term | Meaning | What it is here |
|---|---|---|
| $N$ | Concurrency — gateway pods | 1 … 6 |
| $\lambda$ | Ideal throughput of one unit | RPS of a single pod, unloaded |
| $\sigma$ | **Contention** — serialised work | Redis round-trips, the connection pool, one PostgreSQL primary |
| $\kappa$ | **Coherency** — crosstalk cost | Cache coherence between pods, lock contention, shared HNSW index pages |

Why this model rather than Amdahl's law: Amdahl is monotonic — add capacity, never get
slower. USL has the $\kappa N(N-1)$ term, so when $\kappa > 0$ throughput **peaks and
then declines**. That is the behaviour real distributed systems exhibit, and the peak
is the number worth knowing:

$$N_{max} = \sqrt{\frac{1-\sigma}{\kappa}}$$

**$N_{max}$ is the deliverable of P12.** If the fit says 20 pods, the architecture is
wrong for a billion users and needs sharding before it needs more pods. If it says
$\kappa \approx 0$, the system scales linearly until some other resource binds, and
the next question is which one.

### Measurement protocol (written and executable; NOT YET RUN)

The tooling exists and the fitter is verified - see
[`../../loadtest/README.md`](../../loadtest/README.md). What follows is what it
does, and running it is one command once a cluster exists.

The campaign has now been **run six times on a laptop and has produced no
valid fit**; see [section 3b](#3b-the-load-campaign-what-six-runs-established)
for what the runs did establish, which is more useful than the coefficients
would have been. Every throughput figure in the rest of this document therefore
remains **(C)**.

1. Pin the load generator and the system under test to **disjoint** cores —
   `--cpuset-cpus` for k6 on cores 6–7, SUT on 0–5. Without this the generator steals
   CPU from the thing it is measuring and the curve bends for the wrong reason.
2. Sweep pods 1 → N, measuring **saturation** throughput at each step with a
   closed-loop generator (`loadtest/k6/capacity.js`, a constant VU count).
   Holding a fixed *offered rate* constant per step - which an earlier version of
   this protocol specified - cannot produce a scaling curve: $X(N)$ is the most $N$
   pods can do, so once one pod sustains the offered rate every step reports the
   same throughput and the fit returns $\sigma = \kappa = 0$. Perfect linear
   scalability is the conclusion a sweep reaches when it never loaded the system.
   The offered *concurrency* is what stays constant across steps.
3. Record throughput and p50/p95/p99 at each step. Discard the first 60 s (JIT warm-up
   and connection-pool fill).
4. Fit $\lambda, \sigma, \kappa$ by least squares; report $R^2$ and $N_{max}$.
5. **Confirm the bottleneck moved.** If p99 rises while CPU stays flat, the limit is
   I/O or a lock, not capacity — and the fit describes whatever actually saturated,
   which may not be the gateway. ingress-nginx must be checked explicitly
   (`nginx_ingress_controller_nginx_process_connections`), or the curve describes the
   ingress controller.

---

## 3. What has actually been measured

Single pod, real Redis, real pgvector, real MiniLM (2026-11-02):

| Path | Measured | Target | |
|---|---|---|---|
| Tier 1 miss → upstream | 62.9 ms | — | (M) |
| Tier 1 hit, end to end | 12.5 ms | — | (M) |
| Tier 1 cache lookup alone | **3.94 ms** | < 2 ms | (M) — over target |
| Tier 2 semantic search | **15.38 ms** | < 15 ms | (M) — on target |

Tier 1 misses its target. Redis runs in WSL2, which adds a hop; that is an
explanation, not a defence, and the measured number is the one recorded.

Semantic discrimination, real cosine scores:

| Pair | Score | Outcome | |
|---|---|---|---|
| Paraphrase | 0.9789 | served | (M) |
| Looser paraphrase | 0.8994 | refused at threshold 0.95 | (M) |
| Unrelated | 0.1422 | refused | (M) |

Resilience, live:

- Circuit breaker opened after exactly 10 failures, then **rejected 18 calls without
  attempting them**, and all 14 user-facing requests were served via fallback. (M)
- Forced Kafka redelivery: 18 duplicates, **zero double-billing**, rollup exactly
  equal to ledger rows. (M)

Container: image 309 MB; running at 263 MB of a 512 Mi limit. (M)

---

## 3b. The load campaign: what six runs established

Run 2026-11-10 on k3d, one gateway pod per step, each limited to 400m CPU and
384 Mi, Tier 2 off, `MockLlmProvider`, 70% designed cache-hit mix.

### P8 is verified (M)

The chart deploys, the pod reaches `Ready`, a completion is served end to end
through MetalLB and ingress-nginx, and a repeated prompt comes back
`served_by: tier1_exact, cache_hit: true`. That part is no longer theoretical.

### No USL coefficients are published, and this is why

The first four runs, and four refusals from the fitter (5 and 6 are below):

| Run | Method | Outcome |
|---|---|---|
| 1 | fixed 50 VUs | R² 0.311; hit ratio drifted 81% → 89% |
| 2 | fixed 90 VUs | R² 0.000; hit ratio drifted 70% → 63% |
| 3 | fixed 90 VUs, 2 pods | 1 usable step; server errors at 2 pods |
| 4 | concurrency ladder | σ = 1.316 — outside the model's domain |

Run 4 is the informative one. With the corpus and pre-warm fixed the hit ratio
held at 69.9 / 69.6 / 70.0% across the three steps, so the steps were finally
comparable — and throughput **fell** as pods were added:

| Pods | X(N) | at | p50 | p99 | Pod CPU | Throttled |
|---:|---:|---|---:|---:|---:|---:|
| 1 | 346.7 rps | 64 VUs | 177 ms | 409 ms | 97.9% | 97.8% |
| 2 | 288.8 rps | 128 VUs | 341 ms | 4,703 ms | 58.9% | 72.2% |
| 3 | 267.6 rps | 96 VUs | 90 ms | 1,827 ms | 76.7% | 76.6% |

A curve that falls from N=1 cannot be fitted by the USL with σ ≤ 1, which is
what σ = 1.316 means: not severe contention, but *no curve of this family fits
at all*. N_max and X_max from that fit are arithmetic artefacts.

**This is not evidence that the design does not scale.** A shared bottleneck
produces exactly this curve, and a sweep on one host has three of them: the pods
contend for the same cores, one ingress-nginx sits in front of all of them, and
one Redis sits behind all of them. Both k3d "nodes" are containers on the same
3-core / 2.9 GB WSL VM, so Kubernetes believes it has 6 CPUs and 5.8 GiB. Total
throughput stayed within ~25% of 300 rps at every pod count, which is the
signature of a shared ceiling rather than of per-pod scaling.

### The finding worth keeping: Tier 1 disables itself under load (M)

Three runs of one unchanged configuration reported 69, 190 and 55 rps. The cause
is in the logs, and it is a property of the system rather than of the laptop:

```
Tier1Cache - tier-1 lookup failed: Redis command timed out
Tier1Cache - tier-1 write failed:  Redis command timed out
```

Redis was idle throughout — 2.84 MB of 96 MB, zero evictions, zero rejected
connections, 8–14m CPU. The timeouts are client-side.
`spring.data.redis.timeout` is **500 ms**, and at 90 VUs the request queue was
1.5 s deep, so the reply could not be processed inside its own timeout.

The failure mode is self-amplifying: each timeout turns a cheap Tier-1 hit into
an upstream call, the extra work deepens the queue, and more lookups time out.
Throughput collapses instead of plateauing. Measured at one pod:

| VUs | Throughput | p99 | Hit ratio |
|---:|---:|---:|---:|
| 32 | 210 rps | 333 ms | 70.3% |
| 64 | 347 rps | 409 ms | 69.9% |
| 90 | **55 rps** | 3,105 ms | **55.7%** |

A hit ratio of 55.7% against a 55% exact share means every paraphrase hit was
gone: the cache had effectively switched itself off at the moment it was needed
most. The only visible symptom is the hit ratio — throughput alone looks like
ordinary saturation.

This deserves a design decision rather than a tuning change, and none has been
made here. The timeout value is defensible on its own terms (a cache lookup that
blocks for 500 ms has already failed at its job), but a cache whose failure
*adds* load to a system that is already behind needs a guard — shed load, or
serve stale, rather than promote every timeout to an upstream call.

### Attempt 5 and 6 (2026-11-13): smaller pods, and why that failed too

The obvious objection to the above is "use smaller pods, then three fit". It was
tried, and it establishes a FLOOR that matters more than the ceiling.

**At 200m per pod, Tier 1 collapses.** The hit ratio fell to **0.29** - below
even the 0.55 exact-repeat share, meaning prompts that were definitely cached
were missing. The mechanism is the Redis-timeout loop described above, reached
sooner: a weaker pod has a deeper queue at the same concurrency, so the 500 ms
command timeout is exceeded at 16 VUs instead of 90. The sweep's hit-ratio gate
rejected every rung rather than reporting the fast-looking throughput that a
disabled cache produces.

So the usable window on this hardware is bounded on BOTH sides: pods need
roughly 400m to keep the queue inside the Redis timeout, and 3 x 400m starves a
3-core VM that also runs two k3s control planes, ingress-nginx, Postgres and
Redis. There is no pod size at which three of them are both healthy and
isolated.

**The decisive evidence is non-reproducibility.** The same nominal
configuration - one 400m pod, same corpus, same ladder - has now measured:

| Run | Single pod | Two pods |
|---|---|---|
| fixed 50 VUs | 69 rps | — |
| fixed 90 VUs | 190 rps | — |
| fixed 90 VUs, later | 55 rps | — |
| ladder | **346.7 rps** | 288.8 rps |
| ladder, 2026-11-13 | excluded | **112.6 rps** |

A 6x spread on one configuration. Any lambda quoted from it describes the
machine's mood on the day, not the gateway. Two of those runs were also
invalidated by operational faults rather than by the system under test - a
`kubectl scale` that silently failed because Helm owns `.spec.replicas` under
server-side apply, leaving three pods serving during a step labelled "1 pod",
which the sweep caught and excluded.

**Conclusion: P12 cannot be completed on this machine, and this is now
evidence-backed rather than a guess.** The harness is finished and has refused
six invalid runs, which is the behaviour it was built for. What is missing is a
host, not code.

### Attempts 7 and 8 (2026-11-13): the cap was self-inflicted

Two corrections to everything above, both mine.

**The "hardware wall" was a `.wslconfig` I wrote.** The VM was pinned to
`memory=3GB, processors=3` in an earlier session when the host was short of
RAM, and six runs then treated that as a property of the laptop. The machine
has **8 logical CPUs and 7.4 GB**. Raised to `memory=4GB, processors=6`, which
also puts k6 on the 2 CPUs the VM does not have - the generator/SUT core
isolation the protocol asks for and had never had.

**A sick ingress was being read as a capacity limit.** `ingress-nginx` had
restarted **17 times**; it was accepting requests and timing out upstream after
3.7-7.3 s, returning 503 while the gateway pod was healthy and Ready. Deleting
the pod fixed it in 65 s. Every request in every sweep goes through it, so a
degraded ingress is indistinguishable from a slow gateway - and it plausibly
accounts for some of the earlier variance attributed to the hardware.

**A CPU limit is only meaningful relative to its node.** With the VM at 6 CPUs
and the pod limit still 400m, `availableProcessors()` returned 6, the JVM
created ~6 event loops, and they shared an unchanged 40 ms-per-100 ms quota:
throttled in **96.9%** of periods, p50 177 ms -> 704 ms. More cores made the
pod slower. Raised to 1000m per pod.

#### The first measurement worth reporting (M)

6-CPU VM, 1000m per pod, healthy ingress, hit ratio stable across all three
steps at 0.696 / 0.694 / 0.699 - so the steps are finally comparable:

| Pods | X(N) | p50 | p95 | Pod CPU | Throttled | Ingress CPU |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 271.3 rps | 198 ms | 476 ms | 89.6% | 92.9% | 376m |
| 2 | **288.4 rps** | 187 ms | 402 ms | 56.3% | 61.0% | 407m |
| 3 | 229.1 rps | 310 ms | 1,154 ms | 54.5% | 31.0% | n/a |

Fitted: lambda 271.3, sigma 0.0913, kappa 0.395, N_max 1.5, X_max 303.

**What may be quoted from this, and what may not.**

`lambda >= 271 rps for a single 1000m pod` is a real measured figure **(M)** -
the first in this document. It is a LOWER BOUND, because step 1 peaked at the
top rung of its ladder: the pod was still gaining throughput when the ladder
ran out, and it was throttled in 92.9% of periods, so it was genuinely CPU
bound at its own limit.

**kappa = 0.395 and N_max = 1.5 must NOT be quoted as properties of the
gateway.** Step 3 is the reason: the pods sat at **54.5% CPU, throttled only
31%** - nowhere near saturated - and throughput still fell. A unit that is not
the constraint cannot be what limits the curve. Meanwhile a single ingress pod,
with a 100m request and **no CPU limit at all**, was drawing ~0.4 cores at the
peak. The first reading was that the curve past two pods describes the ingress
controller - the failure mode step 5 of the protocol was written to catch.
**That reading has since been tested and is wrong**; see below.

R^2 = 1.0000 here is arithmetic, not agreement: three points determine a
three-parameter model exactly, so every residual is zero whatever the data. The
fitter says so itself and it remains the only distrust reason it reports.

#### Attempt to confirm the ingress hypothesis - FAILED, and inconclusive

The obvious test of "the curve past two pods describes the ingress" is to give
the ingress more capacity and re-measure. Scaled `ingress-nginx` to 2 replicas
with 3 gateway pods, and it did not merely fail to help - it collapsed:

| | Throughput | p50 | Hit ratio |
|---|---:|---:|---:|
| 1 ingress replica | 229.1 rps | 310 ms | 69.9% |
| 2 ingress replicas | **0.82 rps** | **18,069 ms** | 0.0% |

Then the Kubernetes API server itself became unreachable
(`TLS handshake timeout`), and the scale-down command that would have fixed it
failed for the same reason. Recovery took about 80 s after load stopped.

**This does not confirm or refute the hypothesis**; it is confounded twice
over. One of the two replicas was a 9-hour-old pod carrying a restart, so half
the traffic may have been routed into the known-sick instance - the same one
whose 17 restarts had already corrupted an earlier run. And 3 x 1000m gateway
pods plus 2 ingress replicas plus k3s on a 6-CPU VM is simply more than the VM
has, so the collapse may be resource exhaustion rather than anything about the
ingress.

What it does establish: **the test bed cannot host the experiment that would
settle the question.** Adding the component under suspicion is enough to take
the control plane down.

The clean version of this test is to bypass the ingress rather than scale it -
measure the gateway Service directly - so that the request path contains one
fewer shared component instead of one more. That is the next thing to try, and
it was not reached here.

#### The ingress is NOT the ceiling - the LOAD GENERATOR most likely is

Measuring one pod through the ingress and then bypassing it entirely:

| 1 gateway pod | Throughput | p50 |
|---|---:|---:|
| through ingress | **271.3 rps** | 198 ms |
| bypassing it (kubectl port-forward) | **37.8 rps** | 1,402 ms |

Two conclusions, one about method and one about the result.

`kubectl port-forward` is **unusable as a measurement path**: it is a single
Go TCP proxy and it is ~7x slower than the ingress it was meant to eliminate.
Anyone tempted to "remove the ingress variable" this way would measure the
proxy.

More importantly, the ingress **demonstrably sustains 271 rps with one pod**,
so it cannot be what limits the 3-pod step to 229 rps. The earlier hypothesis
is refuted by its own numbers.

What remains, and fits every observation: **k6 on the host is the constraint.**
The VM holds 6 of 8 logical CPUs, leaving the generator 2. The signature is in
the 3-pod ladder:

| 3 pods, VUs | Throughput |
|---:|---:|
| 48 | 155.3 rps |
| 96 | **229.1 rps** |
| 192 | 159.4 rps |

Throughput FALLS as offered concurrency rises past 96, while the pods sit at
54.5% CPU and are throttled in only 31% of periods. A system under test that is
half idle cannot be the thing that slows down when more load is offered; a
generator out of CPU can, and does exactly this.

Step 1 of the protocol asks for the generator and the SUT on disjoint cores.
They now are - but disjoint is not sufficient if the generator's share is too
small, and giving the VM 6 of 8 CPUs is what made it too small. The two
requirements pull against each other on one machine.

#### What is still needed for a defensible N_max

1. **A fourth and fifth pod count**, so R^2 becomes a test rather than a
   tautology. 6000m of VM CPU now makes 4-5 pods at 1000m affordable.
2. **The ingress removed as a variable** - either given its own CPU limit and
   replicas, or bypassed so the measurement is of the gateway rather than of
   the path to it.
3. **A ladder that extends past the knee at every step**, so no step reports a
   lower bound.

Until those, the honest claim is: **one pod serves at least 271 rps of this
workload, two pods serve more than one, and the ceiling observed beyond that
has not been attributed to the gateway.** That is considerably less than "we
measured billions", and it is defensible.

### What would finish this

A host where three pods each get a real core, and a fourth point. The sweep is
one command: `MAX_PODS=4 ./loadtest/scripts/capacity-sweep.sh`. Nothing in the
harness is missing — it refused four bad fits in a row, which is what it is for.

---

## 4. The arithmetic of "billions of users"

All of this is **(C)** — calculated from the stated assumptions. Change an assumption
and the conclusion changes; that is the point of writing them down.

### Assumptions

| Assumption | Value | Basis |
|---|---|---|
| Registered users | 1 × 10⁹ | the stated goal |
| Daily active fraction | 20% | typical consumer product |
| LLM requests per active user per day | 10 | an assistant feature, not a chat product |
| Peak-to-average ratio | 4× | diurnal, single-region peak |
| Cache hit rate (Tier 1 + Tier 2) | 70% | assumption — **must be measured** |
| Tokens per request | 500 in, 500 out | — |

### Derived load

```
daily requests   = 1e9 × 0.20 × 10              = 2.0 × 10⁹ / day
average RPS      = 2.0e9 / 86,400               ≈ 23,100 RPS
peak RPS         = 23,100 × 4                   ≈ 92,600 RPS
upstream RPS     = 92,600 × (1 − 0.70)          ≈ 27,800 RPS at peak
```

So the system has to absorb ~92,600 RPS at the edge while only ~27,800 RPS reaches a
provider. **The cache is not an optimisation at this scale — it is the thing that
makes the provider bill and the provider rate limits survivable.**

### Unit economics

At commodity small-model pricing (~$0.15 / 1M input tokens, ~$0.60 / 1M output):

```
cost per request  = (500/1e6 × $0.15) + (500/1e6 × $0.60)  = $0.000375
```

| Scenario | Daily | Annual |
|---|---|---|
| No cache (2.0e9 req/day) | $750,000 | **$274M** |
| 70% hit rate (0.6e9 upstream) | $225,000 | **$82M** |
| **Saving** | $525,000/day | **~$192M/year** |

Every percentage point of hit rate is worth roughly **$2.7M/year** at this volume.
That is the entire commercial case for Tier 2 existing: Tier 1 catches exact repeats,
but paraphrase is where the remaining volume is, and a semantic cache is the only way
to reach it.

It is also why the Tier-2 **false-hit rate matters more than the hit rate**. Serving a
wrong answer to save $0.000375 is a terrible trade, which is why the default threshold
is 0.95, why shadow mode exists to measure the false-hit rate before Tier 2 serves
anything, and why the 1,000-character guard refuses to use Tier 2 where MiniLM's
256-token truncation makes different prompts collide.

### Capacity required

$\lambda$ is now **measured**, so this is a plan rather than a sensitivity table -
with one assumption stated loudly below.

**Measured inputs (M)**, from the 2026-11-13 run (section 3b): one pod limited to
1000m CPU / 384 Mi serves **>= 271.3 rps** of the standard mix, at a cache hit
ratio of **0.696**, p50 198 ms, p95 476 ms.

**Target (C):** 2 billion requests/day with a 4x diurnal peak gives 23,148 rps
average and **92,593 rps peak**.

| Quantity | Value | |
|---|---:|---|
| Pods at average load | **85** | (C) 23,148 / 271.3 |
| Pods at peak | **341** | (C) 92,593 / 271.3 |
| Pods at peak, 70% target utilisation | **488** | (C) the number to budget |
| Upstream calls/day avoided by the cache | **1.39e9** | (C) at the measured 0.696 |
| Upstream calls/day still made | **6.08e8** | (C) |
| Saving/day at $0.000375/request | **$522,000** | (C) price assumed, ratio measured |
| Saving/year | **~$190M** | (C) |

Because $\lambda$ is a LOWER bound - step 1 was still gaining throughput when its
ladder ran out - **341 pods is an UPPER bound** on the peak fleet. A pod that
turns out to serve 400 rps needs 232.

**The assumption carrying this table, stated plainly.** Projecting one measured
pod to 341 assumes the fleet scales approximately linearly: that $\kappa \approx 0$
and $\sigma$ is small. **That is exactly what the campaign failed to establish.**
The 3-pod fit returned $\kappa = 0.395$, which was traced to the load generator
rather than to the gateway (section 3b), so $\kappa$ is *unmeasured*, not *known
to be zero*.

So this is a **capacity plan conditional on linear scaling**, not a proven
capacity. What makes that assumption defensible is architectural rather than
empirical: the gateway tier holds no per-request state, every request carries its
own `tenantId`, and both cache tiers live outside the pod. There is no shared
mutable structure between pods for $\kappa$ to arise from - a claim about the
design that a 4-or-more-pod sweep on adequate hardware would confirm or refute in
an afternoon.

**If $\kappa$ were 0.01** - small but non-zero - $N_{max}$ would be about 10 pods
and this table would be wrong by two orders of magnitude. That single measurement
is what stands between this project and a defensible billion-user claim, and it is
why section 3b refuses to publish the coefficients it did obtain.

At 341 pods **the gateway tier is still not the hard part** - it is stateless and
horizontally scalable, and a few hundred pods is an ordinary deployment. The hard
part is state, which is the next section.

---

## 4b. Fleet sizing for 2 billion requests/day

Every row is **(C)** derived from two **(M)** inputs - $\lambda \ge 271.3$ rps/pod
and a 0.696 hit ratio - plus the assumptions listed under each component. Change an
assumption and the row changes; they are stated so that can be done.

**Load:** 23,148 rps average, **92,593 rps peak** (4x diurnal), of which
**28,148 rps peak** reaches a provider at the measured hit ratio.

### Gateway tier

| | Value | Basis |
|---|---:|---|
| Pods at peak | **341** | 92,593 / 271.3 |
| Pods budgeted (70% utilisation) | **488** | headroom for failure + deploys |
| vCPU / memory | **488 / 183 GiB** | 1000m + 384 Mi per pod, as measured |
| Worker nodes | **~38** | 16 vCPU nodes, ~80% allocatable |

### Redis - Tier 1

*Assumes distinct prompts are 10% of traffic (2x10^8/day) and a 2 KiB mean entry
under a 24 h TTL. The hit ratio is measured; this split is not.*

| | Value | Basis |
|---|---:|---|
| Working set | **~381 GiB** | 2x10^8 x 2 KiB |
| Ops/s at peak | **~121,000** | one lookup per request + one write per miss |
| Primaries | **9** | ~48 GiB usable per 64 GiB node |
| With 1 replica each | **18 instances** | Cluster mode, hash-tagged `{tenant:x}` keys |

Ops/s is comfortable - a single node does ~100k - so **memory binds first, not
throughput**. That is the benign direction: eviction lowers the hit rate and raises
cost rather than failing requests.

### PostgreSQL + pgvector - Tier 2

*Same distinct-prompt population; 4 KiB/row including prompt and response, and
~2.3 KiB/row of HNSW index that must stay resident.*

| | Value | Basis |
|---|---:|---|
| Row data | **~763 GiB** | 2x10^8 x 4 KiB |
| HNSW index (must be in RAM) | **~439 GiB** | the figure that drives shard count |
| Shards | **8** | matches the existing `PARTITION BY HASH (tenant_id) x 8` |
| Per shard | 1 primary + 2 replicas | reads prefer a replica (`ShardedDatabase`) |
| **Instances** | **24** | 8 x 3 |
| Writes/s/shard at peak | **~3,500** | 28,148 upstream / 8 |

The index, not the data, sets the shard count. A shard holding more than its RAM
degrades to disk-backed search and the Tier-2 latency target fails quietly.

### Kafka

*Three events per request on average: usage + audit always, cache-write on a miss
(1 + 1 + 0.304). 1 KiB mean message, RF=3.*

| | Value | Basis |
|---|---:|---|
| Events/day | **4.6x10^9** | 2x10^9 x 2.304 |
| Events/s peak | **~213,000** | |
| Ingress at peak | **208 MiB/s** | **625 MiB/s** written with RF=3 |
| Brokers | **9** | ~100 MiB/s sustained per broker, plus RF headroom |
| Partitions: `gateway.usage.v1` | **32** | 92,593/s peak, ~10k/s per partition |
| Partitions: `gateway.audit.v1` | **32** | same rate |
| Partitions: `gateway.cache-write.v1` | **16** | 28,148/s peak |
| Storage at 24 h retention | **~51 TiB** | retention is the lever; 7 days would be ~360 TiB |

**Partition count is a ceiling on consumers, not a tuning knob.** Consumption is
partition-ordered by design, so a 33rd metering pod against 32 partitions sits idle -
repartition before scaling the Deployment, never after.

### Consumer and control services

| Service | Pods | Bound by |
|---|---:|---|
| `metering` | **19** (max 32) | 92,593/s over ~5k/s per pod; capped by partitions |
| `audit` | **19** (max 32) | same |
| `indexer` | **6** (max 16) | 28,148/s; also does the Tier-2 embedding write |
| `control-plane` | **3** | not on the hot path - sized for availability only |

### Totals

| | |
|---|---|
| Application pods | **~535** |
| Stateful instances | **18 Redis + 24 PostgreSQL + 9 Kafka = 51** |
| Worker nodes (application) | **~38** |

**What this table is.** A sizing derived from one measured pod, by the arithmetic
above. **What it is not:** evidence that 488 pods behave like 488 times one pod. That
is the $\kappa \approx 0$ assumption from section 4, which the campaign did not
establish. Every row here inherits it.

---

## 5. What breaks first

Ordered by when it binds, not by severity.

### 1. pgvector HNSW, per tenant

An HNSW search is roughly $O(\log n)$ in entries, but recall degrades and latency
climbs as a tenant's corpus grows, and the index must stay in memory to be fast.

- **Mitigation already built:** `prompt_cache` is `PARTITION BY HASH (tenant_id)` × 8,
  and *every* query carries `tenant_id`, so the planner prunes to one partition.
  Combined with `tenantId` as the shard key, a search is never cross-tenant **and**
  never cross-shard. [ADR-0006](../adr/0006-shard-key-is-tenant-id.md)
- **Still needed:** `ef_search` tuning per tenant size, and TTL eviction keeping the
  working set bounded (`EvictionJob` exists).
- **Ceiling:** one PostgreSQL primary. Beyond it, shard - which the consistent-hash
  ring now does: `ShardedDatabase` routes every Tier-2 read and write by tenant, and
  reads prefer a replica. **A whale tenant still lands on one shard**, because
  sub-sharding on `tenant#bucket` is designed but not built.

### 2. Redis memory for Tier 1

Tier 1 is an exact-match store; its size grows with distinct prompts, not users.

- **Mitigation:** TTL, per-tenant caps, hash-tagged keys (`{tenant:acme}:…`) so a
  tenant's working set lives on one Cluster node.
- **Symptom when it binds:** eviction, so the hit rate falls and upstream cost rises —
  a *cost* failure, not an *availability* failure, which is the right direction.

### 3. PostgreSQL write throughput on the ledger

Every request produces a usage row. At 23,100 average RPS that is ~2 × 10⁹ rows/day.

- **Mitigation:** hash partitioning × 4; `ON CONFLICT DO NOTHING`; hourly rollup so
  queries hit `tenant_usage_rollup` rather than the ledger.
- **Still needed:** batch inserts in the consumer, and a retention policy — the ledger
  is append-only and nothing currently drops old partitions.

### 4. Upstream provider rate limits

At ~27,800 upstream RPS at peak, provider limits bind before anything in this system
does.

- **Mitigation already built:** `ProviderRegistry` with an ordered fallback chain, a
  circuit breaker per provider, and bulkheads so one slow provider cannot consume the
  whole pool.

### 5. Kafka partitions vs consumers

Consumption is partition-ordered by design, so **adding consumer replicas beyond the
partition count does nothing** — the extra consumers sit idle.

- **Operational consequence:** repartition the topic *before* scaling consumers.
  Scaling the Deployment first looks like it should help and provably cannot.

### 6. The control plane

Barely scales and does not need to: the hot path makes **zero** synchronous calls to
it. [ADR-0010](../adr/0010-no-synchronous-inter-service-calls.md) Key and quota
changes propagate over Kafka into local Redis, so the control plane can be down for a
deploy without the data plane noticing.

---

## 6. Autoscaling

The HPA targets `gateway_requests_per_second`, not CPU.

An I/O-bound pod waiting on an upstream LLM uses almost no CPU while being completely
saturated in useful work. A CPU-driven HPA therefore refuses to add pods exactly when
they are needed, and — worse for this project — a load test against a CPU-driven HPA
produces a **flat replica count that looks like evidence the service does not need to
scale**. CPU is retained only as a high fallback (80%) for genuine CPU exhaustion.

Behaviour is deliberately asymmetric:

| Direction | Stabilisation | Reason |
|---|---|---|
| Up | 30 s | A spike needs capacity now |
| Down | 300 s | Each new pod pays ~16 s JVM start plus model load; a surplus pod is far cheaper than a cold start under load |

> **Trap that invalidates a whole run:** the adapter rule computes `rate(...[1m])`,
> which needs two scrapes inside the window. At a scrape interval above 30 s the
> metric returns nothing, the HPA reports `<unknown>`, and **falls back to CPU without
> erroring**. Always confirm `kubectl get hpa` shows a number before trusting a
> measurement.

---

## 7. The honest summary

**What is demonstrated:** a two-tier semantic cache that measurably serves paraphrases
and measurably refuses near-misses; tenant isolation enforced at four independent
layers; idempotent billing proven under forced redelivery; a circuit breaker proven to
shed load and serve from fallback; a container that runs non-root in 263 MB.

**What is modelled, not demonstrated:** every throughput figure, the 70% hit rate,
$N_{max}$, and the dollar savings that follow from them.

**What would falsify the design:** a USL fit with a large $\kappa$. That would mean
pods interfere with each other, and no amount of horizontal scaling fixes crosstalk —
it would send the work back to reducing shared state before adding capacity.

**What remains before any of this is more than arithmetic:** P10 (Prometheus, so the
HPA has a metric at all), P8 cluster bring-up, then P12. In that order.
