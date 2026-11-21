# B5b — Real servers, and how each mechanism actually works

The lab pod was 1000m CPU / 384 MiB, which nobody runs in production. This
recalculates the fleet on a real cloud instance, then explains each mechanism
with the actual file and line you can go and read.

---

## 1. Recalculated on a real server

### Why the lab number cannot be used directly

`271.3 rps` was measured on a pod limited to **1000m CPU / 384 MiB**, on a
6-vCPU laptop VM, through k3d. Three things make that unlike production:

| | the lab | a real cluster |
|---|---|---|
| CPU | 1 shared vCPU, throttled by CFS quota | a dedicated core on a server CPU |
| memory | 384 MiB — JVM heap only just fits | gigabytes, no pressure |
| neighbours | Redis, Postgres, Prometheus on the same 6 vCPUs | separate nodes |
| network | Docker bridge on one host | real NICs, sub-millisecond |

So 271 rps/pod is a **floor**, measured under adverse conditions.

### Picking a real instance

Take **AWS `m7i.4xlarge`**: 16 vCPU, 64 GiB RAM — an ordinary general-purpose
choice, and the node shape the scaling model already assumes.

```
Node:           16 vCPU, 64 GiB
Kubernetes overhead (kubelet, DaemonSets, system reserve): ~20%
Allocatable:    ~12.8 vCPU, ~56 GiB
```

### Production pod size, and the honest uncertainty

```
Pod:  2000m CPU, 2 GiB
```

2 vCPU because Tier 2's embedding needs ≥2 threads (the JVM reads the CPU
**limit**, so 1000m means `availableProcessors()==1` and the inference pool
collapses to one thread). 2 GiB because the ONNX session is native memory
outside the heap.

Now the uncertainty that cannot be reasoned away:

```
If throughput scales with cores:   2 × 271  = 542 rps/pod   (OPTIMISTIC)
If the lab was network/IO-bound:        ~271 rps/pod        (PESSIMISTIC)
A defensible middle:                    ~450 rps/pod        (ASSUMED)
```

**It is not linear in cores** — a second core helps the JIT and the event loops,
but Netty's accept path and Redis round trips do not halve. 450 is a 1.66×
improvement for 2× the CPU, which is a normal shape for a reactive service.

**This is an assumption, not a measurement.** The only way to replace it is to
run the k6 ladder against a 2000m pod on a real node.

### The fleet, recalculated

```
GATEWAY
  92,593 rps peak ÷ 450 rps/pod (ASSUMED)     = 206 pods
  206 ÷ 0.70 target utilisation               = 294 pods
  294 × 2 vCPU                                = 588 vCPU
  588 ÷ 12.8 allocatable vCPU per node        = 46 nodes
  (memory check: 294 × 2 GiB = 588 GiB ÷ 56 = 11 nodes — CPU binds first)
```

**294 pods on 46 m7i.4xlarge nodes**, against 488 pods on ~38 nodes in the
lab-based figure. Fewer, larger pods; slightly more nodes, because each pod now
claims 2 vCPU instead of 1.

```
REDIS — r7g.4xlarge (16 vCPU, 128 GiB), memory-optimised
  381 GiB working set ÷ 96 GiB usable per node   = 4 primaries
  (128 GiB × 75% = 96 usable; the rest is fragmentation + fork headroom)
  4 primaries + 4 replicas                       = 8 nodes
  121,000 ops/s ÷ 4                              = ~30k ops/s per node  ✓

POSTGRESQL — r7g.8xlarge (32 vCPU, 256 GiB), memory-optimised
  439 GiB HNSW index ÷ 192 GiB usable RAM        = 3 shards minimum
  chosen: 4 shards (matches a 4-way partition, splits cleanly to 8)
  4 shards × (1 primary + 2 replicas)            = 12 instances
  28,148 writes/s ÷ 4 shards                     = ~7,000 writes/s/shard  ✓
  row data 763 GiB ÷ 4 shards                    = ~191 GiB disk each

KAFKA — m7i.2xlarge (8 vCPU, 32 GiB) + fast disks
  625 MiB/s written (208 × RF3) ÷ 100 MiB/s      = 7 → 9 brokers (unchanged:
                                                   disk throughput, not CPU)

CONSUMERS — 1000m / 1 GiB each, no inference on these paths
  metering  92,593 ÷ 5,000 per pod               = 19 pods (cap 32)
  audit     92,593 ÷ 5,000                       = 19 pods (cap 32)
  indexer   28,148 ÷ 5,000                       = 6 pods  (cap 16)
  control-plane                                  = 3 pods
```

### Totals, both ways

| | lab-based (1000m pods) | real-server (2000m pods) |
|---|---|---|
| gateway pods | 488 | **294** |
| gateway nodes | ~38 × 16 vCPU | **46 × m7i.4xlarge** |
| Redis nodes | 18 × 64 GiB | **8 × r7g.4xlarge** |
| PostgreSQL instances | 24 (8 shards × 3) | **12 (4 shards × 3)** |
| Kafka brokers | 9 | **9** |
| consumer + control pods | 47 | **47** |
| **total pods** | ~535 | **~341** |

Bigger machines mean **fewer, larger units**. The Redis count halves because a
128 GiB node holds twice what a 64 GiB node does. The PostgreSQL shard count
halves for the same reason. Kafka does not move at all, because it is bound by
**disk write throughput**, which a bigger CPU does not change.

> **This table is more uncertain than the first one**, and it is worth being
> clear about why: the lab-based figure rests on one real measurement plus
> arithmetic. This one rests on that measurement **plus an assumed 1.66× scaling
> from 1 to 2 vCPU**. It is more realistic and less proven.

---

## 2. Where "2 KiB per entry" comes from

It was marked **(ASSUMED)** and deserves the arithmetic.

A Tier-1 value is the JSON from
[B5a §3b](B5a-numbers-explained.md). Measure the example:

```json
{"v":1,"c":"Paris is the capital of France.","m":"gpt-4o-mini","pt":12,"ct":8,"p":"openai","ts":1763049600000}
```

```
JSON overhead (braces, quotes, colons, commas, field names)   ~60 bytes
model name, provider, timestamps, token counts                ~45 bytes
the answer itself ("Paris is the capital of France.")           31 bytes
                                                              ───────────
this entry                                                    ~136 bytes
```

So *this* entry is 136 bytes, not 2 KiB. The difference is the answer length:

| answer | entry size |
|---|---|
| one line ("Paris is the capital of France.") | ~140 B |
| a short paragraph (~400 chars) | ~0.5 KiB |
| a full explanation (~1,500 chars) | **~1.6 KiB** |
| a long structured answer (~3,000 chars) | ~3.1 KiB |

**2 KiB assumes the average answer is roughly 1,900 characters** — a few solid
paragraphs. That is a reasonable guess for an assistant feature and a bad one
for short factual lookups.

Then add what Redis itself costs per key:

```
the key string           ~85 bytes   ({tenant:...}:prompt: + 64 hex chars)
Redis object overhead    ~50-100 bytes per key (internal structures, expiry)
```

So ~2 KiB of value plus ~0.2 KiB of overhead, rounded to 2 KiB.

**Why this matters more than any other assumption:**

```
at 1 KiB/entry:  2e8 × 1 KiB = 190 GiB  →  2 Redis primaries
at 2 KiB/entry:  2e8 × 2 KiB = 381 GiB  →  4 Redis primaries
at 4 KiB/entry:  2e8 × 4 KiB = 763 GiB  →  8 Redis primaries
```

**The entire Redis fleet is linear in this one guess**, and it is measurable in
about a minute against real traffic:

```bash
kubectl exec deploy/redis -- redis-cli INFO memory | grep used_memory:
kubectl exec deploy/redis -- redis-cli DBSIZE
# used_memory ÷ DBSIZE = the real average, and then this table is arithmetic
```

---

## 3. HNSW: what it is, and why it must be in RAM

### The problem it solves

You have 200 million vectors of 384 numbers each. A query arrives as another 384
numbers. Find the closest one.

The obvious way is to compare against all 200 million. At ~0.4 microseconds per
comparison that is **80 seconds per query**. Useless.

### What HNSW does

**H**ierarchical **N**avigable **S**mall **W**orld. It is a **graph**, not a tree
or a sorted list.

Every stored vector becomes a **node**. Each node keeps links to roughly `M = 16`
of its nearest neighbours. Then the graph is built in **layers**: the top layer
has very few nodes with very long links, and each layer down has more nodes with
shorter links.

Searching is a walk:

```
Layer 2 (sparse, long links)    A ─────────────► B          ~3 hops
                                                 │
Layer 1 (denser)                     C ──► D ──► E          ~5 hops
                                                 │
Layer 0 (every node)                      F ─► G ─► H       ~10 hops
                                                     │
                                              nearest match
```

Start at the top, move to whichever neighbour is closer to your query, repeat.
When you cannot improve, drop a layer and continue. **About 20–50 hops instead of
200 million comparisons** — roughly 100,000× less work.

It is *approximate*: it can miss the true nearest neighbour occasionally. For a
cache, "we missed a hit we could have served" is a cache miss, which is
acceptable. For something needing exact answers, it is not.

### Why it must be in RAM

**Each hop goes to an unpredictable memory address.** That is the whole point of
the structure — a long link jumps you across the dataset.

```
in RAM:    ~100 nanoseconds per hop  ×  30 hops  =  ~3 microseconds
on an SSD:  ~100 microseconds per hop × 30 hops  =  ~3 milliseconds
on an SSD under load, with queueing:             =  tens of ms, sometimes 100+
```

A sequential scan of a disk is fast. **Thirty random jumps across a 439 GiB file
is not** — and no amount of read-ahead helps, because the next address is not
knowable until the current comparison finishes.

Add concurrency and it collapses: 28,000 searches/second × 30 random reads each
is 840,000 random IOPS, which is beyond a normal SSD.

**So:** if the index fits in RAM, Tier 2 answers in ~100 ms. If it does not,
Tier 2 takes seconds — and then **the cache is slower than the model it exists to
avoid**, which makes the whole product pointless.

That is why 439 GiB, not the 763 GiB of row data, decides the shard count. Rows
can live on disk; they are read once, sequentially, after the index has already
found them.

---

## 4. Replication, concretely

### The layout

**Each shard is an independent group of 3 machines:**

```
SHARD 0                     SHARD 1                     ... 4 shards total
┌──────────────┐            ┌──────────────┐
│  PRIMARY     │            │  PRIMARY     │     all writes go here
│  (writable)  │            │  (writable)  │
└──────┬───────┘            └──────┬───────┘
       │ streams WAL               │ streams WAL
   ┌───┴────┐                  ┌───┴────┐
   ▼        ▼                  ▼        ▼
┌──────┐ ┌──────┐           ┌──────┐ ┌──────┐
│REPL 1│ │REPL 2│           │REPL 1│ │REPL 2│    reads served here
│(read)│ │(read)│           │(read)│ │(read)│
└──────┘ └──────┘           └──────┘ └──────┘
```

**Yes — every shard has its own primary and its own 2 replicas.** They are not
shared. Shard 0's replicas hold only shard 0's data, which is only the tenants
the ring assigned to shard 0.

### How a write travels

1. Gateway computes `MurmarHash3(tenantId)` → shard 1
2. Writes to **shard 1's primary only**
3. The primary appends to its **WAL** (write-ahead log) and acknowledges the
   client **immediately** — this is the asynchronous part
4. Separately, a background process streams those WAL records to both replicas
5. Each replica replays them and becomes current, typically within milliseconds

**Step 3 is what "asynchronous" means:** the primary does not wait for the
replicas. The client is told "done" before the replicas have the data.

With **synchronous** replication the primary would wait at step 3 until a replica
confirmed — safer, but every write pays a network round trip, and a slow replica
slows every write.

### How a read travels

1. Gateway computes the shard the same way
2. `ShardedDatabase.readClientsFor(tenant)` returns that shard's **read** clients
3. The query goes to a **replica**
4. If the replica's lag is over the threshold, reads **fail over to the primary**
   — stale cache reads are worse than slightly slower ones

### The window where lag matters

```
t=0ms    primary writes the answer for "how do I reset my password"
t=0ms    client is told "done"
t=0-40ms the replicas do not have it yet
t=40ms   replicas are current
```

A request arriving in that 40 ms window and reading a replica **does not find
the entry**. It is a **cache miss** → goes upstream → gets a correct answer →
re-caches it.

**Lag costs money, never correctness.** That is the entire argument of
[ADR-0007](../adr/0007-read-replicas-are-safe.md). At 100 ms lag and 23,000 rps,
roughly 2,300 requests land in the window.

Compare an account balance: a stale read there shows a user the wrong number.
Same mechanism, completely different consequence — which is why the ADR argues
from *what the data is* rather than from replication theory.

### What is excluded from replicas

- **API key lookups** — a revoked key must stop working *now*. Lag means a
  cancelled credential still works. That is a security hole, not a cost.
- **All writes** — primary only.

### Failover

If a primary dies, one replica is promoted. With 2 replicas you can lose a
primary *and* still have a replica serving reads. With 1 replica, a primary
failure means the promoted replica is alone — no read capacity and no redundancy
until a new one is built, which for ~191 GiB takes a while.

### Monitoring is a condition of the design

ADR-0007 grants permission to read replicas **on condition that lag is alarmed
on**. `ReplicaLagMonitor` probes `pg_last_xact_replay_timestamp()` every 30 s and
publishes `gateway_replica_lag_seconds`.

One alert fires when that metric **stops being reported at all**, because a
missing series and a healthy replica look identical on a dashboard.

---

## 5. MetalLB: the actual code

**File: [`scripts/cluster-up.sh`](../../scripts/cluster-up.sh), lines ~203–275.**

There is no MetalLB config in `deploy/` because it is cluster infrastructure,
installed once when the cluster is created, not part of the application chart.

### What the script does, in plain English

**Step 1 — install the chart:**

```bash
helm upgrade --install metallb metallb/metallb \
  --namespace metallb-system --create-namespace \
  --set frrk8s.enabled=false \
  --set speaker.frr.enabled=false
```

`frrk8s` is turned off deliberately. FRR is the backend for **BGP**, which this
cluster does not use. And the comment records something worse: at MetalLB 0.16.0,
leaving it enabled makes Helm **fail to template** — the subchart renders a
ServiceMonitor against a value the parent never passes down, so Helm dies with a
nil-pointer error before installing anything. That reads as a broken Helm
repository rather than as a subchart you did not want.

**Step 2 — work out which IP addresses it may hand out:**

```bash
NETWORK="k3d-${CLUSTER_NAME}"
SUBNET="$(docker network inspect "$NETWORK" -f '{{ (index .IPAM.Config 0).Subnet }}')"
```

It **asks Docker** what subnet the cluster is on, rather than hardcoding
`172.18.x`. Docker picks the subnet when it creates the network, and it differs
between machines and between recreations of the same cluster. Hardcoding would
work on exactly one laptop.

**Step 3 — take addresses from the top of that range:**

```python
last = int(net.broadcast_address)
print(f"{ip_address(last - 60)}-{ip_address(last - 11)}")
```

50 addresses, taken from the **top**. Docker assigns container addresses from the
*bottom*, so the top will not collide with a node. The comment notes it is
computed arithmetically rather than by listing hosts, because a /16 has 65,000
of them and a /8 would hang.

**Step 4 — tell MetalLB about the pool:**

```yaml
kind: IPAddressPool          # "you may hand out these 50 addresses"
spec:
  addresses: [172.19.x.x-172.19.x.y]
---
kind: L2Advertisement        # "announce them by answering ARP"
```

**`L2Advertisement` rather than BGP:** L2 means one elected node answers ARP for
those addresses — "that IP? it's me, send packets here." It needs **no router
configuration**, which is the only option on a laptop. BGP is the production mode
and requires a peer that speaks it.

**Step 5 — retry, because of a race:**

```bash
for attempt in $(seq 1 30); do
  if kubectl apply -f - ...; then break; fi
```

MetalLB's CRDs are guarded by a validating webhook, and that webhook starts
serving slightly *after* the Deployment reports Available. Applying the pool
immediately fails with "connection refused" about one run in three — which looks
random. So it retries rather than sleeping a guessed interval.

---

## 6. The ingress configuration, explained

**File: [`deploy/helm/ai-gateway/values.yaml`](../../deploy/helm/ai-gateway/values.yaml),
the `annotations:` block around line 173.** The template that consumes it is
`templates/ingress.yaml`.

```yaml
annotations:
  nginx.ingress.kubernetes.io/load-balance: "ewma"
  nginx.ingress.kubernetes.io/proxy-buffering: "off"
  nginx.ingress.kubernetes.io/proxy-read-timeout: "120"
  nginx.ingress.kubernetes.io/proxy-send-timeout: "120"
  nginx.ingress.kubernetes.io/proxy-body-size: "2m"
```

Line by line, in plain English:

**`load-balance: "ewma"`** — *"send the next request to whichever pod looks least
busy."*

EWMA is an exponentially weighted moving average: nginx tracks each pod's recent
response times and in-flight count, and picks the best. The alternative,
round-robin, goes strictly in turn.

The comment in the file explains why it matters here: request durations span
**2 ms for a cache hit to 30 s for an upstream miss**. Round-robin distributes
request *count* evenly, which with that spread piles long requests onto whichever
pod is already stuck on one.

**`proxy-buffering: "off"`** — *"pass the response through as it arrives, do not
collect it first."*

Nginx buffers proxied responses by default: it reads the whole thing, then sends
it. For streaming (SSE) that is fatal — the stream is held until the handler
finishes, silently turning streaming into a slow buffered response. The symptom
is "streaming works locally and not in the cluster", with no error anywhere.

**`proxy-read-timeout: 120` / `proxy-send-timeout: 120`** — *"wait up to two
minutes for the gateway."*

Long generations are slow. 120 s is deliberately **longer** than the gateway's
own 30 s limit, so the gateway's timeout is the real deadline and produces a
clean error, rather than nginx cutting the connection first and producing a
confusing 504.

**`proxy-body-size: "2m"`** — *"reject request bodies over 2 MB."*

Prompts are text. 2 MB is generous. The default (1 MB) would reject some
legitimate long-context requests; unlimited invites someone to post a video.

---

## 7. MockLlmProvider: where it is and how it is chosen

**File:
[`gateway/src/main/java/io/vasanth/gateway/provider/MockLlmProvider.java`](../../gateway/src/main/java/io/vasanth/gateway/provider/MockLlmProvider.java).**
Wired up in `config/ProvidersConfig.java` line ~65.

### What it is

A class implementing the same `LlmProvider` interface as the real ones:

```java
public class MockLlmProvider implements LlmProvider {
```

So the gateway cannot tell it apart from OpenAI. It is a **stand-in**, not a
simulator.

### What it does

Given a prompt, it derives a **deterministic** answer from the request text and
returns it after a configured delay:

```java
.delaySubscription(properties.latency())    // default 40 ms
```

`delaySubscription`, not `Thread.sleep` — and the comment says why: a blocking
sleep would occupy an event-loop thread and stall every other request sharing
it, making the mock itself the bottleneck being measured.

### How it is selected

```yaml
providers:
  primary: mock
  fallback: []
```

`ProvidersConfig` builds every available provider as a bean; `ProviderRegistry`
picks the one named `primary` and walks `fallback` in order on failure. Switching
to a real provider is a config change — `primary: openai` plus a base URL and a
key — not a code change.

### Why it is the default — ADR-0011

**A benchmark that includes a real model measures the model.** If a local Ollama
model takes 2 s per request, every load-test number is dominated by those 2 s and
tells you nothing about the gateway's own cost.

It also makes tests deterministic: the same prompt always yields the same answer,
so "was this a cache hit?" is verifiable rather than guessed.

**The consequence to keep straight:** because the mock reports its *configured*
delay as its latency, every "upstream 40 ms" figure from this system is a number
someone chose. It is not a measurement of any provider.

### Also available

- `OpenAiCompatibleProvider` — real HTTP to any OpenAI-shaped API (OpenAI,
  Together, Groq, vLLM, LM Studio)
- `OllamaProvider` — a local model
- `ResilientLlmProvider` — a **wrapper**, not a provider: adds retry and
  circuit-breaking around any of the above
- a second mock, `mock-secondary`, exists so the fallback chain can be
  demonstrated without a vendor account

---

## 8. "Redis stores key-value pairs" — yes, and this is one

Your instinct is right, and the diagram confused the issue by annotating the key.

```
KEY   (one single string, 85 characters)
  {tenant:acme}:prompt:9f86d081884c7d659a2feaa0c55ad015a3bf...

VALUE (one single string, the JSON)
  {"v":1,"c":"Paris is the capital of France.", ... }
```

**One key. One value. A normal pair.**

```
SET  "{tenant:acme}:prompt:9f86..."  '{"v":1,"c":"Paris...","m":"gpt-4o-mini",...}'  EX 3600
GET  "{tenant:acme}:prompt:9f86..."  →  the JSON string
```

The `WHOSE / WHAT / WHICH ONE` annotation was describing how the **key string is
composed**, not three separate things. Redis sees one opaque string. Only two
parts carry meaning to Redis itself:

- the **braces**, which it uses to pick a cluster node
- nothing else — the colons and names are a convention humans chose for
  readability, exactly like naming a file `2026-11-invoice.pdf`

Everything else is just "this is a long unique name for this answer."

```bash
# prove it
kubectl exec deploy/redis -- redis-cli --scan --pattern '{tenant:acme}*' | head -3
kubectl exec deploy/redis -- redis-cli TYPE '{tenant:acme}:prompt:<hash>'   # -> string
```

---

## 9. What metering and indexer are actually for

### metering — turning requests into money

**Reads** `gateway.usage.v1`. **Writes** two tables.

```
one event per request
      │
      ├──► usage_ledger          one row per request. The authoritative record:
      │                          tenant, model, tokens in/out, hit or miss, when
      │
      └──► tenant_usage_rollup   hourly totals per tenant. What the dashboard reads
```

**Why two tables.** The ledger is the truth and the audit trail — "prove this
invoice". But a dashboard asking "requests this month" against 6 billion ledger
rows is a slow scan. The rollup pre-aggregates to one row per tenant per hour, so
a month is 720 rows.

**Why it is a separate service.** It scales with event volume and does database
batch writes; the gateway scales with request rate and must stay on an event
loop. Different work, different scaling, different failure tolerance.

**Why `ON CONFLICT DO NOTHING`.** Kafka is at-least-once, so metering **will**
see a duplicate after a rebalance. Without idempotence that is double-billing.
Tested: 18 duplicate deliveries, zero double-billing.

### indexer — filling Tier 2

**Reads** `gateway.cache-write.v1`. **Writes** vectors into `prompt_cache`. Also
runs TTL eviction.

**Why the gateway does not do it inline:** writing a vector means a PostgreSQL
insert into a table with an HNSW index, and **HNSW inserts are expensive** — the
graph must be traversed and relinked. Doing that inside the request would put
index-maintenance latency on the hot path.

So the gateway publishes an event and replies. The indexer does the write later.

**The vector travels in the event** rather than being recomputed:

```java
// The vector travels with the event so the indexer never recomputes it -
// that would spend the same 6 ms of CPU twice for an identical result.
embedding.toPgVector(),
```

**Eviction matters as much as insertion.** Rows have a 24-hour TTL, but nothing
in PostgreSQL deletes them automatically. The indexer's `EvictionJob` does. Left
unrun, `prompt_cache` grows without bound — and the thing that must stay in RAM
is its index.

**Why it is the most dangerous service to lose:** nothing breaks. Tier 2 just
stops gaining entries, the hit ratio drifts down, storage drifts up, and there is
no error anywhere. See [B5a §8](B5a-numbers-explained.md).

---

## 10. The hash ring, worked through with numbers

**File:
[`common/src/main/java/io/vasanth/gateway/common/shard/ConsistentHashRing.java`](../../common/src/main/java/io/vasanth/gateway/common/shard/ConsistentHashRing.java).**
`DEFAULT_VIRTUAL_NODES = 150`, line 57.

### Step 1 — the ring is a number line that wraps

Imagine every possible hash value laid out in a circle, 0 at the top, going
clockwise up to the maximum.

### Step 2 — place each shard at 150 points

For each shard, hash `"shard-0#0"`, `"shard-0#1"` … `"shard-0#149"` and put the
shard at each resulting position:

```java
NavigableMap<Long, ShardId> built = new TreeMap<>();
// 150 entries per shard, keyed by hash position
```

With 4 shards that is **600 points** on the ring, interleaved unpredictably:

```
position 1,203,847,112  ──► shard 2
position 1,204,009,885  ──► shard 0
position 1,204,781,003  ──► shard 3
position 1,205,112,990  ──► shard 0      <- shard 0 appears many times
position 1,206,004,221  ──► shard 1
   ... 600 of these, all the way round
```

### Step 3 — look up a tenant

```java
Map.Entry<Long, ShardId> entry = ring.ceilingEntry(position);
return entry != null ? entry.getValue() : ring.firstEntry().getValue();
```

In plain English:

1. `position = MurmurHash3("acme")` → say `1,204,500,000`
2. `ceilingEntry` = *"the first ring point at or after this number"* →
   `1,204,781,003` → **shard 3**
3. `firstEntry` is the wrap-around: if your position is past the last point, you
   come round to the first one. **That is what makes it a ring rather than a
   line.**

A `TreeMap` makes step 2 a binary search — about 10 comparisons for 600 points,
microseconds.

### Step 4 — why 150 and not 1

This is the part worth seeing numerically.

**With 1 point per shard**, 4 shards land at 4 random positions. The arc between
consecutive points is each shard's share, and random points produce wildly
unequal arcs:

```
shard 0:  8% of the ring   ──►  8% of tenants
shard 1: 47% of the ring   ──► 47% of tenants     <- nearly half, on one shard
shard 2: 31% of the ring   ──► 31% of tenants
shard 3: 14% of the ring   ──► 14% of tenants
```

Shard 1 gets ~6× shard 0. One machine melts while another idles.

**With 150 points per shard**, each shard owns 150 small scattered arcs. Some are
large, some tiny, and they average out — the law of large numbers:

```
shard 0: 24.8%      shard 1: 25.3%      shard 2: 24.6%      shard 3: 25.3%
```

The javadoc records the rule of thumb: *"150 is the usual choice (Ketama uses
160). Below about 100 the load spread becomes visibly uneven."*

### Step 5 — why this survives adding a shard

Here is the real payoff, and the comparison that makes it obvious.

**With `hash % N`:**

```
4 shards:  hash % 4  →  tenant "acme" (hash 1,204,500,000) → shard 0
5 shards:  hash % 5  →  same tenant                        → shard 0  (lucky)
```

but across all tenants, changing the divisor rehashes **~80% of them**. Nearly
every tenant moves to a different shard, so you move nearly all 763 GiB.

**With the ring:** adding shard 4 inserts 150 new points. A tenant only moves if
one of those new points landed *between* the tenant's hash and the point that
used to serve it.

```
new points added:        150
total points now:        750
fraction of the ring newly owned by shard 4:  150/750 = 20%
tenants that move:       ~20%, and ALL of them move TO shard 4
tenants that stay put:   ~80%, untouched
```

**Nothing moves between two existing shards.** Only the new shard gains, and it
gains from everyone roughly equally.

```
moving 20% of 763 GiB  = ~153 GiB   with the ring
moving 80% of 763 GiB  = ~610 GiB   with hash % N
```

That is the difference between a planned migration and an outage.

### Step 6 — why MurmurHash3 and not SHA-256

| | MurmurHash3 | SHA-256 |
|---|---|---|
| speed | ~1 GB/s, a few ns per key | ~10× slower |
| spread | excellent | excellent |
| cryptographic | **no** | yes |

The ring is called on **every single Tier-2 read and write** — tens of thousands
of times a second. It needs speed and even spread, and it does **not** need to be
cryptographic: nobody gains anything from predicting which shard a tenant lands
on, because they cannot read it without credentials anyway.

SHA-256 is used for the **cache key**, where the requirement is different: two
different prompts must never collide, and that is a correctness guarantee worth
paying for.

---

**Previous:** [B5a — every number explained](B5a-numbers-explained.md).
**Next:** [B6 — the LLD](B6-lld.md).
