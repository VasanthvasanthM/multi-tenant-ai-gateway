# B6b — Which architecture did we use?

The styles and patterns by name, so you can say them out loud. Each one gets a
plain sentence, then where it is in this project.

A pattern name is only useful if you can say what problem it solves. So each
entry is written as: **the problem** → **the name** → **where it is here**.

---

## 1. The overall shape

### API Gateway

**The problem:** many clients need auth, rate limiting, caching and routing
before reaching a backend. Putting that in every client, or every backend, means
copies that drift.

**The name:** API Gateway. One front door that handles the cross-cutting
concerns, so backends do one thing and clients do none of it.

**Here:** that is literally what this project is. The name is in the title.

### Microservices

**The problem:** one deployable means one scaling decision forced on workloads
with different needs.

**The name:** microservices — separate deployables with separate lifecycles.

**Here:** five of them. And the split is on **scaling and failure boundaries,
not on data entities**, which is the part worth saying: the gateway needs ~294
pods, the control plane needs 3. Packaged together you would have to pick one
number for both.

### Event-driven architecture

**The problem:** if service A calls service B and waits, A's uptime becomes
A × B. With three services at 99% each you have 97%.

**The name:** event-driven — services publish facts and others react, rather
than calling each other.

**Here:** the gateway publishes to Kafka and never waits. Metering, audit and the
indexer consume. **There are no synchronous calls between services at all**
([ADR-0010](../adr/0010-no-synchronous-inter-service-calls.md)).

### Layered architecture (inside each service)

**The problem:** without direction, every class can reach every other class, and
a change anywhere can break anything.

**The name:** layered architecture — dependencies point one way only.

**Here:** inside the gateway, `Web → Pipeline → Cache / Provider / RateLimit /
Auth`. Enforced by the `layering` rule in `ArchitectureRulesTest`:

```java
.whereLayer("Web").mayNotBeAccessedByAnyLayer()
.whereLayer("Pipeline").mayOnlyBeAccessedByLayers("Web")
.whereLayer("Cache").mayOnlyBeAccessedByLayers("Pipeline")
```

> **A correction.** In [B6](B6-lld.md) idea 7 I said the `layering` rule is what
> stops one service importing another. That is wrong — `layering` governs layers
> *within* the gateway. Cross-service imports are prevented by something simpler
> and stronger: **Maven**. The gateway module declares `common`, `embedding` and
> `kafka-support` as dependencies and nothing else, so it **cannot see** the
> other services' classes at all. There is no import to forbid. The architecture
> test still matters, for the nine rules it does cover.

### Not hexagonal, and worth being honest about

There is no `ports`/`adapters` package structure, no domain layer isolated behind
interfaces. The packages are grouped by **feature** — `auth`, `cache`, `budget`,
`ratelimit` — not by hexagonal role.

Saying "we used hexagonal architecture" would not survive someone opening the
repo. **Layered, feature-packaged, event-driven microservices** is the accurate
description.

---

## 2. The caching patterns

### Cache-aside

**The problem:** where does the "check the cache first" logic live?

**The name:** cache-aside (also called lazy loading). The application checks the
cache, and on a miss fetches from the source and populates the cache itself. The
cache does not know about the database.

**Here:** `CompletionPipeline` checks Tier 1, then Tier 2, then the provider, and
writes back on a miss. The alternative — read-through, where the cache fetches
for you — would hide the miss cost and make the tiering impossible to control.

### Multi-level cache

**The problem:** a fast cache is small and expensive; a big cache is slower.

**The name:** multi-level (or tiered) caching — a small fast layer in front of a
large slower one.

**Here:** Tier 1 is Redis exact-match at 2–5 ms. Tier 2 is pgvector semantic
search at ~100 ms. **And a Tier-2 hit is promoted into Tier 1**, so the next
identical prompt costs 2 ms.

### Cache stampede is NOT handled

Worth knowing as a gap. If a thousand requests for the same uncached prompt
arrive at once, all thousand miss and all thousand call the provider. The usual
fix is **request coalescing** (one call, the rest wait on it). Not built.

---

## 3. The messaging patterns

### Transactional outbox

**The problem:** you must change the database **and** publish a message. Write
first and the publish may fail, losing the event. Publish first and the write may
fail, announcing something that never happened. There is no safe order.

**The name:** transactional outbox. Write the change and a row in an `outbox`
table **in one transaction**, then a separate relay reads that table and
publishes. Either both happen or neither does.

**Here:** `control-plane/.../outbox/OutboxRelay.java`. It claims rows with
`FOR UPDATE SKIP LOCKED`, which is what lets several replicas poll the same table
without blocking each other or double-publishing.

**And deliberately NOT used on the hot path**
([ADR-0008](../adr/0008-outbox-only-off-hot-path.md)) — usage and audit events
are fire-and-forget, because losing one costs a fraction of a cent and the error
direction is under-billing. Key revocation uses the outbox, because a revoked
credential still working is a security hole.

### Saga (orchestrated)

**The problem:** a business operation spans several services, and there is no
distributed transaction to roll it all back.

**The name:** saga — a sequence of local steps, each with a compensating undo. If
step 4 fails, you run the undos for 3, 2, 1.

**Two flavours:** *choreographed* (each service listens and reacts) and
*orchestrated* (one coordinator drives the steps).

**Here:** orchestrated — `control-plane/.../saga/SagaOrchestrator.java`, with
`SagaStep` and `SagaContext`. Chosen over choreography
([ADR-0009](../adr/0009-orchestrated-saga.md)) because choreography has no single
place to ask *"where did this one get stuck?"*

### Idempotent consumer

**The problem:** Kafka guarantees *at-least-once* delivery. A consumer **will**
see the same message twice — after a rebalance, or a crash between the write and
the offset commit.

**The name:** idempotent consumer — processing the same message twice has the
same effect as once.

**Here:** `ON CONFLICT DO NOTHING` on every consumer write. Tested: 18 duplicate
deliveries produced zero double-billing.

### Competing consumers

**The problem:** one consumer cannot keep up with the message rate.

**The name:** competing consumers — many instances share a queue, each taking
different messages.

**Here:** Kafka consumer groups. One partition goes to exactly one consumer, so
the partition count is a **hard cap** on useful pods: 32 partitions means the
33rd pod is assigned nothing and idles while looking healthy.

---

## 4. The resilience patterns

All four are in `ResilientLlmProvider`, nested as
`Retry ( CircuitBreaker ( TimeLimiter ( Bulkhead ( call ) ) ) )`.

| the problem | the name | what it does here |
|---|---|---|
| a transient failure fails the whole request | **Retry** | try again a few times |
| you keep calling something that is clearly down | **Circuit breaker** | after enough failures, fail instantly for a while instead of waiting |
| a call hangs forever | **Timeout** (TimeLimiter) | give up after 30 s |
| one slow dependency consumes every thread | **Bulkhead** | cap how many calls run at once |

**And two more elsewhere:**

**Load shedding** — `withAdmissionControl` in the pipeline refuses with 503 when
too many requests are in flight. The step most people leave out: without it an
overloaded service accepts everything, queues it, and fails *all* requests slowly
instead of *some* requests fast.

**Fail-open** — if Redis is unreachable the rate limiter **allows** the request.
A deliberate choice that a cache outage should not take the product down, with
the sharp edge that during a Redis outage there is no rate limiting at all.

**Graceful degradation** — a Tier-2 failure is counted as a miss, not an error.
The system degrades to "slower and more expensive", never to "down".

---

## 5. The data patterns

### Consistent hashing

**The problem:** `hash % N` reassigns almost every key when N changes, so adding
a shard moves nearly all the data.

**The name:** consistent hashing, with **virtual nodes**. Each shard is placed at
150 points around a ring; a key belongs to the next point clockwise. Adding a
shard moves only ~1/N of keys, all onto the new shard.

**Here:** `ConsistentHashRing`, MurmurHash3, 150 virtual nodes. Measured: adding
a 5th shard moved 19.5% of tenants, all to the new shard, none between existing
ones.

### Sharding, and hash partitioning

**The problem:** one database cannot hold the data or serve the traffic.

**The names:** *sharding* splits across **servers**; *partitioning* splits across
**tables inside** one server.

**Here both, and both keyed on the tenant:** the ring picks the server,
`PARTITION BY HASH (tenant_id) × 8` picks the table. The shard key is `tenantId`
([ADR-0006](../adr/0006-shard-key-is-tenant-id.md)) because a semantic search
cannot be routed — sharding by prompt would scatter similar prompts across all
shards and force every search to query all of them.

### Read replicas with read-write splitting

**The problem:** reads vastly outnumber writes and compete with them.

**The name:** read-write splitting — writes to the primary, reads to replicas.

**Here:** `ShardedDatabase` routes reads to a replica and writes to the primary,
round-robin across replicas by an atomic counter. Replication is **asynchronous**
([ADR-0007](../adr/0007-read-replicas-are-safe.md)), and the argument is specific
to this data: lag makes a search *fail to find* an entry, which is a **cache
miss, never a wrong answer**.

> **Status check:** this is code that is ready for replicas. There are none —
> the deployment runs one PostgreSQL pod and the repo contains no replication
> configuration. See [B5c §3](B5c-plain-answers.md).

### Token bucket

**The problem:** real clients are bursty. A batch job firing 50 requests then
idling is within its average, but a rule enforcing a smooth rate rejects it.

**The name:** token bucket — a burst allowance that refills at a steady rate.
Compare *leaky bucket*, which enforces a strictly uniform rate.

**Here:** `token_bucket.lua`, chosen over leaky bucket deliberately
([ADR-0005](../adr/0005-token-bucket.md)).

---

## 6. The code-level patterns

| the problem | the name | where |
|---|---|---|
| the same behaviour copied into four implementations drifts apart | **Decorator** | `ResilientLlmProvider` wraps any provider |
| a new error silently becomes a generic 500 | **Sealed hierarchy / algebraic data type** | `GatewayError`, so the compiler forces a decision |
| an object that can exist in an invalid state | **Invariant enforced at construction** | `Embedding` refuses a non-unit vector |
| picking an implementation at runtime | **Registry + ordered fallback chain** | `ProviderRegistry` |
| the same value object recreated everywhere | **Value object** | `TenantId`, `ModelId`, `CacheKey`, `ProviderId` — all records, all validated at construction |

### Reactive / non-blocking

**The problem:** one thread per request means thousands of threads and gigabytes
of stacks, mostly idle waiting on I/O.

**The name:** reactive, non-blocking, event-loop.

**Here:** Spring WebFlux and Project Reactor. Chosen over Java 21 virtual threads
([ADR-0001](../adr/0001-webflux-over-virtual-threads.md)) for one reason worth
remembering: **virtual threads give you cheap concurrency but no backpressure.**
Under overload the queue grows until memory does. A gateway's job under overload
is to *refuse work*, and an unbounded queue of cheap threads is exactly what
prevents that.

---

## 7. The operational patterns

**Shadow mode / dark launch** — run the new thing, log what it *would* have done,
act on nothing. Tier 2 ships this way so the false-hit rate can be measured
before anyone trusts it.

**Health probes, three kinds** — startup gates the others (the JVM plus model
load takes ~16 s and liveness would otherwise kill the pod before it ever became
ready); readiness removes a pod from the load balancer; liveness restarts it.
Confusing readiness and liveness restarts pods that were merely busy.

**Sidecar-free log shipping** — Alloy runs as a DaemonSet (one per node) rather
than a sidecar per pod, so the cost is per node, not per pod.

**Infrastructure as code** — Helm for the application, Terraform for AWS, one
Deployment template looped over a `services` map so everything structural is
written once.

---

## 8. How to say it in an interview

If asked *"what architecture did you use?"*, the short version:

> "**Event-driven microservices behind an API gateway.** Five services, split on
> scaling and failure boundaries rather than by data entity — the gateway needs
> hundreds of pods, the control plane needs three. No synchronous calls between
> services at all; they communicate only through Kafka and the database, so one
> service cannot make another slow.
>
> Inside each service it is layered, enforced by an architecture test rather than
> a convention. The caching is cache-aside and two-tier. Durability is chosen per
> data class: a transactional outbox where losing a message is unrecoverable —
> key revocation, for instance — and fire-and-forget where it costs a fraction of
> a cent and is self-healing.
>
> Data is sharded by tenant using a consistent hash ring with virtual nodes, and
> hash-partitioned by tenant inside each shard, so a cross-tenant read is
> structurally impossible rather than merely forbidden."

And if they push on what you would do differently:

> "The clearest gap is that embedding runs on the request path. It should be an
> event to the indexer that already exists. The second is cache stampede — a
> thousand simultaneous misses for the same prompt all call the provider, and
> request coalescing would fix it."

**Do not say "hexagonal architecture".** It is not — and someone will open the
repo.

---

**Previous:** [B6-steps — the LLD, step by step](B6-steps.md).
**Next:** B6c — which APIs we expose and which we call.
