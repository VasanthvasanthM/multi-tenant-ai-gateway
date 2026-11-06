# ADR-0006: Shard key is tenantId, routed by consistent hash

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

One PostgreSQL primary cannot hold the semantic cache for a billion-user system. The
data has to be partitioned, and the choice of key determines which queries become
expensive.

## Decision

Shard key is `tenantId`. Routing is a consistent-hash ring with ~150 virtual nodes per
shard, hashed with MurmurHash3.

## Rationale

**Every cache lookup is already tenant-scoped.** Tier 1 keys embed the tenant; the
Tier-2 query carries `WHERE tenant_id = :tenantId`. So sharding on `tenantId` means a
request touches exactly **one** shard.

That is the whole argument, because the alternative is catastrophic for this workload:
a scatter-gather approximate-nearest-neighbour search across N shards runs N HNSW
searches, waits for the slowest, and merges the results. ANN is the most expensive
operation in the system, and fanning it out multiplies its cost by the shard count
while making p99 the maximum of N latencies. **There is never a cross-shard ANN
search**, and that property is purchased entirely by this key choice.

**Why consistent hashing rather than modulo.** `hash(tenant) % N` remaps nearly every
key when N changes, so adding a shard invalidates the whole cache at once. A consistent
hash moves approximately K/N keys.

**Why ~150 virtual nodes.** One point per shard distributes badly; ~150 brings the load
standard deviation to a few percent.

## Implementation notes

- The ring is **immutable and copy-on-write**. A ring mutated while being read is a
  correctness bug under concurrency, and routing is on the hot path.
- **Shards are sorted before the ring is built**, so every node computes a
  byte-identical ring from identical membership. Without that, two pods could route the
  same tenant to different shards, and the symptom is an unexplained cache miss rate
  rather than an error.
- Linear probing on vnode collision, so two shards hashing to one position does not
  silently drop one.
- `prompt_cache` is additionally `PARTITION BY HASH (tenant_id)` x 8 *within* a shard,
  so the planner prunes to one partition.

## Consequences

**Accepted costs**

- **A whale tenant cannot be split by this key alone.** One enormous tenant lands on
  one shard and can saturate it. Mitigation is sub-sharding on `(tenantId, bucket)` for
  identified whales — designed, not built.
- No cross-tenant analytics from the sharded path; that belongs in the ledger.
- Rebalancing still moves K/N keys, which is a transient miss-rate rise.

**Benefits**

- Single-shard reads and writes; no scatter-gather.
- Natural tenant isolation: a shard failure affects the tenants on it, not everyone.

## Status

The ring and its tests exist in `common`. **Routing does not use it yet** — the gateway
talks to one database. This is tracked as P7.
