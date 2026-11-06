# ADR-0013: Scaling proven by a USL fit, not a headline RPS number

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author
- **Deviation from blueprint:** the blueprint claimed "3 nodes x 5,000 RPS = 15,000 RPS"

## Context

The goal is to scale to billions of users, and the available hardware is one laptop:
7.4 GB RAM, 4 physical cores, ~460 MB free with Docker Desktop running. The blueprint
stated a throughput figure.

## Decision

Do not publish a headline RPS number. Measure the scalability **curve** from 1 to 6
pods, fit the Universal Scalability Law, and publish the coefficients, the fit quality
and the implied ceiling.

## Rationale

**A headline number measured here would be measuring the laptop.** The load generator,
the gateway, Redis and PostgreSQL contend for the same four cores. At saturation the
benchmark reports the Linux scheduler's behaviour under oversubscription. Quoting that
as system throughput is not an approximation — it is a different measurement wearing
the same units.

**A single number also cannot be argued with.** "15,000 RPS" has no stated load mix, no
cache hit rate, no percentile, and no hardware. It cannot be reproduced and it cannot
be falsified, which means it carries no information.

**The USL is falsifiable and extrapolates with visible assumptions:**

$$X(N) = \frac{\lambda N}{1 + \sigma(N-1) + \kappa N(N-1)}$$

where $\sigma$ is contention (serialised work) and $\kappa$ is coherency (crosstalk).
The $\kappa N(N-1)$ term is why this model and not Amdahl's law: when $\kappa > 0$,
throughput **peaks and then declines**, which is what real distributed systems do. The
peak,

$$N_{max} = \sqrt{\frac{1-\sigma}{\kappa}}$$

is the useful output. A fit saying $N_{max} = 20$ means the architecture is wrong for a
billion users and needs sharding before it needs pods. A fit with $\kappa \approx 0$
means it scales linearly until some other resource binds, and names what to measure
next.

Three coefficients with an $R^2$ are a claim someone can attack. That is the point.

## Consequences

**Accepted costs**

- No marketing number. For a portfolio project this cuts against the instinct to quote
  one.
- More work: a controlled sweep, cpuset pinning, warm-up discard, a least-squares fit.
- The result may be unflattering. A large $\kappa$ would be a finding to publish, not
  to hide.

**Benefits**

- Every claim is traceable to a measurement or a stated assumption.
- Extrapolation is explicit rather than linear hand-waving.
- It identifies the **next** bottleneck rather than just the current throughput.

## Protocol

1. Pin k6 and the system under test to **disjoint** cores, or the generator steals CPU
   from what it measures and the curve bends for the wrong reason.
2. Sweep 1 -> 6 pods via `values-scale.yaml`.
3. Discard the first 60 s per step (JIT warm-up, pool fill).
4. Fit $\lambda, \sigma, \kappa$; report $R^2$ and $N_{max}$.
5. Confirm the bottleneck moved as expected. If p99 rises while CPU is flat, the limit
   is I/O or a lock — and the fit then describes whatever actually saturated, which may
   not be the gateway.

## Status

**Not yet run** (P12). Blocked on P10 (Prometheus, so the HPA has a metric) and on a
cluster bring-up. Until then, every throughput figure in
[../architecture/scaling-model.md](../architecture/scaling-model.md) is labelled as a
calculation rather than a measurement.
