# B6b1 — Each pattern: where we used it, and why

[B6b](B6b-architecture-used.md) names the patterns. This file takes each group
and says, in plain words, **where it actually is in the code** and **what breaks
without it**.

Groups done so far: **Caching**, **Messaging**. The rest follow.

---

# Caching

## Pattern 1 — Cache-aside

### What it means

**The application checks the cache itself.** The cache is a dumb box that knows
nothing about the database or the AI provider.

The opposite is **read-through**: you ask the cache, and the cache fetches from
the source for you if it does not have it.

### Where it is

`CompletionPipeline.serve()`:

```java
return tier1.get(key)                                                     // 1. Tier 1
    .switchIfEmpty(Mono.defer(() -> tryTier2(request, key, promptText)))  // 2. Tier 2
    .switchIfEmpty(Mono.defer(() -> callUpstream(request, key, promptText))); // 3. the AI
```

`switchIfEmpty` means *"if that found nothing, do this instead."*

Three lines, and **the pipeline is in charge**. It asks Tier 1, and if Tier 1
shrugs, the pipeline decides what to try next. Tier 1 has no idea Tier 2 exists.
On a miss the pipeline writes the answer back itself, in `storeQuietly()`.

### Why, and not read-through

**You can see the cost of a miss.** With read-through, one call sometimes takes
3 ms and sometimes 30 seconds and the difference is hidden inside. With
cache-aside the three steps are separate in the code, each with its own timer and
counter - which is the only reason the dashboards can show *hits by tier* at all.

**The tiering would otherwise be impossible.** Tier 2 has guards in front of it:
is it enabled, is the request eligible, is the prompt short enough. Those are
decisions about *this request*. A read-through cache would have to know about
sampling settings and tool definitions, which are none of its business.

**A cache failure must not fail the request.** Because the pipeline is in charge,
a Tier-2 error becomes "found nothing" and the request carries on to the
provider. In a read-through design the cache sits on the critical path and its
failure is your failure.

## Pattern 2 — Multi-level cache

```
Tier 1   Redis, exact match        2-5 ms      <- checked first
Tier 2   pgvector, meaning match   ~100 ms     <- only if Tier 1 missed
upstream the real AI               0.5-30 s    <- only if both missed
```

### Why two tiers and not one

Each can do something the other cannot.

**Tier 1 is cheap and cannot be wrong.** It hashes the exact text: either those
bytes were asked before or they were not. No judgement involved.

But it is **useless for rewording**. "How do I reset my password?" and "how do I
reset my password" are different bytes, so a miss - even though a human sees one
question.

**Tier 2 catches the rewording** by comparing meaning. But it costs ~100 ms,
needs CPU for the embedding, and **it can be wrong** - it can decide two
different questions are the same.

So the order is not arbitrary:

> **Cheapest and safest first. The expensive, fallible one only sees what the safe
> one could not answer.**

Run Tier 2 first and you would pay ~100 ms and a CPU-bound embedding on **every**
request, including the ~76% Tier 1 could answer in 3 ms for free.

## Pattern 3 — Promotion

```java
// A Tier-2 hit is promoted into Tier 1 so the next occurrence of
// this exact prompt costs 2 ms instead of another vector search.
.flatMap(hit -> tier1.put(key, hit).thenReturn(hit)))
```

When Tier 2 finds a match, the answer is **also** written into Tier 1 - under the
*new* prompt's key.

### Why - three requests

```
Request 1:  "How do I cancel my subscription?"
            Tier 1 miss, Tier 2 miss  ->  AI provider, 800 ms, you pay
            stored in both tiers

Request 2:  "steps to cancel my subscription"        <- different wording
            Tier 1 miss (different bytes)
            Tier 2 HIT (same meaning), ~100 ms
            -> promoted into Tier 1 under THIS wording's key

Request 3:  "steps to cancel my subscription"        <- same wording as #2
            Tier 1 HIT, 3 ms                         <- because of the promotion
```

Without promotion, request 3 pays the ~100 ms vector search again for a prompt
Tier 1 could serve in 3 ms.

**The compounding effect:** popular rewordings migrate into the fast tier by
themselves. Nobody has to predict which phrasings matter - **usage decides.**

## The eviction policy

Confirmed in config and on the running instance:

```
maxmemory-policy   allkeys-lru
maxmemory          96mb
```

**`allkeys`** - when memory runs out, *any* key may be dropped. The alternative,
`volatile-*`, only drops keys that have an expiry set.

**`lru`** - Least Recently Used: drop whatever has not been touched for longest.

So: *when full, delete the keys nobody has asked for in a while.*

### Why not `noeviction`

`noeviction` is the apparently-safer choice: when full, **refuse new writes**.
Right for a database, exactly wrong for a cache.

| | `allkeys-lru` | `noeviction` |
|---|---|---|
| when full | drops the coldest entries | **refuses to store anything new** |
| what you see | hit rate dips slightly | **errors on every cache write** |
| what it costs | some requests go upstream | the same, *plus* error noise everywhere |

The comment in `dev-infra.yaml` says it directly: `noeviction` *"would start
refusing writes and surface as cache-write errors instead of evictions"*. You
would get the identical loss of cache value, dressed up as a stream of errors,
and spend time debugging "cache write failures" when the real message was "it is
full".

**`allkeys-lru` fails in the right shape: quietly, and with the least valuable
data.**

### Why not `volatile-lru`

It only evicts keys that have a TTL. Every Tier-1 entry has one, so in theory it
works - but the rate-limiter buckets and the API-key cache also live in Redis. If
any of those lacked a TTL, `volatile-lru` would have nothing it was *allowed* to
evict and would behave like `noeviction`: refusing writes while plenty of
evictable-looking data sat there. `allkeys` cannot get stuck that way.

### Why not `allkeys-lfu`

LFU is Least *Frequently* Used, keeping popular keys even when briefly idle, and
is arguably better for this workload. LRU was taken as the conventional default.
The difference only matters once the working set genuinely exceeds memory, which
has not been measured - so **switching to LFU is a reasonable experiment, not a
known improvement.**

### No persistence at all

```yaml
--save ""           # no snapshots
--appendonly no     # no write-ahead log
```

*"Tier 1 is a cache: losing it costs a slower request."* If Redis restarts
everything is gone, the next requests miss, and it refills. You pay money for an
hour and nothing is incorrect. Persistence would cost disk writes on the hot path
to protect data that is by definition reconstructible.

### The signal to watch

```bash
kubectl exec deploy/redis -- redis-cli INFO stats | grep evicted_keys
```

**`evicted_keys > 0` means the working set outgrew memory.** Hit rate falls,
provider bill rises. A **cost problem, not an outage** - the right direction to
fail, but invisible unless someone looks, which is why it is on the dashboard.

> The 96 MiB cap is a laptop number, so Redis cannot eat the node. The capacity
> plan assumes 381 GiB across 9 nodes. The policy is production-shaped; the limit
> is not, and is not meant to be.

## The gap

**Cache stampede is not handled.** A thousand simultaneous requests for the same
uncached prompt all miss both tiers and **all thousand call the provider**. The
fix is **request coalescing** - the first calls, the other 999 wait on its
result. Not built. On a popular new prompt that is 999 unnecessary payments.

---

# Messaging

## Pattern 1 — Transactional outbox

### The problem it solves

You must do two things: **change the database** and **publish a message**. There
is no safe order.

```
write first, then publish  ->  the publish fails  ->  the change happened,
                                                      nobody was told
publish first, then write  ->  the write fails    ->  you announced something
                                                      that never happened
```

### The fix

Write the change **and a row in an `outbox` table** in **one transaction**. Either
both land or neither does. A separate process reads that table later and
publishes.

The database transaction is the thing that makes it atomic - no distributed
coordination needed.

### Where it is

`control-plane/.../outbox/OutboxRelay.java`.

### The detail that makes it work with several replicas

```sql
SELECT id, topic, partition_key, payload::text AS payload, attempts
  FROM control_outbox
 WHERE published_at IS NULL
 FOR UPDATE SKIP LOCKED
```

The class comment calls this *"the whole trick"*, and here is why.

Three replicas all poll the same table. A plain
`SELECT ... WHERE published_at IS NULL` hands **all three the same rows**, so
every message is published three times.

- **`FOR UPDATE`** locks the rows this replica selected.
- **`SKIP LOCKED`** makes the other replicas *step over* locked rows instead of
  waiting for them.

So replica 1 takes rows 1-10, replica 2 takes 11-20, replica 3 takes 21-30, all
at once, with **no duplicates and no waiting**.

Without `SKIP LOCKED` they would serialise behind one another - correct, but with
three replicas doing the work of one.

### Where it is deliberately NOT used

The gateway's own usage and audit events are **fire-and-forget**, not outboxed
([ADR-0008](../adr/0008-outbox-only-off-hot-path.md)). The decision is made per
data class by asking what losing a message costs:

| data | loss costs | mechanism |
|---|---|---|
| usage event | a fraction of a cent, and the error is **under**-billing | fire-and-forget |
| audit event | one log line | fire-and-forget |
| cache-write event | one entry unindexed; the next prompt re-caches it | fire-and-forget |
| tenant provisioning | a tenant stuck half-created | **outbox** |
| **key revocation** | **a revoked credential still working** | **outbox** |
| billing state change | a wrong invoice | **outbox** |

The top three heal themselves. The bottom three do not, and one is a security
boundary. **An outbox write on the hot path would put a database write in front
of every cached response**, which is the cost being avoided.

## Pattern 2 — Orchestrated saga

### The problem

An operation spans several services. There is no distributed transaction, so
there is nothing to roll back.

### The fix

A **saga**: a sequence of local steps, each with a compensating undo. If step 4
fails, run the undos for 3, 2, 1 in reverse.

### Two flavours, and why this one

**Choreographed** - each service listens for events and reacts. No coordinator.

**Orchestrated** - one coordinator drives the steps and knows the state.

This project implements **orchestrated**
([ADR-0009](../adr/0009-orchestrated-saga.md)): `SagaOrchestrator`, `SagaStep`,
`SagaContext`.

> ### IMPORTANT: the machinery is built, but nothing uses it yet
>
> Checked on 2026-11-15. `SagaOrchestrator` is a `@Component` and is
> unit-tested by `SagaOrchestratorTest`, but:
>
> - **nothing implements `SagaStep`** outside the interface itself
> - **`SagaOrchestrator` is never called** anywhere in `src/main` - the only
>   construction of it is inside its own test
>
> So it is a working engine with nothing plugged in. **Which problem does it
> solve today? None** - no multi-step operation currently runs through it.
>
> What it is *for* is visible in the control plane packages (`outbox`, `quota`,
> `saga`): tenant lifecycle. Creating a tenant is the obvious case - insert the
> row, generate a key, set a quota in Redis, publish the event. If the quota step
> fails you must undo the key and the row, or you have left a half-created tenant
> with a working credential and no limit.
>
> **Say it accurately:** an orchestrated saga framework is implemented and
> unit-tested; no saga is defined, so it runs nothing in production. Claiming it
> is in use does not survive a ten-second grep.

**Why:** choreography has **no single place to ask "where did this one get
stuck?"** The state is spread across every participant's inbox, so answering a
support question means reading five services' logs and inferring. An orchestrator
has one row with one state.

### A detail from the code

```java
this.compensated = Counter.builder("saga.compensated").register(meterRegistry);
// Alert on this. A saga that failed to compensate has left resources behind and
```

Two things worth noticing:

- the **compensation itself is counted**, so "how often do we have to undo?" is a
  number rather than a feeling
- and the comment says **alert on a failed compensation**, because that is the
  genuinely bad state: a half-finished operation with resources left behind and
  nobody told

Also: steps run **sequentially**, which the code calls out - *"steps execute
sequentially, so contention is not the concern"* - and recovery compensates
**exactly the steps that actually ran**, not all of them.

## Pattern 3 — Idempotent consumer

### The problem

Kafka guarantees **at-least-once** delivery. A consumer **will** see the same
message twice - after a rebalance, or a crash between writing the row and
committing the offset.

Without protection, that is double-billing.

### The fix, and there are TWO different ones here

`metering/.../UsageLedger.java` uses a different strategy per table, and the
difference matters.

**For the ledger - one row per request:**

```sql
ON CONFLICT (tenant_id, request_id) DO NOTHING
```

A duplicate is a **no-op**. The row already exists, so ignore it. Correct,
because the row is a fact that happened once.

**For the hourly rollup - a running total:**

```sql
ON CONFLICT (tenant_id, period_hour) DO UPDATE SET
    request_count = tenant_usage_rollup.request_count + EXCLUDED.request_count,
    ...
```

Here a duplicate must **add**, not be ignored - the row is a total, and this
event contributes to it.

And the comment names the real reason for `DO UPDATE SET x = x + new` rather than
reading the value and writing it back:

> *"two pods updating the same hour would lose one of them"*

Read-modify-write from two pods is the **same race as the rate limiter**: both
read 100, both add 5, both write 105, and one update vanishes. Doing the addition
**inside** the database statement makes it atomic.

> That is the second time the same lesson appears - the rate limiter solved it
> with a Lua script, the rollup with `DO UPDATE SET x = x + ...`. **Both move the
> read-modify-write to where it cannot be interrupted.**

Tested: 18 duplicate deliveries produced **zero** double-billing.

## How many consumers are there? Three.

Each has its own Kafka configuration, and each reads exactly one topic:

| service | reads | writes |
|---|---|---|
| **metering** | `gateway.usage.v1` | `usage_ledger` + `tenant_usage_rollup` |
| **audit** | `gateway.audit.v1` | `audit_log` |
| **indexer** | `gateway.cache-write.v1` | vectors into `prompt_cache`, plus TTL eviction |

No service reads another service's topic. And of the five services:



**One producer on the request path, one off it, three consumers.**

### Why three separate services rather than one consumer

They scale differently:



The indexer handles about 30% of the volume, because a cache-write event only
happens on a miss. One combined consumer would have to be sized for the busiest
topic, and would do the indexer's light work on 19 pods instead of 6.

And the failure consequences differ completely: losing metering delays invoices
and loses nothing, because Kafka buffers; losing the indexer silently degrades
the hit ratio and lets storage grow. Separate services mean separate alerts,
separate dashboards, and one can be restarted without touching the others.

## Pattern 4 — Competing consumers

### The problem

One consumer cannot keep up with the message rate.

### The fix

Many instances share the work, each taking different messages. In Kafka that is a
**consumer group**.

### The constraint that bites

**One partition goes to exactly one consumer in a group.**

```
gateway.usage.v1
  partition 0   -> metering pod A
  partition 1   -> metering pod A
  partition 2   -> metering pod B
  ...
  partition 31  -> metering pod S
```

So with 32 partitions, **the 33rd pod is assigned nothing.** It starts, joins the
group, gets no work, and idles while looking perfectly healthy.

That is why the autoscaler for metering is capped at 32 and not left open, and
why repartitioning has to happen **before** scaling the deployment.

### And why the message key is the tenant

Kafka hashes the key to pick a partition, so the same key always lands on the
same partition. With `tenantId` as the key:

- **ordering where it matters** - Kafka orders messages *within* a partition, so
  one tenant's events are processed in the order they happened. "Used 100 tokens"
  cannot be processed after "budget increased".
- **each consumer owns whole tenants** - the pod handling partition 7 handles
  *all* of that tenant, so it can safely keep a running total in memory because
  no other pod is touching that tenant.

The cost: a huge tenant creates a hot partition, and a partition cannot be split.
Same trade as the whale shard.

---

**Still to come:** Resilience, Data, Code-level.
**Index:** [B6b — the patterns by name](B6b-architecture-used.md).
