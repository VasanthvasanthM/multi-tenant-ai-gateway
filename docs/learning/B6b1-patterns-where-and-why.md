# B6b1 — Each pattern: where we used it, and why

[B6b](B6b-architecture-used.md) names the patterns. This file takes each group
and says, in plain words, **where it actually is in the code** and **what breaks
without it**.

All five groups: **Caching**, **Messaging**, **Resilience**, **Data**, **Code-level**.

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

# Resilience

Everything in this group protects against **one thing you depend on going wrong**.
The AI provider is outside your control, so it will.

## Four failures, four mechanisms

They are not four versions of the same precaution. Each covers a case the other
three cannot see.

```
1. it fails once, briefly        ->  retry
2. it fails constantly           ->  circuit breaker
3. it never answers at all       ->  timeout
4. it answers, but very slowly   ->  bulkhead
```

**Retrying helps case 1 and makes case 2 worse.** That single sentence is why
there has to be more than one mechanism.

## Pattern 1 - Retry

### The case

A dropped packet, a brief TLS hiccup, one bad server behind the vendor's load
balancer. The next attempt succeeds.

Without a retry, a customer's request fails for a reason that was gone a
millisecond later.

### The config

```java
.maxAttempts(2)                        // one retry, not three
.waitDuration(Duration.ofMillis(250))
.retryOnException(ResilienceConfig::isRetryable)
.failAfterMaxAttempts(true)
```

**Two attempts, not five.** The comment gives the reason: LLM calls are
*expensive and slow*, so a deep retry policy multiplies cost and latency for
little gain - and there is a provider fallback chain anyway, so **a different
provider is a better third attempt than the same provider again.**

### The part that matters more than the number

```java
// Retryable AND a provider fault. A 429 from our own limiter is retryable but
// retrying it here, inside the gateway, would defeat the limit.
return gatewayError.retryable() && countsAsProviderFailure(error);
```

Two separate questions, both of which must be yes:

- **is this the kind of error that could succeed next time?** - a malformed
  request is still malformed on attempt two; retrying it just doubles load
  during an incident
- **is it the provider's fault at all?** - our own rate limiter returning
  "too many requests" is technically retryable, but retrying it *inside* the
  gateway would defeat the limit we just applied

And `retryable()` is read **off the error type itself**, not re-decided here, so
*"the two can never disagree."*

### Why retry cannot work alone

During a real outage, every request now makes 2 failed calls instead of 1:

```
1,000 requests  ->  2,000 calls to a provider that is already struggling
```

You have **doubled the load on the thing that is failing**, stopped it
recovering, and made every request take 250 ms longer before failing anyway.

That is what the next pattern exists to stop.

## Pattern 2 - Circuit breaker

### Three states

```
CLOSED      normal. Calls go through, failures are counted.
            |
            |  too many failures
            v
OPEN        every call is refused INSTANTLY, without trying.
            |
            |  after 30 seconds
            v
HALF-OPEN   let a few through as a test.
            succeed -> CLOSED      fail -> OPEN again
```

### What it buys

| | without | with |
|---|---|---|
| load on a failing provider | **doubled** by retry | **zero** - nothing is sent |
| how long a request takes to fail | 30 s timeout x 2 attempts | microseconds |
| recovery | someone has to notice | automatic, it tests by itself |

A refused call costs nothing and returns immediately, so the gateway stays fast
**while** the provider is down. Without a breaker, "the provider is down" means
"every request takes a minute to fail".

### The config, line by line

```java
.slidingWindowType(COUNT_BASED)
.slidingWindowSize(50)
.minimumNumberOfCalls(10)
.failureRateThreshold(50.0f)
.slowCallRateThreshold(80.0f)
.slowCallDurationThreshold(Duration.ofSeconds(20))
.waitDurationInOpenState(Duration.ofSeconds(30))
.permittedNumberOfCallsInHalfOpenState(5)
.automaticTransitionFromOpenToHalfOpenEnabled(true)
```

**Count-based, not time-based.** The comment: *"LLM traffic is bursty: a time
window can hold three calls during a lull and open the circuit on a single
blip."* A ten-second window with 2 calls in it, one of which failed, is a 50%
failure rate - and a false alarm. Fifty *calls* is fifty calls whenever they
arrive.

**Minimum 10 calls.** Without it, *"the first failed call after startup is a 100%
failure rate and trips the breaker"* - every pod would start with its circuit
open.

**Slow calls count toward opening.** The subtle one:

> *"An upstream answering every request in 25 s is functionally down even though
> nothing technically fails, and a pure failure-rate breaker would never notice."*

80% of calls over 20 seconds opens the breaker **even though every one of them
succeeded**. A pure failure-rate breaker misses exactly the failure mode that
hurts most.

### The most important line in the file

```java
// THE IMPORTANT PART. A 400 from the vendor means the caller sent something
// unacceptable - an unknown model, bad parameters. Counting that as a provider
// failure lets one client with a malformed request open the circuit for every
// other tenant.
.recordException(ResilienceConfig::countsAsProviderFailure)
```

Without this predicate, **one tenant sending a bad model name in a loop opens
the circuit for all 100 tenants.** Their bug becomes your outage.

Only three things count as the provider's fault:

```java
return gatewayError instanceof GatewayError.UpstreamUnavailable
        || gatewayError instanceof GatewayError.UpstreamTimeout
        || gatewayError instanceof GatewayError.Internal;
```

And an error nobody recognises counts as a fault - *"an unrecognised throwable is
an infrastructure fault until proven otherwise."* The safe default is to assume
the infrastructure, not the caller.

### How the two fit together

> **Retry handles the blip. The breaker stops retry from making an outage worse.**

## Pattern 3 - Timeout

### The case

The provider never answers. No error, no response. Silence.

**An error comes back and you handle it. Silence does not come back.** The
request sits holding a connection, memory, and a slot in the in-flight count
long after the customer gave up.

**The timeout creates the error that silence refused to give you.**

### The config

```java
.timeoutDuration(Duration.ofSeconds(30))
.cancelRunningFuture(true)
```

Real AI calls genuinely take a long time - a long answer from a slow model can
take 20 seconds legitimately. So 30 seconds is *longer than any realistic
answer, shorter than forever*. Too short cuts off requests that were about to
succeed; too long holds resources for something already dead.

And `cancelRunningFuture(true)` - give up **and** cancel the underlying work.
Without it you stop waiting but the call keeps running, which fixes the latency
and not the resource leak.

### Per attempt, not per request

```java
// Per attempt, not per request. With retry wrapped outside, the worst case is
// attempts x this value, which must stay inside the caller's own deadline
```

30 s x 2 attempts = **60 s worst case**, which has to fit inside whatever
deadline the caller has. This is why the retry count is 2 and not 5: a deeper
retry policy would silently blow the overall budget.

### The nginx timeout is deliberately longer

```yaml
nginx.ingress.kubernetes.io/proxy-read-timeout: "120"
```

**120 at the edge against 30 inside.** Whoever times out first produces the
error message. If nginx gave up first you would get a bare `504` with no
explanation; letting the gateway hit its own limit first means **we** produce the
error, with a code and a message saying what happened.

> **The rule: the inner timeout must be shorter than the outer one.** Otherwise
> the layer that knows least about what went wrong is the one that reports it.

## Pattern 4 - Bulkhead

### The case

The provider still works, but it has gone slow. 10 seconds per call instead of 1.

**Nothing has failed.** No errors. No timeouts - 10 s is well under the 30 s
limit. Every request eventually succeeds. Which is exactly why this one is
dangerous.

```
requests arriving:  100/second
calls completing:    10/second
```

Ninety a second accumulate, each holding resources. Within a minute there are
thousands in flight, all waiting on one slow dependency, and the gateway runs out
of connections or memory.

**And then everything breaks - including the cache hits**, which never needed the
provider at all. A request Redis could have answered in 3 ms cannot be served,
because one slow dependency consumed every shared resource.

### The fix

Named after a ship's compartments: if the hull is breached, the bulkhead confines
the flooding to one compartment instead of sinking the ship.

```java
int perProvider = Math.max(1, properties.limits().maxInFlight() / 2);
BulkheadConfig.custom()
        .maxConcurrentCalls(perProvider)
        .maxWaitDuration(Duration.ZERO)
```

**Half the pod's admitted concurrency per provider** - 32 against a
`max-in-flight` of 64 - and the next call is refused immediately.

> This was a hardcoded 64 until 2026-11-16. See *"the number that became dead
> code"* below: when admission control came down to 64, a per-provider cap of 64
> could never be reached, and a limit that cannot fire still passes every test
> it has.

**`maxWaitDuration(Duration.ZERO)` - fail fast, do not queue.** The comment:

> *"A queue here would be invisible latency on top of the time limiter, and
> callers would time out having waited without ever reaching the provider."*

A queue feels kinder and is worse: the customer waits 30 seconds and *then*
fails, having never even reached the provider. Refusing in microseconds at least
tells them something true, immediately.

### What it protects

Resource use per provider now has a **ceiling it cannot exceed**, however slow
that provider gets. So a slow provider degrades only the requests that need it,
and **cache hits keep being served at 3 ms.**

## The order the four are wrapped in

```java
return delegate.complete(request)
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(TimeLimiterOperator.of(timeLimiter))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .transformDeferred(RetryOperator.of(retry));
```

In Reactor the **last** one applied is the outermost, so that chain reads
inside-out:

```
Retry ( CircuitBreaker ( TimeLimiter ( Bulkhead ( call ) ) ) )
```

**This order is load-bearing.** Each boundary is where it is for a reason, and
each one would break something if moved:

| boundary | why there | what breaks if moved |
|---|---|---|
| **bulkhead innermost** | a permit is held only for the actual call | outside the time limiter it stays held during the timeout wait, so a slow upstream **exhausts permits instead of failing fast** |
| **time limiter inside the breaker** | a timeout is *recorded* as a failure | outside it, the breaker **never sees the slowest failures** - the ones most worth opening on |
| **retry outermost** | each attempt gets a fresh breaker check | inside the breaker, **retries hammer an upstream the breaker already judged unhealthy** |

That last one is the retry-plus-breaker pairing made real: because retry is
outside, attempt two asks the breaker again, and if the breaker has just opened,
attempt two is never sent.

### Streaming is protected differently - on purpose

```java
return delegate.stream(request)
        .transformDeferred(BulkheadOperator.of(bulkhead))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
```

**Bulkhead and breaker only. No timeout, no retry.**

- **no timeout** - *"a time limiter on a stream would cancel a perfectly healthy
  long generation partway through"*. The point of streaming is that total
  duration is unbounded while individual tokens arrive promptly, so a total-time
  deadline measures the wrong thing.
- **no retry** - *"once bytes have reached the client there is nothing to retry
  into."* You cannot un-send the half-answer you already streamed.

A single wrapper applied identically everywhere would have been simpler and
wrong.

### Why a decorator and not code inside each provider

```java
public final class ResilientLlmProvider implements LlmProvider
```

Same interface, wrapped around a real provider. Policy is identical for every
upstream, and *"duplicating it would guarantee the copies drift."*

It also **keeps the providers honest** - they contain protocol translation and
nothing else, *"which is why they can be tested against WireMock without any of
this machinery."* The resilience behaviour is tested once, against a fake
provider, with no HTTP involved.

### And the states are visible

```java
circuitBreaker.getEventPublisher().onStateTransition(event ->
        log.warn("circuit breaker '{}' {} -> {}", ...));
```

Every transition is logged at WARN, and the registries are bound to Micrometer
by hand. That binding exists for a non-obvious reason:

> *"Creating the registries as plain beans means Spring Boot's auto-configuration
> never sees them, and so never wires them to Micrometer. Without these binders
> there is no `resilience4j_circuitbreaker_state` series at all - and a circuit
> opening is one of the few signals genuinely worth paging on, so losing it
> silently is not acceptable."*

**Configuring it in Java bought the predicates and cost the free metrics.** The
four `MeterBinder` beans buy them back.

One more: an open circuit makes the provider report **unhealthy**, regardless of
what its own health endpoint says - *"a provider whose circuit is open is not
healthy however cheerfully its own health endpoint answers."*

## Pattern 5 - Load shedding

Everything above protects you from **the provider**. This protects you from
**your own customers**.

### The case

Traffic arrives faster than you can serve it. Nothing is broken - there is just
too much.

The default behaviour is to accept it all and queue it. That ends one way:

```
accept everything  ->  queue grows  ->  every request slow  ->  everyone times out
                                                                NOBODY is served
```

Accepting work you cannot finish means **failing all of it** instead of some of
it.

### The fix: refuse early, while refusing is still cheap

Three separate places, because there are three separate resources to protect.

**1. At the edge - concurrent requests in the gateway**

```java
if (inFlight.incrementAndGet() > limits.maxInFlight()) {
    inFlight.decrementAndGet();
    shed.increment();
    return Mono.error(new GatewayException(
            new GatewayError.Overloaded(limits.maxInFlight())));
}
```

```yaml
max-in-flight: 64
```

Request 65 is refused **immediately**, in microseconds, having consumed almost
nothing. The 64 already accepted keep their normal latency.

> This was **256** until 2026-11-16, when the question *"how do you know these
> counts?"* turned out to have no answer. The audit below is kept because the
> reasoning is the point, not the number.

**2. The embedding queue - CPU**

```yaml
queue-capacity: 0        # 0 = derive: 2 slots per worker
```

```java
if (error instanceof RejectedExecutionException) {
    // The bounded queue is full. This is backpressure working, not a defect, so
    // it becomes a retryable 503 rather than a 500.
```

Embedding is CPU-bound, so an unbounded queue would just accumulate work the CPU
can never catch up on.

The depth is **derived from the worker count** rather than fixed, because the
number of workers follows the pod's CPU limit and a fixed number cannot. Two
slots per worker keeps the deepest waiter about one inference behind the worker
that will serve it.

**Shedding here is almost free**, which is what justifies going shallow: a
rejected embedding is not a failed request. `tryTier2` catches it and returns
empty, so the request falls through to the provider exactly as if Tier 2 had
missed. The cost is one lost cache hit, not an error - so there is no reason to
buy queue depth with latency that every caller pays.

**3. Per provider - the bulkhead**, already covered: calls beyond half the pod's
admitted capacity are shed rather than queued.

### Where these three numbers come from — the honest answer

Checked on 2026-11-16, because "how do you know these counts?" is the right
question to ask of any limit.

**Two of the three were chosen, not measured. The third has a derivation, and
the derivation argues for a different number than the one shipped.**

#### 64 — the bulkhead

```java
// Concurrent in-flight calls per provider. Bounded so one slow upstream
// cannot consume every connection and starve the others.
.maxConcurrentCalls(64)
```

The comment explained **why it is bounded**. It did not explain **why 64**.
There was no measurement behind it and no derivation anywhere in the repo. It
was a round power of two.

**And then it became dead code.** Once `max-in-flight` dropped from 256 to the
measured 64, the pipeline could admit at most 64 requests at a time - so 64
concurrent calls to a single provider could never be exceeded, and the bulkhead
could not fire at all. Nothing failed. **A limit that cannot trigger still
passes every test it has**, and the metric it publishes simply reads zero
forever, which looks exactly like a bulkhead doing its job.

**Fixed 2026-11-16** by deriving it instead:

```java
int perProvider = Math.max(1, properties.limits().maxInFlight() / 2);
```

Half, so one slow provider can hold at most half the pod's admitted capacity.
The other half stays free for cache hits - which need no provider at all - and
for the fallback provider the chain switches to. **That is the bulkhead's actual
job: not protecting the vendor, but stopping one vendor's bad day from consuming
a pod that could still serve ~70% of its traffic from cache.**

The ideal source is still the vendor's own published concurrency limit - a fact
to look up per provider, not to derive. Deriving from our own capacity is the
honest version until a real vendor is wired in.

#### 128 — the embedding queue

This one *does* have a derivation, in `EmbeddingService.create`:

> *"Sized from the latency target, not from optimism: at roughly 6 ms per
> inference and 4 workers, a queue of 256 means the last entry waits about
> 380 ms, already far beyond the 15 ms Tier-2 budget. Anything deeper is latency
> nobody will collect on."*

The reasoning is sound and the arithmetic checks out — `256 / 4 x 6 ms = 384 ms`.

**But the shipped value is 128, not 256.** The javadoc reasons about a number
that is not the one in `application.yml`. And 128 does not escape its own
argument:

| queue | workers | the last entry waits | against a 15 ms budget |
|---|---|---|---|
| 256 (the javadoc's example) | 4 | **384 ms** | 25x over — *"far beyond"*, correctly rejected |
| **128 (what ships)** | 4 | **192 ms** | **12.8x over** |
| **128 (what ships)** | 2, on a 1-2 vCPU pod | **384 ms** | **25x over — the exact number the comment rejects** |
| 10 | 4 | 15 ms | what the stated rule actually implies |

Worker count is `min(model.maxConcurrency(), availableProcessors)`, so the
javadoc's "4 workers" assumes a 4-CPU pod. The capacity plan sizes gateway pods
at 1000m-2000m. **On the pod shape this is actually deployed at, the shipped
queue produces the same 384 ms the comment calls unacceptable.**

#### What was done about it — 2026-11-16

The depth is now **derived from the worker count** (`queue-capacity: 0`, two
slots per worker) instead of hardcoded, so it follows the CPU limit of whatever
pod it lands in - which is the thing the original comment was reasoning about
and the thing a fixed number could never track. An explicit value is still
honoured, so a deployment that has measured its own inference cost keeps
control.

And the justification is now the right one. The old comment argued from latency
alone; the real reason a shallow queue is safe here is that **a rejected
embedding is not a failed request** - `tryTier2` turns it into a Tier-2 miss and
the request goes upstream. The cost of shedding is one lost cache hit.

---

#### 256 — max-in-flight

```yaml
max-in-flight: 256
```

**No comment at all** — and it is the only value in that config block without
one. `max-prompt-code-points` above it explains code points versus UTF-16 units;
`default-limit-tokens` explains what it mirrors and why. This one is bare.

The natural assumption is that the load-testing campaign produced it. The git
history says otherwise:

```
2026-11-06  d06d834  max-in-flight: 256 written, in the FIRST commit
2026-11-14  b0dae0d  "Record the first measured throughput figure"
```

**Eight days earlier than the first measurement, and never changed since** -
`git log -S` returns exactly one commit for it. No deploy profile or load-test
script sets it either, so no sweep ever varied it.

And the campaign that followed contradicts it. The sweeps ran at 64 and 90 VUs,
so the limit was never once reached - and the campaign's own note records what
happened at 90 concurrent:

> *"Throughput collapses rather than plateauing - 347 rps at 64 VUs, 55 rps at 90."*

**The gateway falls over at 90 in flight. The limit that exists to prevent that
is set at 256.**

So check it against the one number that *was* measured. A 1000m pod sustains
**271.3 rps at a 198 ms mean**. Little's Law, `L = lambda x W`:

```
271.3 rps  x  0.198 s  =  53.7 requests in flight
```

**The pod is already CPU-saturated at ~54 in flight** — that measurement was
taken with the pod throttled in 92.9% of CPU periods. The load generator ran 64
VUs, so ~64 was the highest concurrency ever observed on one pod.

The shed limit is **256** — about **five times** past the point where the pod is
already out of CPU. Inverting Little's Law to ask what latency 256 implies:

```
256 / 271.3 rps  =  944 ms per request
```

**Shedding starts only once each request is taking about 940 ms** — nearly 5x
the measured 198 ms mean and 2x the 476 ms p95. Long before that, the pod has
been returning slow responses and the readiness probe, the HPA and the customer
have all noticed.

#### What was done about it — 2026-11-16

`max-in-flight` is now **64**, and the line carries the derivation it never had:

```yaml
# Derived from the load campaign, not chosen. 64 concurrent is the highest level
# measured serving healthily (271.3 rps, 198 ms mean on a 1000m pod); at 90 the
# gateway did not plateau but collapsed, 347 rps -> 55 rps ...
max-in-flight: 64
```

**Why 64 and not 89.** 90 is where collapse was *observed*; 64 is the highest
level *proven healthy*. Everything between them is untested, so the limit sits
on the last proven-good point rather than one step from a known explosion. And a
shed limit should fire when the pod is full, not when it is about to die - by 89
in flight, latency is already unacceptable even though nothing has crashed yet.

**Why this is the right failure to prevent.** The 90-VU collapse had a specific
mechanism: a queue deeper than the 500 ms Redis timeout makes Tier-1 lookups time
out, **every timed-out lookup becomes a full upstream call**, and that extra work
deepens the queue further. It is a feedback loop, which is why throughput fell
347 -> 55 instead of flattening. Shedding at 64 keeps the queue shallow enough
that the cache never begins timing out.

**It is per pod shape**, and the comment now says so: 64 is a 1000m figure, to be
re-derived if the CPU limit changes.

---

**Read plainly: this limit protects memory, not latency.** It is a backstop
against unbounded growth, not an admission controller tuned to the pod's
capacity. 256 x a 32,000 code-point prompt cap is roughly 16 MB of prompt text
worst case, which a 384 MiB heap survives — so it does the memory job. It just
does not do the job the name suggests.

#### What it would take to set them properly

| number | what to measure |
|---|---|
| **max-in-flight** | the concurrency at which p99 crosses the latency SLO on the real pod shape — shed *there*, not 5x past it |
| **queue-capacity** | real `gateway.embedding.duration` (the meter added in A13) and the real worker count on a 2 vCPU pod; then `budget x workers / duration` |
| **bulkhead 64** | the provider's own concurrency limit, which is a vendor fact, not a guess |

Until then the honest sentence is: **the mechanisms are right, the thresholds
are placeholders.** Which is a much better position than the reverse — a tuned
threshold on a mechanism that does not work cannot be fixed by tuning.

> The same shape as [A16](../learning-plan.md) (the Redis entry size was assumed,
> not measured) and kappa in the scaling model. The pattern is worth naming:
> **a number with no comment next to it has usually never been checked.**

### The bug this nearly had

```java
return work.doFinally(signal -> inFlight.decrementAndGet());
```

> *"`doFinally` rather than `doOnTerminate`: the permit must be released on
> cancellation too, which happens whenever a client disconnects mid-request.
> Missing that leaks a permit per dropped connection and the gateway slowly
> strangles itself."*

Worth sitting with. `doOnTerminate` covers success and error but **not
cancellation** - and a customer closing their laptop mid-request is a
cancellation. Each one would leak one permit permanently. After 256 dropped
connections the gateway refuses everything, while every dashboard looks fine and
nothing in the logs says why.

**A counter that only ever goes up is the quietest possible outage.**

### Shedding is reported as its own thing

```java
record Overloaded(int inFlightLimit) implements GatewayError
```

> *"In-flight request limit reached; the request was shed rather than queued.
> This is backpressure working as designed. Shedding at the edge keeps latency
> bounded for requests already accepted, which is strictly better than queueing
> everything and timing out uniformly."*

And the same distinction is kept for the breaker:

```java
record CircuitOpen(String provider) implements GatewayError
```

> *"Separate from `UpstreamUnavailable` because it is a decision this gateway
> made, not a failure it observed. Keeping them apart is what lets a dashboard
> show 'we are shedding load deliberately' rather than 'the provider is down'."*

**Three different 503s, three different causes:**

| error | HTTP | what actually happened | what to do about it |
|---|---|---|---|
| `overloaded` | 503 | we are at capacity | **add gateway pods** |
| `circuit_open` | 503 | we stopped calling a sick provider | **wait, or check the vendor** |
| `upstream_unavailable` | 503 | the provider genuinely failed | **the vendor's problem** |

Collapsed into one generic "service unavailable", every incident would start with
twenty minutes of guessing which of those three it was.

## Pattern 6 - Fail-open

### The case

The thing that *checks* a request breaks - not the thing that serves it.

Redis holds the rate-limit buckets and the budget counters. If Redis is
unreachable, the gateway **cannot know** whether this tenant is over their limit.

Two choices, and neither is free:

```
fail CLOSED  ->  refuse everything you cannot verify
                 a Redis blip takes the entire product down
fail OPEN    ->  allow it through unchecked
                 limits briefly stop being enforced
```

### The decision

```java
return config.failOpen()
        ? RateLimitDecision.failedOpen()
        : RateLimitDecision.failedClosed(UNAVAILABLE_BACKOFF);
```

**Fail open by default**, with the reasoning written down next to it:

> *"Fail-open trades enforcement for availability. It is the right default for a
> gateway whose job is to keep serving - a Redis blip should not take the product
> down - but it does mean a Redis outage temporarily removes abuse protection."*

The budget gate makes the same choice for a **different** reason, and this one is
the more interesting argument:

> *"For a budget, failing open gives away tokens, which sounds like the expensive
> choice. It is not, because the Postgres ledger still records every token and
> the tenant is still billed correctly - what is lost is the ceiling,
> temporarily. Failing closed would instead refuse paid traffic from every tenant
> because one Redis node blinked."*

**You still bill them.** The money is not lost; only the cap is - and only while
Redis is down.

### Three properties that keep it honest

**1. It is a choice, not a hardcoded assumption.**

```
gateway.rate-limit.fail-open=false
gateway.budget.fail-open=false
```

*"A deployment that genuinely must never exceed a budget sets
`gateway.budget.fail-open=false` and accepts that."* The trade is the operator's
to make, not the code's.

**2. It is never silent.** Both paths carry a `degraded` flag, and both gates
count it:

```java
this.degraded = Counter.builder("gateway.ratelimit.degraded")
...
if (decision.degraded()) {
    degraded.increment();
    log.warn("rate limiting degraded for tenant {}: limiter unavailable", ...);
```

> *"Without its own counter that fail-open policy let traffic through
> unchecked."* - the counter is the only thing standing between "we chose to
> degrade" and "we silently stopped enforcing limits".

**3. WARN, not ERROR.**

> *"Logged at WARN, not ERROR: the gateway is still serving. An operator needs to
> see this, but it should not page as though traffic had stopped."*

Waking someone at 3am for a degraded limiter, with the same urgency as an
outage, is how alerts get muted - and then the real one is missed too.

### The same shape elsewhere

Fail-open is the policy across every optional dependency:

| dependency fails | gateway does | what you lose |
|---|---|---|
| Redis (rate limits) | allows the request | abuse protection, temporarily |
| Redis (budgets) | allows the request | the ceiling - **not** the billing |
| Tier 2 / pgvector | treats it as a cache miss | the hit rate, so cost rises |
| ONNX model missing | loads the lexical fallback | Tier 2 serves nothing at all |
| Kafka | drops the event | that window of billing records, **permanently** |

Note the last row. **Fail-open is not always harmless** - that one really does
lose data, which is why it is argued explicitly in
[B4a](B4a-failure-behaviour.md) rather than assumed.

And the embedding fallback shows the pattern applied to a *model*:

> *"Deliberately does not rethrow. A corrupt or half-downloaded model should
> leave the gateway serving Tier-1 traffic, not take it down - Tier 2 is an
> optimisation, and the same rule applies to its model as to its database."*

It then prints a seven-line banner saying the fallback is active and that
`car` and `automobile` now score as unrelated - because **a silent fallback is a
trap**, and the thing to be loud about is precisely the degradation you chose to
survive.

## What this group costs

| | |
|---|---|
| **what you get** | a slow, broken or absent provider degrades rather than cascades; overload refuses the newest work instead of failing everyone; a Redis outage does not stop the product |
| **what you pay** | one retry doubles the cost of a genuinely failing call; a breaker can open on a provider that was about to recover; fail-open means limits are briefly unenforced; six mechanisms is six more things to tune |
| **the gap** | the breaker is **per provider, not per model** - one bad model on a vendor opens the circuit for all of that vendor's models |

---

# Data

Worked examples with real tool output are in
[B5e](B5e-shard-operations-worked.md); this section is where each pattern lives
in the code and what breaks without it.

## Pattern 1 - Sharding (partitioning)

### The problem

One machine has one set of disks, one amount of RAM, one CPU. The capacity plan
needs **381 GiB** of cached answers at 2 billion requests a day. No single
machine does that.

### The decision that everything else follows from

Not *whether* to split, but **what to split on**. The shard key is `tenantId`.

The reason is the most common query. A Tier-2 lookup asks *"has <b>this
customer</b> asked anything similar before?"* With the customer as the key, that
is one query to one machine:

```java
public ShardId route(TenantId tenantId) { ... }
```

Split on anything else - date, prompt hash - and one customer's answers scatter
across every machine, so every lookup must ask all of them and merge. The ring's
own javadoc names this as the thing being avoided:

> *"Cross-shard approximate-nearest-neighbour search is the usual reason sharded
> vector stores fail to scale, and this design simply never performs one."*

**The shard key is chosen by looking at your hottest query, not at your data.**

### What it costs

One customer's data is pinned to one machine, so a tenant ten times bigger than
the rest makes one shard hot and cannot be spread. That is the **whale problem**,
and the escape hatch is explicit about its price:

> *"Sub-sharding fixes a write hotspot and, for that tenant only, gives up
> single-shard Tier-2 reads: that tenant's reads fan out across all of them and
> merge."*

You buy write capacity by paying the fan-out cost you were avoiding. Hence
opt-in and deliberately rare.

### Honest status

```yaml
sharding:
  # Empty by default: the unsharded deployment.
```

Router, ring, per-shard clients and migration tooling are built and tested
against two real databases. **The shipped default is one database, no shards.**

## Pattern 2 - Consistent hashing

### The problem with the obvious approach

`hash(key) % shardCount` remaps almost everything when the count changes:

```
moves = N/(N+1)     4 -> 5 shards: 80%     9 -> 10 shards: 90%
```

Derived and confirmed empirically at 80.01% over 200,000 keys in
[B5e](B5e-shard-operations-worked.md). The javadoc's verdict:

> *"An effectively total cache invalidation and a migration that cannot be done
> online."*

### The ring

```
moves = 1/(N+1)     4 -> 5 shards: 20%     9 -> 10 shards: 10%
```

Exactly inverted. Measured at 19.87% over 100,000 tenants.

### Virtual nodes: 150

With one position per shard the arcs are wildly uneven - measured max/min of
**14.9x** across 8 shards. Spread follows `1/sqrt(V)`, so:

```
  1 vnode    CV 63.0%      max/min 14.91x
 10          CV 30.9%               3.00x
150          CV  8.5%               1.36x
250+         lost in sampling noise
```

150 is where the curve flattens; Ketama uses 160. Pinned by two tests - one that
150 spreads within 25% of fair share, and one that **1 vnode does not**, which is
what would catch virtual nodes silently stopping working.

### Why the ring is immutable

```java
public ConsistentHashRing withShard(ShardId shard)    // returns a NEW ring
```

swapped by compare-and-set in `ShardRouter`:

> *"A mutable ring would need a lock or a concurrent map on the hottest path in
> the system, and that shared-state contention is exactly what shows up as a
> non-zero sigma term in the Universal Scalability Law fit - the thing this
> project sets out to demonstrate is absent."*

A reader sees the old ring or the new one, never a half-updated one, and nobody
blocks.

### Three guards worth knowing

**Shards are sorted before placement.** Every pod must build an identical ring,
or two pods route one tenant to two shards and silently split its cache.

**The seed is a constant that must never change.** It decides every position on
the ring; changing it relocates every tenant - a full cache invalidation plus a
data migration.

**Removing the last shard is refused.** An empty ring cannot route, so this would
turn a decommission into an exception on every request with nothing naming the
cause.

## Pattern 3 - Read-write splitting

```java
public DatabaseClient forRead(TenantId tenantId)   // a replica, if healthy
public DatabaseClient forWrite(TenantId tenantId)  // always the primary
```

### Why reads from a replica are safe HERE

A replica is always slightly behind, which for most systems means a wrong answer.
On this data it does not:

> *"On this data, replica lag produces a cache MISS, never a wrong answer: the
> search does not find a newly written entry, the gateway falls through to the
> provider, and the response is re-cached."*

**A cache is the one kind of data where being behind cannot make you wrong** - it
can only make you miss. That single property (ADR-0007) is what permits far more
aggressive read fan-out than a normal OLTP system allows.

Writes go to the primary because a write to a replica simply fails - *"which at
least fails loudly."*

### Round-robin, deliberately not random

> *"With two replicas, random selection gives an uneven split often enough to
> look like a replica problem."*

### The condition attached, and the bug in it

ADR-0007's permission came with a requirement: **alarm on lag**. So
`ReplicaLagMonitor` asks each replica
`EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))`, and a replica
past `max-replica-lag` is skipped in favour of the primary.

> **The monitor never started.** Found 2026-11-16: `start()` was called only from
> its own test and carried no `@PostConstruct`. The gauge is registered in the
> constructor, so it existed and read a healthy **0.0 forever**, and the health
> predicate answered "never behind" for every shard. The safety condition was
> reporting perfect health without ever querying a database. Fixed; see
> [`problems-and-solutions.md`](../problems-and-solutions.md).

### Honest status

No replicas are deployed - a single Postgres pod. And `routeWithReplicas()`
exists on the ring but is **not** used for data replication; PostgreSQL's own
streaming replication handles redundancy within a shard.

## Pattern 4 - Token bucket

### The problem

One customer's buggy loop sends 10,000 requests a second and the other 99 get
nothing.

### Why not a plain counter

A fixed window punishes normal clients - a batch job firing 50 requests then
idling for a minute is well inside its average and still rejected - and it is
gameable at the boundary: 20 requests at 10:00:00.9 plus 20 at 10:00:01.1 is 40
in 200 ms, and both windows report compliance.

### The bucket

```yaml
capacity: 100                 # burst size
refill-per-second: 20         # sustained rate
```

Coins drip in at 20/second, the bucket holds at most 100, each request costs one.
**Idle time becomes allowance** - which is exactly what real API clients need.

### Why not the other algorithms

| | state per tenant | burst? | exact Retry-After? | why not |
|---|---|---|---|---|
| fixed window | 1 counter | no | no | boundary doubling; rejects legitimate bursts |
| sliding window log | **one timestamp per request** | yes | yes | at peak, `92,593 rps x 60 s` = **5.5M timestamps** held and trimmed |
| sliding window counter | 2 counters | partly | approximate | more state and code, still no real burst allowance |
| leaky bucket | 1 counter | **no, by design** | yes | *"real API clients are bursty... fails a leaky bucket outright"* |
| **token bucket** | **2 numbers** | **yes** | **yes** | - |

It stores the **rate**, not the **history**, which is why two numbers suffice at
any traffic level. Idle tenants cost nothing either: the key carries a TTL.

### Why it is a Lua script - the real reason

The naive version is GET, compute, SET: a read-modify-write across a network
round trip.

```
pod A reads: 1 coin left
pod B reads: 1 coin left      <- same instant
both allow, both write 0      <- two requests on one coin
```

> *"Under load that is not a rare race - it is the normal case, and the effective
> limit silently becomes (configured limit x replica count)."*

At 294 pods, a 20/second limit is really **5,880/second** - and it would look
perfect in testing with one pod. Redis runs the script atomically, so there is no
gap to interleave with.

> **Third appearance of the same lesson.** Rate limiter: a Lua script. Hourly
> rollup: `DO UPDATE SET x = x + ...`. Idempotent consumer: `ON CONFLICT`. All
> three move the read-modify-write somewhere it cannot be interrupted.

### Why the clock comes from Redis

```lua
local clock = redis.call('TIME')
```

> *"A pod running a few seconds fast would persist a future timestamp and every
> other pod would then compute a negative elapsed time and refill nothing."*

One fast pod would freeze that tenant's bucket at zero until clocks converged.
Redis is the one clock every pod already shares - the problem is removed rather
than handled. There is still a `elapsedMillis < 0` guard for a clock stepping
backwards under NTP correction or VM migration.

### Small deliberate choices

**Start full, not empty.** *"Starting empty would make a tenant's very first
request fail, which looks exactly like an outage to them."*

**Round remaining down.** *"Reporting 4 remaining when 4.9 are available is the
safe direction to round."*

**Report the exact wait.** `retryAfterMillis = ceil(deficit / refillPerSecond *
1000)` becomes the `Retry-After` header, so a client backs off by the right
amount *"instead of guessing and making the overload worse."*

**Capacity below refill is refused at startup**, because the bucket could then
never hold a second's worth and the sustained rate would silently be lower than
configured.

### One token per request, and the gap that leaves

```java
public Mono<RateLimitDecision> tryAcquire(TenantId tenant) {
    return tryAcquire(tenant, 1);
}
```

The variable-cost overload exists and is tested; **nothing calls it**. So a
two-character prompt and a 30,000-character prompt both cost exactly one token,
although the second costs far more money and CPU.

Defensible, because the division of labour is clean - the rate limiter governs
*how often*, the budget gate governs *how much*, using the provider's own token
counts after the fact. Charging by size here too would count the same expense
twice in two systems with different numbers.

### The limits are the same for everyone

Each tenant gets its **own bucket** - the key is tenant-scoped, so one tenant's
spending never touches another's. But the bucket's **size** comes from config
with no tenant lookup anywhere in the limiter, so a free-tier user and an
enterprise customer get identical limits.

Note the inconsistency with its neighbour: the monthly **token budget** *is* per
tenant, via `TenantQuotaRegistry` reading an override from Redis with a default
fallback. The rate limit has no equivalent, and the pattern to copy is three
packages away.

---

# Code level

The patterns that are invisible in a diagram and decide whether the rest can be
relied on.

## Pattern 1 - Value objects

### The weak version

```java
void serve(String tenant, String model, String key)
```

Three strings. Nothing stops the wrong order: it compiles, runs, and serves the
wrong customer.

```java
void serve(TenantId tenant, ModelId model, CacheKey key)
```

The compiler now refuses the mix-up - but that is the smaller benefit.

### The real reason: one place to validate

A tenant id is not a label. It reaches five subsystems:

```
Redis keys          "acme:c1:9f86d081..."
Kafka partition key decides the partition
Prometheus labels   tenant="acme"
the hash ring       decides the shard
log lines           tenant=acme
```

Each is a separate hazard if the value is unconstrained:

| character | consequence |
|---|---|
| `*` or `:` | *"lets one tenant's key pattern match another's, which is a **cross-tenant data leak**, not a cosmetic problem"* |
| unbounded values | *"cardinality explosion that will take the monitoring stack down before it takes the gateway down"* |
| a newline | *"log injection and **forged audit entries**"* |

So it is checked once, at birth:

```java
private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9_-]{2,63}$");
```

> *"Validating once at the boundary is far cheaper than escaping correctly at
> every one of those call sites, and **impossible to forget**."*

The alternative is not "validate in five places" - it is "validate in five
places, forever, including the sixth place someone adds next year."

Everything downstream can then use the value with **no checking at all**:

```java
public String redisHashTag() {
    return "{" + value + "}";     // the value cannot hurt us
}
```

### The error message never echoes the value

```java
"invalid tenant id: must match " + VALID.pattern() + " but was length " + value.length()
```

Pattern and length, never the input. A rejected value might be a leaked API key,
a password pasted into the wrong field, or an injection attempt. **A validation
error message is a place secrets go to be permanently recorded**, and this one
refuses.

## Pattern 2 - Sealed hierarchy

`GatewayError` is a sealed interface with **12** record implementations:

```
Unauthenticated   Forbidden        InvalidRequest    PayloadTooLarge
TenantMigrating   RateLimited      BudgetExhausted   UpstreamUnavailable
UpstreamTimeout   CircuitOpen      Overloaded        Internal
```

`sealed` means nothing outside that file can add a thirteenth.

### What it prevents

Every error must be mapped in several places - HTTP status, metric label, retry
decision, whether it carries `Retry-After`. With ordinary exceptions, adding a
type means *remembering* all of them, and:

> *"A new type silently falls into a catch-all and is reported as a 500."*

A sealed type makes a `switch` without `default` fail to compile when a case is
missing, so the compiler walks you to every mapping site.

This happened in the small on 2026-11-16: adding `TenantMigrating` forced the
`Retry-After` handler to be touched, which is how the now-wrong test name *"only
a 429 gets Retry-After"* was caught.

### Two more reasons from the javadoc

**Cost.** *"Exceptions capture a stack trace on construction. On a path that
returns 429 under load - precisely when the system is busiest - that is real CPU
spent describing a condition that is entirely expected."* A rate limit is not a
surprise; it is the system working.

**Reactive fit.** *"Exceptions thrown inside lambdas get wrapped, lose their
stack context, and are awkward to branch on."*

### Four questions every error must answer

```java
String code();        // stable, machine-readable - "never rename one"
String message();     // "must never contain another tenant's data or a secret"
int httpStatus();
boolean retryable();
```

No type can exist without answering all four.

### retryable() has one meaning

> *"This flag is what the client SDK and the circuit breaker act on, so it must
> mean exactly one thing: would resending this identical request plausibly
> succeed later?"*

Which is why the retry predicate reads it rather than re-deciding:

```java
return gatewayError.retryable() && countsAsProviderFailure(error);
```

> *"Reuses the taxonomy's own retryable flag, so the two can never disagree."*

### And it is where the four 503s come from

| code | what happened | what to do |
|---|---|---|
| `overloaded` | at capacity | add pods |
| `circuit_open` | we stopped calling a sick provider | wait |
| `upstream_unavailable` | the vendor failed | their problem |
| `tenant_migrating` | moving this customer's data | wait 5 s |

One generic "service unavailable" would start every incident with twenty minutes
of guessing which of the four it was.

## Pattern 3 - Invariant at construction

**An object cannot exist in an invalid state.** The constructor is the only door
and it refuses.

### The load-bearing example

```java
double magnitude = magnitudeOf(copy);
if (Math.abs(magnitude - 1.0d) > NORM_TOLERANCE) {
    throw new IllegalArgumentException(...);
}
```

Every embedding must be unit length, because the HNSW index was built for
**inner product** rather than cosine - cheaper, and:

> *"An HNSW index built for inner product gives the same ranking as one built for
> cosine **only if** the vectors are normalised."*

If one vector is not normalised, nothing crashes. The search runs and returns
results that are simply **ranked wrong** - a longer vector scores higher for
being longer, not for being more similar. The cache then serves confident wrong
answers, and the only symptom is customers occasionally getting replies to
questions they did not ask.

> *"So normalisation is not a stylistic choice; the index correctness depends on
> it. **The invariant is enforced at construction rather than trusted.**"*

### Why the tolerance is not zero

Floating-point maths yields 0.99999994, not 1.0, so exactness would reject
correct vectors. The check still works because **the gap between rounding error
and an actual bug is enormous** - an un-normalised vector is not off by 0.0001.

### The invariant must survive construction

```java
// The backing float[] is copied in and out.
```

Arrays are mutable. Without the copy:

```java
Embedding e = Embedding.of(values);
index(e);
values[0] = 999;          // the stored vector changed, after indexing
```

> *"Leaving a stored row that no longer matches the text it came from."*

Validating at birth is pointless if the object can be mutated afterwards.

### The same pattern elsewhere

| type | what it refuses |
|---|---|
| `TenantId` | anything outside `[a-z0-9_-]`, 3-64 chars |
| `Embedding` | empty, or not unit length |
| `RateLimit` | capacity below refill-per-second |
| `MigrationState` | a dual-write where `from == to` |
| `ShardRouter` | removing the last shard |

Every failure in that list is **silent** - a wrong ranking, a data leak, a rate
limit secretly lower than configured, a dual-write that is not dual. None
produces a stack trace, so the constructor is the only place they can still be
caught cheaply.

## Pattern 4 - Registry

```java
private final Map<ProviderId, LlmProvider> providers;
private final LlmProvider primary;
private final List<LlmProvider> fallbacks;
```

The pipeline does not know OpenAI or Ollama exist. It asks for a provider.

### Ordered fallback, never load balancing

> *"Different vendors produce different answers to the same prompt, so
> round-robin would make the cache incoherent - an identical request could yield
> two different cached answers depending on which vendor happened to serve the
> miss."*

**The cache would be poisoned by its own load balancer.** A strict primary with
ordered fallbacks keeps output stable and degrades only when the primary is down.

### Each provider is tried at most once

> *"An unbounded or retrying chain turns one vendor's outage into an amplified
> storm against all the others, which is how a partial failure becomes a total
> one."*

And the division of labour is explicit: *"retry policy belongs to Resilience4j,
in one place."* The registry chooses **which** provider; the decorator decides
**how hard to try**.

### Why the class is final

> *"The constructor throws on misconfiguration, which is correct fail-fast
> behaviour, but a throwing constructor on a non-final class is open to a
> **finalizer attack** - a subclass overriding finalize() can capture the
> partially built instance."*

Sealing the class removes the hazard outright, *"which is better than suppressing
the warning."*

## Pattern 5 - Decorator

```java
public final class ResilientLlmProvider implements LlmProvider {
    private final LlmProvider delegate;
```

Same interface in and out, so the registry hands out providers without knowing
some are wrapped. Covered in full under **Resilience**; the code-level points
are:

**No duplication.** *"Duplicating it would guarantee the copies drift."* Five
vendors would mean five copies of retry logic and, within a year, five slightly
different ones.

**It keeps the providers honest.** *"They contain protocol translation and
nothing else, which is why they can be tested against WireMock without any of
this machinery."* `OpenAiProvider` turns our request into their JSON and back,
and is tested against a fake HTTP server with no breaker, retry or timeout in the
way. The resilience behaviour is tested once, against a fake provider, with no
HTTP at all.

**They compose without knowing about each other:**

```
registry    decides WHICH provider     primary -> fallback -> fallback
decorator   decides HOW to call it     retry, breaker, timeout, bulkhead
```

Add a sixth vendor and it gets all four resilience mechanisms for free, because
the registry wraps whatever it is handed.

---

**All five groups complete.** Caching, Messaging, Resilience, Data, Code-level.
**Index:** [B6b - the patterns by name](B6b-architecture-used.md).
