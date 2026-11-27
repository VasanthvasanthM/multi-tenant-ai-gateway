# B8 — Kafka

Topics, why the partition key is the tenant, the four consumer settings that
each prevent a specific disaster, and what gets thrown away when the broker is
gone.

---

## Five topics

```
gateway.usage.v1          "Acme used 150 tokens"       -> metering
gateway.audit.v1          "Acme asked a question"      -> audit
gateway.cache-write.v1    "file this answer"           -> indexer
gateway.tenant.v1         tenant lifecycle             -> (control plane)
gateway.dead-letter.v1    messages nobody could read   -> a human
```

The `.v1` is the escape hatch for the day a message shape changes in a way old
consumers cannot read.

## Why the contracts live in `common`

> *"An event schema is a contract between two deployables. Defining it twice
> guarantees the copies diverge, and the divergence shows up as a consumer
> silently dropping a field rather than as a compile error."*

One definition compiled into producer and consumer, so **an incompatible change
fails the build** rather than failing quietly in production.

---

## The partition key is the tenant

> *"Partitioning by tenant gives per-tenant ordering, which the metering consumer
> needs - usage must be applied in the order it occurred - and it matches the
> sharding scheme used everywhere else in the system."*

The same key as the hash ring and the Redis hash tag. **One idea in three
places.**

**What it buys**

- *ordering where it matters* - "used 100 tokens" cannot be processed after
  "budget increased"
- *each consumer owns whole tenants* - so a pod can hold a running total in
  memory, because no other pod touches that tenant

**What it costs** - a huge tenant creates a hot partition, and a partition cannot
be split. The same trade as the whale shard.

---

## Producer settings

```java
ACKS_CONFIG                "all"
ENABLE_IDEMPOTENCE_CONFIG  true
RETRIES_CONFIG             Integer.MAX_VALUE
DELIVERY_TIMEOUT_MS        120_000
LINGER_MS                  20
COMPRESSION_TYPE           "snappy"
```

**`acks=all` plus idempotence:**

> *"These producers carry billing events, audit records and dead letters; losing
> one to a leader failover means losing money, a compliance record, or the last
> copy of a bad message."*

`acks=1` means the leader confirmed it. If that leader dies before its followers
copy the record, **the message is gone and the producer was told it succeeded.**

**`retries=MAX_VALUE` is only safe because idempotence is on.** Without it, a
retry after a timeout can write the record twice. The real bound is
`delivery.timeout.ms = 120s` - a time limit rather than an attempt count.

**`linger=20ms`** batches messages that nobody is waiting for, so 20 ms buys
throughput for free on a fire-and-forget path.

---

## Consumer settings — four decisions, four disasters

### `auto.offset.reset = earliest`

> *"A service redeployed after downtime must not skip the events that accumulated
> while it was gone - for metering that is unbilled revenue, for audit it is a
> compliance gap."*

The default, `latest`, means "start from now". Deploy metering after an outage
and everything queued is **silently skipped** - no error, just an invoice that is
too small.

### `enable.auto.commit = false`

> *"Offsets are acknowledged after the write succeeds. Auto-commit acknowledges
> on poll, so a crash mid-processing loses records silently."*

Committing after the write is what makes at-least-once real - and at-least-once
is only safe because of the `ON CONFLICT` idempotency on the other end.

### `max.poll.records = 100`, not the default 500

> *"The default of 500 lets a rebalance hand one consumer 500 records to finish
> inside `max.poll.interval.ms`; exceeding it triggers another rebalance, and the
> loop presents as an unstable broker."*

Too-big batches -> miss the deadline -> Kafka assumes the consumer is dead ->
rebalance -> the next consumer gets a big batch -> **it loops forever.** And it
looks like the broker is broken, which is where the investigation goes first.

### `CooperativeStickyAssignor`

> *"The eager default stops the whole group on every membership change, so a
> rolling deploy becomes a series of full stalls."*

With 19 metering pods, the default means 19 full group stalls per deploy.
Cooperative revokes only the partitions actually moving.

---

## The dead-letter topic

A message that cannot be parsed has no good answer:

```
skip it    ->  silent data loss
retry it   ->  it fails identically forever, blocking the partition behind it
```

The third option is to move it aside and carry on, which is what
`gateway.dead-letter.v1` is for - and why every event carries a schema version:

> *"The version lets a consumer recognise what it cannot handle and route it to a
> dead-letter topic, instead of parsing it half-correctly and writing
> plausible-looking nonsense to the ledger."*

**"Plausible-looking nonsense" is the thing to fear.** A half-parsed billing
event does not crash; it writes a wrong number that looks exactly like a right
one.

---

## The ceiling nobody can raise with replicas

**One partition goes to exactly one consumer in a group.**

```
gateway.usage.v1
  partition 0   -> metering pod A
  partition 1   -> metering pod A
  partition 2   -> metering pod B
  ...
  partition 31  -> metering pod S
```

With 32 partitions the **33rd pod is assigned nothing**. It starts, joins the
group, idles, and looks perfectly healthy - right CPU, right memory, zero
throughput.

The chart says so directly:

```yaml
# ONE replica, and this is a correctness constraint rather than a resource
# one. ... partition-ordered consumption means adding replicas only helps
# once there are more partitions than consumers. Scale the topic first.
replicaCount: 1
```

> **Partitions are the ceiling; replicas only fill it.** Which is why the
> metering autoscaler is capped rather than left open - an HPA scaling to 50 pods
> against 32 partitions buys 18 idle pods and a bigger bill.

---

## When Kafka is down, the gateway throws events away

```java
dropped.increment();
log.debug("dropped {} event for tenant {}: {}", ...);
.onErrorResume(error -> Mono.empty())
.subscribe();
```

No retry, no buffer, no queue. **Dropped and counted.**

### Why that is the right trade

The alternative is an outbox - write the event to Postgres in the same
transaction. But:

> *"On this path there is no database transaction to join - the response came
> from Redis or from a vendor - so adding one would mean a synchronous insert on
> a path budgeted at 2 ms, roughly doubling it."*

```
a dropped usage event   ->  a rounding error in billing
3 ms on every request   ->  the latency target the product is built around
```

Paying 3 ms on 100% of requests to protect against rare partial telemetry loss
is a bad trade, and it is made explicitly rather than by accident.

### The condition attached

> *"Drops are counted rather than prevented. `gateway.events.dropped` is the
> metric that makes the cost of this decision visible instead of silent - **if it
> is ever non-trivial, the trade needs revisiting.**"*

### What it may never do

> *"Nothing here can fail a request."*

Every publish is subscribed separately and errors are swallowed. **Kafka being
down degrades telemetry, never serving.**

---

## The second deadline: retention

Kafka holds unread messages for a limited window (24 h in this deployment's
shape).

```
metering down for 2 hours    ->  fine, it catches up
metering down for 30 hours   ->  the first 6 hours of events are GONE
```

So "nothing is lost, Kafka buffers it" has a clock on it. **A consumer outage
becomes data loss once it outlasts retention**, which is why consumer lag is
worth alerting on rather than merely watching.

---

## The one place that drops nothing

The control plane's **outbox** - tenant provisioning, key revocation, billing
state changes:

```
usage event      ->  fire-and-forget, may be dropped
key revocation   ->  outbox, guaranteed
```

A dropped usage event costs a fraction of a cent. A dropped key revocation means
**a revoked credential still works.**

> Same mechanism, opposite decision, chosen per data class by asking what losing
> one message costs. The full table is in
> [B6b1's Messaging section](B6b1-patterns-where-and-why.md).

---

**Previous:** [B7 — Redis](B7-redis.md).
**Next:** B9 — PostgreSQL.
