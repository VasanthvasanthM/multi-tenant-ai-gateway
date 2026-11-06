# Scaling Model

The stated goal is to scale to billions of users. This document is the argument that
the architecture can, together with an explicit account of what has been **measured**,
what is **modelled**, and where the model stops being trustworthy.

> ## Status: the model is written, the measurement is not done
>
> No load campaign has been run yet (P12). Every throughput number below is either
> **(M)** a measured single-request latency from a live run, or **(C)** a calculation
> from stated assumptions. There is no measured throughput figure in this document,
> and none should be quoted as if there were.

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

### Measurement protocol (P12, not yet run)

1. Pin the load generator and the system under test to **disjoint** cores —
   `--cpuset-cpus` for k6 on cores 6–7, SUT on 0–5. Without this the generator steals
   CPU from the thing it is measuring and the curve bends for the wrong reason.
2. Sweep pods 1 → 6 via `values-scale.yaml`, holding offered load constant per step.
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

With $\lambda$ unmeasured, this is a sensitivity table rather than a plan:

| If one pod serves | Pods for 92,600 RPS peak | Verdict |
|---|---|---|
| 500 RPS | 186 | plausible, unremarkable |
| 1,000 RPS | 93 | comfortable |
| 2,000 RPS | 47 | comfortable |

At any of these, **the gateway tier is not the hard part** — it is stateless and
horizontally scalable, and 50–200 pods is an ordinary deployment. The hard part is
state, which is the next section.

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
