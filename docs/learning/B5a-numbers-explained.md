# B5a — Every number explained

Where each figure in the HLD comes from, how the sharding actually works, and
the eight failure scenarios in full.

**Nothing here is measured except one number.** The only measurement is
**271.3 rps per pod (M)**. Everything else is arithmetic on that plus stated
assumptions. That distinction is the whole point.

---

## 1. Where the demand figures come from

Start from the goal and divide.

```
Registered users                   1,000,000,000     <- the stated goal
× 20% daily active (ASSUMED)   =     200,000,000     users/day
× 10 requests each (ASSUMED)   =   2,000,000,000     requests/day
÷ 86,400 seconds in a day      =          23,148     rps average
× 4 diurnal peak (ASSUMED)     =          92,593     rps at peak
```

**The 4× peak factor** is because traffic is not flat. Everyone uses your product
during their waking hours, so the busy hour is several times the daily average.
4× is a normal single-region figure.

```
92,593 × (1 − 0.696 hit ratio)  =  28,148 rps reaching a provider
```

So the system absorbs **92,593 rps at the edge** and only **28,148 rps** costs
money. That is the product in one line.

---

## 2. How we got 488 gateway pods

This is the only place a real measurement enters.

```
92,593 rps peak ÷ 271.3 rps per pod (MEASURED)   = 341 pods
341 ÷ 0.70 target utilisation                    = 488 pods
```

**Step 1 — the measurement.** One pod, limited to 1000m CPU and 384 MiB, served
**271.3 rps** of a realistic traffic mix at a 69.6% hit ratio, p50 198 ms. That
came from a k6 load test against a real pod in a real cluster: a concurrency
ladder was walked and the best sustainable point kept. It is written `≥271` because
that step was still gaining throughput when the ladder ran out, so it is a lower
bound.

**Step 2 — divide.** 92,593 ÷ 271.3 = 341 pods to exactly meet peak.

**Step 3 — why ÷ 0.70 and not just 341.** Running pods at 100% of their measured
capacity leaves nothing for: a pod restarting during a deploy, a node failing, a
burst above the modelled peak, or the latency rise that always accompanies full
utilisation. Targeting 70% means 488 pods, and 147 of them are headroom.

> **The honest caveat.** 271.3 was measured with **Tier 2 switched off**, which is
> the shipped default. With semantic caching actually serving, embedding is
> CPU-bound and per-pod throughput falls by one to two orders of magnitude. So
> 488 pods describes a Tier-1-only fleet. The fix is architectural — move
> inference off the request path — not more pods.

**And what is not proven.** This assumes 488 pods each do 271 rps, i.e. that pods
do not interfere with each other. The Universal Scalability Law calls that
coefficient *kappa*. It was never measured, because the laptop's load generator
saturated before three pods did. If kappa were 0.01, this table is wrong by two
orders of magnitude.

---

## 3. The storage calculation, step by step

### Step 1 — how many distinct things are there?

```
2,000,000,000 requests/day
× 10% distinct prompts (ASSUMED)   =  200,000,000 distinct entries/day
```

**This is the assumption that drives everything.** If only 10% of prompts are
unique, you store 2×10⁸ entries. Not 2×10⁹ — the other 90% are repeats, and a
repeat does not create a new entry, it *hits* an existing one.

Note it is the same 10% that produces the 70% hit rate: most traffic is repeats.

### Step 2 — Redis (Tier 1)

```
200,000,000 entries × 2 KiB each (ASSUMED)  =  ~381 GiB
```

Why 2 KiB: one entry holds a model answer plus a little metadata. A typical
answer is a few hundred to a couple of thousand characters.

```
381 GiB ÷ 48 GiB usable per node  =  8 → 9 primaries
```

**Why only 48 GiB usable from a 64 GiB node:** Redis needs room above your data
for its own overhead, for memory fragmentation, and for the fork it performs when
saving a snapshot — briefly doubling some pages. Filling a Redis node to 100% is
how you get an out-of-memory kill at 3am. ~75% is the practical ceiling.

```
9 primaries + 9 replicas  =  18 Redis nodes
```

One replica each, for failover.

**Then the throughput check**, which is separate from the capacity check:

```
92,593 lookups/s + 28,148 writes/s  =  ~121,000 ops/s
one Redis node does roughly          ~100,000 ops/s
121,000 ÷ 9 nodes                   =  ~13,500 ops/s per node   ✓ comfortable
```

So 9 nodes are needed for **memory**, and they are nowhere near their
**operations** limit. Capacity binds first.

### Step 3 — PostgreSQL (Tier 2)

Two separate numbers, and the second one is the one that matters.

```
Row data:
  200,000,000 rows × 4 KiB each (ASSUMED)   =  ~763 GiB

Index (HNSW):
  200,000,000 rows × 2.3 KiB each (ASSUMED) =  ~439 GiB
```

**Why a row is 4 KiB:** the embedding alone is 384 floats × 4 bytes = **1,536
bytes**, before the prompt text and the answer.

**Why the index is 2.3 KiB per row:** HNSW is a graph. Each row becomes a node
with links to neighbours — `M=16` means roughly 16 links per node, each a pointer,
at several levels.

**Why the index figure drives everything:**

```
439 GiB ÷ ~200 GiB usable RAM per node  =  2.2 → 3 shards minimum
```

**The HNSW index must be in RAM.** A vector search walks the graph, jumping
between distant nodes. If the graph is on disk, every jump is a disk seek, and
Tier-2 latency goes from ~100 ms to seconds — at which point the cache is slower
than the model it exists to avoid.

The row data (763 GiB) can live on disk. Only the index must be resident. **So
the shard count is set by index size, not data size.**

### Step 4 — why 8 shards and not 3

Three reasons, in order of importance:

1. **3 is the bare minimum with zero headroom.** Growth, an uneven tenant
   distribution, or a wrong assumption and you are re-sharding in production.
2. **8 matches the existing `PARTITION BY HASH (tenant_id) × 8`.** The table is
   already split 8 ways inside each database. Choosing 8 shards means the
   partition scheme and the shard scheme line up — one concept to reason about,
   not two.
3. **Powers of two split cleanly.** Going 8 → 16 splits each shard in half,
   which is far simpler than any split from 3.

### Step 5 — why × 3 (1 primary + 2 replicas)

```
8 shards × (1 primary + 2 replicas)  =  24 PostgreSQL instances
```

- **The primary** takes all writes.
- **Replica 1** serves reads, which is most of the Tier-2 traffic.
- **Replica 2** is the one that lets you survive losing a node *while* a replica
  is already down or lagging — and gives you somewhere to run backups without
  slowing the primary.

Two replicas rather than one is the difference between "a node failure is a
non-event" and "a node failure makes you nervous".

```
28,148 writes/s ÷ 8 shards  =  ~3,500 writes/s per shard   ✓ comfortable
```

### Step 6 — synchronous or asynchronous replication?

**Asynchronous.** And this is a deliberate decision with a written argument:
[ADR-0007](../adr/0007-read-replicas-are-safe.md).

Synchronous replication means the primary waits for a replica to confirm every
write. Safer, but every write pays the network round trip.

Asynchronous means the replica is slightly behind — "replica lag". The obvious
objection: a read from a lagging replica might miss recent data.

**Here, that is harmless, and the reason is specific to cached data:**

> A stale replica makes a Tier-2 search *fail to find* an entry that exists. That
> is a **cache miss**. The request falls through to the provider, gets a correct
> answer, and re-caches it.
>
> **Replica lag on this data produces a miss, never a wrong answer.**

A worked bound from the ADR: at 100 ms of lag and 23,000 rps, about **2,300
requests** fall in the window — they cost a little money and return correct
answers.

Compare that with replica lag on an account balance, where a stale read is a
*wrong number shown to a user*. Same mechanism, completely different risk.

**Two things are explicitly excluded from replicas:**

- **API key lookups.** A revoked key must stop working *immediately*. Lag here
  means a cancelled credential still works — a security hole, not a cost.
- **Writes**, always to the primary.

And the ADR attaches a condition to its own permission: **monitoring must alarm
on lag**, because unbounded lag silently degrades the hit rate, which surfaces
weeks later as a larger bill. `ReplicaLagMonitor` probes
`pg_last_xact_replay_timestamp()` every 30 s. One alert fires when the lag metric
**stops being reported at all**, because a missing series and a healthy replica
otherwise look identical.

---

## 3b. The Redis cache entry, decoded piece by piece

The example looked like line noise. Here is every part of it.

### The key

```
{tenant:acme}:prompt:9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
└─────┬─────┘ └──┬──┘ └──────────────────────────┬──────────────────────────────────┘
      │          │                               │
   WHOSE       WHAT                          WHICH ONE
```

**Part 1 — `{tenant:acme}`. Whose data this is.**

Redis is one giant key-value store shared by every tenant, so the tenant name
goes in the key. That is what stops acme reading globex's answers: they cannot
collide, because their keys differ.

**The curly braces are not punctuation. They are a Redis Cluster instruction.**

Redis Cluster spreads keys over many nodes by hashing the key. Normally it hashes
the *whole* key — so `{tenant:acme}:prompt:aaa` and `{tenant:acme}:prompt:bbb`
would land on different nodes. The braces say: *hash only what is inside me.* So
every key containing `{tenant:acme}` goes to the **same node**.

Why that is needed: the rate limiter runs a small Lua script that touches several
of a tenant's keys at once, and **Redis refuses to run a script spanning keys on
different nodes.** Without the braces the limiter works perfectly on a single
Redis and breaks the moment you run a cluster — the worst kind of bug, invisible
in development.

**Part 2 — `:prompt:`. What kind of thing this is.**

A namespace. The same tenant also has `{tenant:acme}:bucket` for the rate limiter
and `{tenant:acme}:quota` for the budget. The label keeps them apart and makes
`--scan --pattern '{tenant:acme}:prompt:*'` possible.

**Part 3 — `9f86d081…`. Which prompt.**

The SHA-256 hash of the normalised prompt plus the model name. 64 hex characters.

Why a hash rather than the prompt itself:

- a prompt can be thousands of characters; a hash is always 64
- prompts contain spaces, newlines, quotes and colons, which would break the key
  format
- the lookup is only ever "do I have this exact text?", and a hash answers that
  perfectly

The same prompt always produces the same hash, so the same question always finds
its own entry.

### The value

```json
{"v":1,"c":"Paris is the capital of France.","m":"gpt-4o-mini","pt":12,"ct":8,"p":"openai","ts":1763049600000}
```

| field | means | why it is stored |
|---|---|---|
| `v` | **v**ersion = 1 | which format this entry is in |
| `c` | **c**ontent | the answer. This is the thing being cached |
| `m` | **m**odel = `gpt-4o-mini` | so a gpt-4o answer is never served to a request for a different model |
| `pt` | **p**rompt **t**okens = 12 | for billing and the usage report |
| `ct` | **c**ompletion **t**okens = 8 | same |
| `p` | **p**rovider = `openai` | which vendor produced it, for debugging |
| `ts` | **t**ime**s**tamp = 1763049600000 | when the answer was **originally** generated |

**Why `ts` is the original creation time, not the serve time:** it is what makes
staleness visible. The response tells the caller `content_age_seconds`, so a
client can see it is being handed a two-hour-old answer and decide whether it
cares.

**Why the names are one or two letters.** At 2×10⁸ entries, writing `"content"`
instead of `"c"` costs 6 extra bytes per entry:

```
6 bytes × 200,000,000 entries = 1.2 GB of RAM, for nothing
```

Across all seven fields the full names would add roughly 8 GB — and this is data
that must live in memory. The short names are a deliberate purchase of about 8 GB.

**Why `v` earns its place.** If the format changes — a field added, a type
changed — an old entry would deserialise wrongly or throw. Instead the reader
checks `v`, and an entry it does not recognise is treated as a **cache miss**. The
request goes upstream, gets a fresh answer, and overwrites the old entry in the
new format. So the format can change with **no coordinated cache flush and no
downtime**: old entries quietly age out.

### TTL: 1 hour

Redis deletes the key automatically an hour after it is written. Three reasons:

1. **Memory is bounded.** Without expiry the working set grows forever.
2. **Answers go stale.** An answer about "current pricing" should not be served
   for a month.
3. **It is how the 381 GiB figure is computed** — the working set is "one day of
   distinct prompts", not "everything ever asked".

Tier 2 keeps entries for **24 hours** instead, because a vector search is still
far cheaper than a provider call, so it is worth holding on longer.

### See a real one

```bash
kubectl exec deploy/redis -- redis-cli --scan --pattern '{tenant:acme}*' | head -5
kubectl exec deploy/redis -- redis-cli GET '{tenant:acme}:prompt:<paste a hash>'
kubectl exec deploy/redis -- redis-cli TTL '{tenant:acme}:prompt:<paste a hash>'
```

`TTL` returns the seconds remaining — you can watch it count down from 3600.

---

## 4. How PostgreSQL sharding actually works

This was the confusing part, so here it is concretely.

### Many tenants per shard — not one shard each

```
8 shards, and every tenant is assigned to exactly ONE of them.

shard 0 ──► acme, zebra-corp, foo-industries, ... (thousands of tenants)
shard 1 ──► globex, initech, ...                  (thousands of tenants)
shard 2 ──► umbrella, ...                         (thousands of tenants)
   ...
shard 7 ──► ...
```

One tenant's data lives entirely on one shard. One shard holds many tenants.

With a million tenants and 8 shards, that is ~125,000 tenants per shard.

### There is no "range" — it is a hash ring

You asked what the range of each shard is. There isn't one, and that is
deliberate.

A **range** scheme would be: tenants A–C on shard 0, D–F on shard 1, and so on.
That is simple and it fails badly — tenant names are not evenly distributed, and
adding a shard means redrawing every boundary.

Instead a **consistent hash ring**:

```
hash = MurmurHash3("acme")        -> some number
                                     pick the next shard clockwise on the ring
```

- **MurmurHash3** spreads names evenly regardless of what they look like.
- **150 virtual nodes per shard.** Each shard is placed at 150 points around the
  ring rather than one. Without this, 8 shards means 8 points and the gaps between
  them are wildly uneven — one shard ends up with far more tenants than another.
  150 points each smooths it out.
- **Adding a shard moves only ~1/9th of tenants.** With a range scheme or a plain
  `hash % 8`, changing the shard count rehashes *almost everything*. That is the
  whole reason the ring exists.

### Two levels, which is easy to confuse

| level | mechanism | what it splits |
|---|---|---|
| **sharding** | consistent hash ring on `tenantId` | which **database server** holds a tenant |
| **partitioning** | `PARTITION BY HASH (tenant_id) × 8` | which **physical table** inside that server |

So one tenant's rows are on one server, in one partition of `prompt_cache` on
that server. Both levels use the tenant, which is why a Tier-2 search can never
cross a tenant: the planner prunes to one partition, and the router only ever
contacts one shard.

```sql
-- see the spread yourself
SELECT tableoid::regclass AS partition, count(*)
  FROM prompt_cache GROUP BY 1 ORDER BY 1;
```

### Why the shard key is `tenantId` — ADR-0006

The alternative is sharding by prompt hash, which would spread load more evenly.
It is rejected, and the reason is the heart of the design:

**A semantic search cannot be routed.** If you shard by prompt hash, similar
prompts land on unrelated shards — because similar text does not produce similar
hashes. So finding the nearest vector would require querying **every** shard and
merging the results. Eight searches instead of one.

Sharding by tenant means a tenant's entire history is on one shard, so a nearest-
neighbour search is **one query to one shard**.

**The cost:** one enormous tenant cannot be split. That is the whale-tenant gap.

---

## 5. Kafka: 9 brokers, 213k events/s, and the partition key

### Where 213k events/s comes from

Each request produces **more than one** event:

```
usage event        1 per request        (always)
audit event        1 per request        (always)
cache-write event  0.304 per request    (only on a miss: 1 − 0.696)
                   ─────────────────
                   2.304 events per request

2e9 requests × 2.304            = 4.6e9 events/day
4.6e9 ÷ 86,400 × 4 (peak)       = ~213,000 events/s at peak
```

### Where 9 brokers comes from

```
213,000 events/s × 1 KiB each (ASSUMED)  =  208 MiB/s arriving
208 MiB/s × 3 (replication factor 3)     =  625 MiB/s actually written
625 ÷ ~100 MiB/s sustained per broker    =  6.25 → 7 brokers
                                            → 9 for headroom
```

**Why × 3:** replication factor 3 means every message is stored on three brokers.
So the cluster physically writes three times what arrives. This is the step people
forget, and it triples your disk requirement.

**Why ~100 MiB/s per broker:** that is a realistic sustained sequential-write
figure for a commodity node once replication traffic and page-cache pressure are
accounted for. Disks can burst higher.

```
208 MiB/s × 86,400 s × 3 copies  =  ~51 TiB at 24-hour retention
```

### Where the partition counts come from

```
usage, audit:   92,593 rps ÷ ~10,000 per partition  = 10 → 32 partitions
cache-write:    28,148 rps ÷ ~10,000 per partition  =  3 → 16 partitions
```

Rounded up heavily to powers of two, because **the partition count caps your
consumer pods forever** and increasing it later is disruptive. 32 is cheap
insurance; discovering you need 40 when you have 32 is not.

### Why the partition key is `tenantId`

Kafka hashes the key to choose a partition. Same key → same partition, always.

```
key "acme"   → hash → partition 7   (every acme event, forever)
key "globex" → hash → partition 2   (every globex event, forever)
```

**Two things this buys:**

**1. Ordering where it matters.** Kafka only guarantees order *within* a
partition. Because all of one tenant's events land on one partition, they are
processed in the order they happened. That matters for billing: "used 100 tokens"
then "budget increased" must not be seen the other way round. Across tenants
there is no ordering, and nothing needs it.

**2. Each consumer owns whole tenants.** A metering pod handling partition 7
handles *all* of acme. It can safely keep per-tenant state in memory — running
totals for an hourly rollup — because no other pod is processing acme
simultaneously.

If the key were random, both properties vanish: a tenant's events scatter across
32 partitions, arrive out of order, and are processed by 32 different pods at
once.

**The cost:** a huge tenant creates a hot partition, and a partition cannot be
split. Same trade as the whale shard.

---

## 6. Why MetalLB

**The problem it solves:** in Kubernetes you can declare
`Service type: LoadBalancer`, which asks for an external IP address. On AWS, GCP
or Azure, the cloud controller hears that and provisions a real load balancer.

**On bare metal, k3d, or a laptop, nothing is listening.** The service sits in
`<pending>` forever. There is no external IP, and nothing outside can reach your
cluster.

**MetalLB is the missing piece.** It watches for those requests, assigns an IP
from a pool you configure, and announces it on the local network (ARP, or BGP in
larger setups).

| | |
|---|---|
| Why MetalLB specifically | it is the de-facto standard for on-prem Kubernetes, and it is **free and open-source** — a hard project constraint |
| Why L4 and not L7 | its only job is getting packets to an IP. HTTP-aware routing is ingress-nginx's job, one layer up |
| In a cloud | you would **delete it**. The cloud's own load balancer does this |

So the two layers are: **MetalLB gets traffic into the cluster; ingress-nginx
decides which pod gets it.**

---

## 7. The LLM provider: which one, and when it is called

### Which providers exist

| provider | what it is |
|---|---|
| `MockLlmProvider` | **the default.** Returns a deterministic answer after a configured delay |
| `OpenAiCompatibleProvider` | real HTTP calls to any OpenAI-shaped API — OpenAI, Together, Groq, vLLM, LM Studio |
| `OllamaProvider` | a local model via Ollama |
| `ResilientLlmProvider` | a wrapper, not a provider — adds retry and circuit-breaking around any of the above |

Default config:

```yaml
providers:
  primary: mock
  fallback: []
```

### Why the mock is the default — ADR-0011

Not laziness. **A benchmark that includes a real model measures the model.**

If a local Ollama model takes 2 seconds per request, every load test result is
dominated by those 2 seconds, and you learn nothing about the gateway. The mock
answers in a configured 40 ms, so what you measure is the gateway's own overhead:
its cache, its routing, its queueing.

It also makes tests deterministic — the same prompt always gives the same answer,
so a cache hit is verifiable.

> **The honest consequence**, which I got wrong earlier: because the mock reports
> its *configured* delay, every "upstream 40 ms" figure from this system is a
> constant someone chose. It is not a measurement of anything.

### When the provider is called

Only when **both** cache tiers miss — step 11 of the request flow:

```
Tier 1 miss  AND  Tier 2 miss  →  call the provider
```

At a 69.6% hit ratio that is roughly 3 requests in 10.

It is also **skipped entirely** when the request is not cacheable: streaming
requests bypass both tiers and always go upstream.

### When it falls back

`ProviderRegistry` walks an **ordered** chain: primary first, then each fallback
in turn if the one before it fails or times out.

**Why ordered rather than load-balanced** — the subtle part. Different vendors
answer the same question differently. If you spread traffic across them, identical
questions get different answers depending on who happened to serve it — and your
cache then stores whichever arrived first, making the answer to a question depend
on routing luck.

A strict primary keeps answers consistent. **Fallbacks are for failure, not for
sharing load.**

---

## 8. The failure scenarios, in full

### gateway dies

**What breaks:** everything. It is the only service on the request path.

**What happens in practice:** you do not lose the service, you lose a pod. 488
pods spread across ~38 nodes, so one pod dying removes 0.2% of capacity, and
`ingress-nginx` stops sending it traffic as soon as its readiness probe fails.

**What the protections are for:**

- **HPA** adds pods as request rate rises
- **PodDisruptionBudget** stops a node drain or rolling deploy from taking too
  many at once — without it, `kubectl drain` on three nodes could remove a large
  slice simultaneously
- **Readiness vs liveness**: readiness failing removes a pod from the load
  balancer; liveness failing restarts it. Confusing the two restarts pods that
  were merely busy
- **A startup probe** gates the other two, because the JVM plus ONNX model load
  takes ~16 s and liveness would otherwise kill the pod before it ever became
  ready — a crash loop that reads as an application bug

### metering or audit dies

**What breaks:** nothing visible. Requests are unaffected.

**What happens to the data:** the gateway keeps publishing to Kafka. Kafka
**keeps** the messages (24-hour retention here). When metering returns it resumes
from its last committed offset — `auto.offset.reset=earliest` and manual commits
mean it picks up exactly where it stopped. **Nothing is lost.**

**What you lose while it is down:** freshness. `tenant_usage_rollup` stops
updating, so the dashboard shows stale numbers and invoices are delayed. The data
arrives later, complete.

**The real risk is time**, not loss: if it is down longer than Kafka's retention,
unread messages are deleted. 24 hours is the deadline.

**Why duplicates do not hurt on recovery:** if metering crashed between writing a
row and committing its offset, it re-reads that record on restart.
`ON CONFLICT DO NOTHING` makes the second write a no-op. 18 duplicate deliveries
in testing produced zero double-billing.

### indexer dies

**What breaks:** nothing immediately, and that is what makes it dangerous.

**What degrades, slowly:**

1. `gateway.cache-write.v1` stops being consumed, so **no new vectors enter Tier
   2**
2. Existing Tier-2 entries keep being served, so the hit ratio does not collapse
   — it **drifts down** as the cached population ages out
3. Every drifting percentage point is real money at the provider
4. **Expired rows also stop being evicted**, so `prompt_cache` grows without
   bound — and the thing that must stay in RAM is the HNSW index

**Why this is the worst failure mode to have:** there is no error, no alert from
the request path, and no user complaint. The only symptoms are a slowly falling
hit ratio and slowly rising storage. Both are visible only on a dashboard someone
has to look at.

### control-plane dies

**What breaks:** administrative operations. You cannot create a tenant, issue a
key, revoke a key, or change a budget.

**What keeps working:** all existing traffic. The gateway resolves API keys and
quotas from **Redis**, not from the control plane. It does not call it at all —
ADR-0010.

**The security consideration:** if you need to revoke a key *right now* and the
control plane is down, you cannot do it through the API. The fallback is deleting
the key's hash from Redis directly. Worth knowing before you need it.

**Why 3 pods is enough:** it scales with administrative actions, not request
rate. Creating a tenant happens once per customer, ever. The 3 are for
availability, not throughput.

### Redis dies

**What breaks:** two things degrade, neither fails.

**Tier 1 becomes a permanent miss.** Every lookup errors, is counted as a miss,
and falls through to Tier 2 and then upstream. Latency goes from 3 ms to
hundreds; cost rises to whatever the provider charges for the ~70% of traffic
that was free.

**The rate limiter fails open.** `fail-open: true`: if the limiter cannot read
its buckets, requests are **allowed**. The reasoning is that a cache outage
should not take the product down.

**Why this is the sharpest edge in the system.** Failing open means that during a
Redis outage you have **no rate limiting at all**. A tenant can send unlimited
traffic, straight to the provider, at full cost — exactly when your costs are
already elevated because Tier 1 is gone. The multi-tenant isolation guarantee is
silently suspended.

It is the right default, but it needs the alert that exists for it:
`gateway_ratelimit_degraded_total`.

**There is also a feedback loop worth knowing.** Redis has a 500 ms timeout. Under
heavy load, lookups start timing out; each timeout becomes an upstream call; the
extra work deepens the queue; more lookups time out. Throughput **collapses**
rather than plateauing, and the only visible symptom is the hit ratio falling. It
was measured: 347 rps at 64 VUs, 55 rps at 90.

### Kafka dies

**What breaks:** nothing in the request path. Requests are completely unaffected.

**What you lose, permanently:** every event published during the outage.

The gateway's publish is fire-and-forget: it starts the send, does not wait, and
three layers of error handling stop a failure reaching the request. There is **no
queue and no retry**. A `dropped` counter increments and the message is gone.

So an hour of broken Kafka is an hour of usage records you will never have. You
served those requests and cannot bill for them.

**Why this is accepted:**

| data lost | what it costs |
|---|---|
| usage event | a fraction of a cent, and the error is **under**-billing — the customer is never overcharged |
| audit event | one log line |
| cache-write event | one entry unindexed; the next identical prompt re-caches it. **Self-healing** |

And where loss is *not* acceptable — tenant provisioning, key revocation, billing
state — the control plane uses a **transactional outbox**: the state change and
the message are written in one database transaction, so the message cannot be
lost. ADR-0008.

**Note the asymmetry:** metering being down loses nothing, because Kafka buffers.
Kafka being down loses everything published during the window, because the
gateway will not wait for it.

### the provider dies

**What breaks:** requests that miss both cache tiers — about 3 in 10.

**The sequence:** the primary times out or errors → each fallback is tried in
order → if all fail, the request fails with a clean `UpstreamTimeout` or provider
error.

**What still works:** every cached request. At a 69.6% hit ratio, roughly 7 in 10
requests are served normally throughout a total provider outage.

**That is the strongest resilience property the gateway has**, and it is a
side-effect of the cache rather than a designed feature: a provider outage
degrades you to "answers questions we have seen before" instead of taking you
down.

### one whale tenant — the known gap

**The situation:** one tenant is 1,000× larger than the rest.

**What happens:**

1. The ring assigns them to one shard, like everyone else
2. All their vectors, all their rows, all their searches hit **that one shard**
3. That shard's HNSW index may not fit in its node's RAM, while the other seven
   sit half empty
4. Their Kafka events all land on **one partition**, so one consumer pod does all
   their work while others idle

**Why it cannot simply be fixed:** the shard key must be the tenant, or a semantic
search has to query every shard (section 4).

**The designed-but-unbuilt fix:** sub-shard the whale on `tenant#bucket`, so one
tenant spreads across several buckets. `ShardRouter.route(String)` already accepts
that key shape; nothing computes the buckets.

**And it is not free.** Splitting by prompt hash breaks Tier 2 for that tenant,
because similar prompts hash to unrelated buckets — so a whale's reads must
**fan out across all its buckets** and merge. That fan-out is accepted for whales
and refused for everyone else, which is exactly why it is a separate mechanism
rather than the default.

### traffic doubles overnight

**What scales by itself:** the gateway. The HPA watches request rate and adds
pods, 2 → 6 in dev, toward 488 in production. Stateless pods are the easy part.

**What does not:**

- **Redis** — adding a node means moving slots and the keys in them
- **PostgreSQL** — adding a shard means moving tenants' data
- **Kafka partitions** — can be increased, but it is disruptive and never
  decreased

**Why the ring makes the PostgreSQL case survivable:** 150 virtual nodes per
shard means adding a 9th shard moves only about 1/9th of tenants. With a plain
`hash % 8`, going to 9 rehashes almost every tenant and you move nearly all the
data.

**What actually binds first:** not pod count. The HNSW index staying in RAM. You
can double the gateway in minutes and discover that Tier 2 has become the
bottleneck because its index no longer fits.

**The practical order of operations:** watch the index size per shard, add shards
*before* you need them, and never let the autoscaler convince you the data layer
scaled too.

---

## 9. The one-page summary of what is actually known

| figure | status |
|---|---|
| 271.3 rps per pod | **measured (M)** |
| 69.6% hit ratio on a test mix | **measured (M)** |
| Tier-1 hit at 2–5 ms | **measured (M)** |
| everything else on this page | **calculated from assumptions** |
| kappa — that pods do not interfere | **never measured** |
| 2 KiB per Redis entry, 4 KiB per row, 10% distinct prompts | **assumed** |

The assumptions in that last row are the weakest part of the whole plan. Halve
"10% distinct prompts" and you halve both datastores. Nobody has measured it
against real traffic, because there is no real traffic yet.

---

**Previous:** [B5 — the HLD](B5-hld.md).
**Next:** [B6 — the LLD](B6-lld.md).
