# B5 — The HLD: services, boundaries, and why

[`HLD.md`](../architecture/HLD.md) is 647 lines and uses C4 levels. This is the
guided tour: the handful of ideas that actually carry the design, and what to say
about each.

---

## 1. Five services, and the rule that decided the split

| service | scales with | pods at 2e9 req/day |
|---|---|---|
| **gateway** | request rate | 488 |
| **metering** | event volume | 19 (capped at 32) |
| **audit** | event volume | 19 (capped at 32) |
| **indexer** | event volume | 6 (capped at 16) |
| **control-plane** | nothing | 3 |

**The split is along failure and scaling boundaries, not along data entities.**

That sentence is the whole section. The obvious way to split a system is by
*thing* — a tenant service, a cache service, a billing service. This one splits
by **how each part needs to scale and what happens when it breaks.**

Look at the right-hand column. The gateway needs 488 pods; the control plane
needs 3. If they shipped as one service you would have to pick one number for
both — either 488 copies of a control plane that does almost nothing, or 3
gateways that fall over.

**Say it like this:** *"I split on scaling and failure boundaries rather than by
entity, because the gateway scales with request rate, the consumers scale with
event volume, and the control plane barely scales at all. One deployable means
one scaling decision forced on three different workloads."*

### Why the consumers have a pod cap

`metering` is capped at 32 pods because its Kafka topic has 32 partitions.

Kafka gives each partition to exactly one consumer in a group. With 32
partitions, the 33rd pod **gets nothing** — it starts, joins the group, is
assigned no work, and sits idle while looking perfectly healthy.

So the cap is not a guess. It is the partition count, and raising it means
repartitioning the topic *first*.

---

## 2. The two layers of load balancing, and why both

```
client → MetalLB (L4) → ingress-nginx (L7) → gateway pods
```

People ask why there are two. They do different jobs:

- **MetalLB** works at layer 4 (TCP). Its job is to own an external IP address
  and get packets into the cluster. In a cloud you would not need it — the cloud
  provider's load balancer does this. On bare metal or k3d, nothing does, so
  MetalLB fills the gap. **It is free, which is why it is here.**
- **ingress-nginx** works at layer 7 (HTTP). It reads the `Host` header, matches
  the route, and chooses *which pod* gets the request.

Only the second one can make an intelligent choice, because only it can see
HTTP.

### Least-connections, not round-robin

[ADR-0012](../adr/0012-least-connections.md). Round-robin sends request N+1 to
the next pod in the list regardless of what that pod is doing. If one pod is
stuck on a 30-second LLM call, round-robin cheerfully sends it more work.

Least-connections sends the next request to whichever pod has the fewest
in-flight. With request durations spread from 2 ms to 30 s — which is exactly
this system's profile — that difference is large.

**The general lesson:** round-robin is fine when every request costs the same.
This system's requests differ by four orders of magnitude, so it isn't.

---

## 3. What talks to what

```
gateway ──writes──► Kafka ──read by──► metering, audit, indexer
gateway ──reads/writes──► Redis, PostgreSQL
control-plane ──writes──► its own tables + outbox ──relay──► Kafka
```

**Nothing in that diagram is a service calling a service.**
[ADR-0010](../adr/0010-no-synchronous-inter-service-calls.md) forbids it. The
reasoning and the failure behaviour are in [B4a](B4a-failure-behaviour.md); the
short version is that waiting on another service multiplies your downtime into
theirs.

Services communicate through exactly two things: **Kafka topics** and **the
database**. Both are durable, both decouple timing, and neither lets a slow
consumer slow a producer.

---

## 4. Multi-tenancy is structural, not a filter

This is the part worth understanding properly, because it is where the design is
strongest.

A naive multi-tenant system adds `WHERE tenant_id = ?` to its queries. That
works until someone forgets it once.

Here, tenancy is built into the **shape** of the data:

| place | how tenancy is enforced |
|---|---|
| Redis keys | `{tenant:acme}:prompt:<hash>` — the tenant is in the key |
| PostgreSQL | `PARTITION BY HASH (tenant_id)` × 8 — a different physical table per tenant group |
| Tier-2 query | `tenant_id` is the **first** predicate, so the planner prunes to one partition |
| sharding | the shard key **is** `tenantId` ([ADR-0006](../adr/0006-shard-key-is-tenant-id.md)) |
| rate limiting | one bucket per tenant |

The difference matters. With hash partitioning, a query that forgot its
`tenant_id` does not quietly return another tenant's rows — it has to scan every
partition, which is slow and obvious. **The mistake becomes a performance
problem instead of a data breach.**

**Say it like this:** *"Isolation is structural rather than a predicate. The
shard key is the tenant, the table is hash-partitioned on the tenant, and the
tenant is in every cache key — so a cross-tenant read is not something the
system can do by accident."*

### The Redis hash tag, which looks like a typo

`{tenant:acme}:prompt:abc123` — the braces are not decoration.

In Redis Cluster, braces mark the part of the key used to choose a node. So
every key for `acme` lands on **the same node**. That is what makes the
rate limiter's multi-key Lua script legal — Redis refuses a script touching keys
on different nodes.

Without the braces, the limiter would fail in a cluster and work fine on a
single node, which is the worst kind of bug: invisible in development.

---

## 5. Autoscaling on request rate, not CPU

The default Kubernetes autoscaler scales on CPU. This one scales on requests per
second.

Why: **a cache hit uses almost no CPU.** A pod serving 200 cached requests a
second might sit at 15% CPU. CPU-based autoscaling would see an idle pod and
scale *down*, right as traffic rose.

CPU is a proxy for load. Here it is a bad proxy, because the whole point of the
system is to make load cheap. So it scales on the thing it actually cares about.

> This flips when Tier 2 is serving — embedding is genuinely CPU-bound, and a pod
> can be at 80% CPU serving a handful of requests. The HLD's autoscaling section
> predates Tier 2 ever having run. See
> [`tier2-measured.md`](../architecture/tier2-measured.md) §4.

---

## 6. What breaks first

A good design document says where the ceiling is. This one does:

- **HNSW index must stay in RAM.** ~439 GiB at full scale. If it spills to disk,
  Tier-2 latency goes from ~100 ms to seconds, and the cache becomes slower than
  the thing it is caching.
- **Kafka partition count caps consumer pods.** See above.
- **One whale tenant lands on one shard.** The ring spreads tenants evenly;
  it cannot spread *one* tenant.

That third one is a real limit, and it is the first gap in the HLD's own list.

---

## 7. Two of the HLD's "known gaps" are now stale

A design doc that claims gaps it has closed is as misleading as one that hides
them. Checked on 2026-11-15:

**Gap 2 — "the mutation score has never been measured."** PITest has since run
as its own CI job. The specific claim "it has not yet executed once, so there is
no mutation score" is no longer true, and the coverage figures quoted alongside
it are from an older run.

**Gap 3 — "a streamed response spends no budget."** This is now closed in code.
`CompletionPipeline.stream()` draws the budget down two ways: if the provider
sends a terminal usage frame (`stream_options.include_usage`), the exact token
count is used via `budgetGate.record`; otherwise it falls back to
`budgetGate.recordStreamed` counting characters. `BudgetGate.recordStreamed`
exists and is called. The gap text still says neither is implemented.

Both are logged as work items. The lesson is the one this project keeps
relearning: **documentation rots silently, and a stale gap list costs you
credibility exactly when someone is checking your rigour.**

---

## How to present the HLD

If someone asks you to walk them through the architecture, lead with the
decisions rather than the boxes:

1. **"Five services, split on scaling and failure boundaries"** — then the
   488-vs-3 pods contrast, which makes it obvious.
2. **"No synchronous calls between services"** — then the uptime multiplication
   argument.
3. **"Isolation is structural"** — then hash partitioning turning a forgotten
   predicate into a slow query instead of a breach.
4. **"Autoscaling on RPS, not CPU"** — then "a cache hit uses no CPU, so CPU is
   a bad proxy for load here."
5. **"Here is what breaks first"** — the HNSW RAM limit, the partition cap, the
   whale tenant.

Point five is the one that distinguishes a design document from a diagram.
Anyone can draw boxes; naming your own ceiling is the part that reads as
experience.

---

**Next:** [B6 — the LLD](B6-lld.md).
**Previous:** [B4a — why a broken service does not break a request](B4a-failure-behaviour.md).
