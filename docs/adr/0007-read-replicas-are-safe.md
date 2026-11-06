# ADR-0007: Read replicas are acceptable for cache reads

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

Tier-2 reads outnumber writes heavily. Read replicas are the obvious way to scale
them, and the obvious objection is replica lag.

## Decision

Route Tier-2 cache **reads** to replicas; all writes and all control-plane reads go to
the primary.

## Rationale

**On this data, replica lag produces a cache miss, never a wrong answer.**

If a replica has not yet received a newly written cache entry, the search does not find
it, the gateway falls through to the provider, and the response is re-cached. The cost
is one avoidable upstream call. The result is still correct.

That is a fundamentally different risk profile from replica lag on, say, an account
balance or an inventory count, where a stale read is a *wrong* read. Here staleness
costs money in exactly the amount the cache was saving, on a small fraction of
requests, and nothing else.

A worked bound: with 100 ms of lag at 23,000 RPS, roughly 2,300 requests fall in the
window — and only those whose prompt was first seen inside that window actually miss.
At any realistic repeat rate that is a rounding error against a 70% hit rate.

## Consequences

**Accepted costs**

- A slightly lower measured hit rate than a primary-only deployment. Must be *measured*
  rather than assumed, so that a replica problem is not mistaken for a cache-tuning
  problem.
- A separate `ConnectionFactory` and the discipline to keep writes off it. A write
  issued to a replica fails outright, which at least fails loudly.
- Monitoring must alarm on lag, because unbounded lag silently degrades the hit rate —
  a *cost* regression with no error anywhere.

**Benefits**

- Read capacity scales without touching the primary.
- The primary keeps its headroom for the write path (ledger, audit, vector writes).

## Explicitly NOT on replicas

- **API key lookups.** A revoked key must stop working immediately; replica lag would
  leave a revoked credential valid for the duration of the lag. This is a security
  boundary, not a cache.
- **Budget and quota checks.** Stale reads here mean over-serving a tenant past a paid
  limit.
- **Anything in the control plane.** Saga state read from a replica can show a step as
  unstarted after it has run, and a saga is a state machine that must not see the past.

## Status

**Implemented.** `ShardedDatabase.forRead(tenantId)` returns a replica when one is
configured for that tenant's shard and the primary otherwise;
`forWrite(tenantId)` always returns the primary. Replicas rotate round-robin per
shard.

Configure them per shard:

```yaml
gateway:
  sharding:
    shards:
      - id: shard-a
        url: r2dbc:postgresql://pg-a:5432/aigw
        replica-urls:
          - r2dbc:postgresql://pg-a-replica:5432/aigw
```

What is **not** yet built is the monitoring this ADR says is required: nothing alarms
on replica lag, and unbounded lag silently degrades the hit rate - a cost regression
with no error anywhere. The Grafana cache dashboard would show it as a falling hit
ratio, which is a symptom rather than the cause.
