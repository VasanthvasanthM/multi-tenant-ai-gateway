# B4a — Why a broken service does not break a request

The claim is "the services are asynchronous, so if one fails the request still
works." This is how that actually holds up, and what it costs.

Plain words. Verified against `GatewayEventPublisher`, `CompletionPipeline` and
[ADR-0008](../adr/0008-outbox-only-off-hot-path.md).

---

## Part 1 — Why nothing breaks

### The gateway never calls those services

There is no HTTP call from the gateway to metering, audit or the indexer. The
gateway writes a message to Kafka. Those services read it later, in their own
time.

So "metering is down" is not something the gateway can even notice. There is no
connection to fail.

Compare the other design. If the gateway had to call metering before replying:

- metering slow → gateway slow
- metering down → gateway down
- your uptime becomes everyone's uptime **multiplied**: 99% × 99% × 99% = 97%

That is the entire reason for the rule in
[ADR-0010](../adr/0010-no-synchronous-inter-service-calls.md). One service
cannot drag another down if it never waits for it.

### The message is started, never waited for

```java
sender.send(...)
    .subscribeOn(Schedulers.boundedElastic())   // on another thread
    .subscribe();                               // fire it, do not wait
```

`subscribe()` starts it and returns straight away. The network write happens on
a different thread. `publishUsage()` returns in microseconds whether Kafka is
healthy, slow, or gone.

`subscribeOn(boundedElastic())` also keeps that work off the event-loop thread
that is serving requests.

### Three walls stop an error getting back

```java
try {
    String payload = objectMapper.writeValueAsString(event);
    sender.send(...)
        .doOnError(error -> { dropped.increment(); log.debug(...); })
        .onErrorResume(error -> Mono.empty())     // wall 2
        .subscribe();
} catch (Exception e) {                            // wall 1
    dropped.increment();
    log.debug("could not emit {} event: {}", topic, e.toString());
}
```

1. **try/catch** catches anything that throws immediately — a serialisation
   failure, the sender refusing work.
2. **`onErrorResume(... Mono.empty())`** turns an asynchronous failure into
   "nothing happened".
3. **It returns `void`.** There is no result for the request path to inspect.

An exception cannot travel back into the request. Note the level is **debug**,
not error: a telemetry problem should not fill the error log either.

---

## Part 2 — What you lose

### A concrete story

Kafka breaks at 2pm. Nobody notices for an hour.

- A customer sends **1,000 requests**
- The gateway answers **all 1,000** normally. Fast, correct, no errors
- The customer is happy and has no idea anything is wrong
- But the 1,000 notes saying *"this request happened, charge for it"* were
  **thrown in the bin**

At 3pm Kafka comes back. Those notes are **gone**. They are not waiting in a
queue. Nobody retries them.

Your billing report for that hour shows **zero requests** for that customer. You
served 1,000 and charged for none.

**That is the loss: the record, not the request.**

### Why that is the right trade

| choice | what happens when Kafka is down |
|---|---|
| wait for Kafka before answering | no request can finish. Every customer gets an error. You are offline |
| answer anyway, bin the note | every customer is served. You lose an hour of billing records |

An hour of lost billing is a small amount of money. An hour of downtime is lost
customers.

### The shop analogy

**Fire-and-forget:** hand over the coffee, then write the sale in a notebook. If
the notebook goes missing the customer still got their coffee; you just cannot
prove you sold it.

**Outbox:** refuse to hand over the coffee until the sale is written down and
confirmed. Slower, but you never lose a record.

Use the notebook for selling coffee. Do **not** use the notebook for *"is this
person banned from the shop?"*

### Two things that make the loss safe

**The mistake falls in the safe direction.** A lost usage note means you charge
**less** than you should. You lose money; the customer is never overcharged.
Overcharging causes complaints, refunds and lost trust. Undercharging costs you
a little. If it must go wrong, this is the direction to want.

**Some of it fixes itself.** If the lost note was "save this answer into the
semantic cache", the answer is simply not cached. The next person asking the
same question gets a miss, goes to the model, and the save is attempted again.
Nothing is permanently broken.

### Where loss is NOT acceptable

ADR-0008 decides this per kind of data, by asking what losing it costs:

| data | loss costs | mechanism |
|---|---|---|
| usage event | a fraction of a cent, and the error is **under**-billing | fire-and-forget |
| audit event | one log line | fire-and-forget |
| cache-write event | one entry not indexed; the next prompt re-caches it | fire-and-forget |
| tenant provisioning | a tenant stuck half-created, needs manual repair | **outbox** |
| **key revocation** | **a revoked credential still working** | **outbox** |
| billing state change | a wrong invoice | **outbox** |

The top three heal themselves. The bottom three do not, and one is a security
boundary.

With the outbox, the state change and the message are written in **one database
transaction**. Either both happen or neither does, so the message cannot go
missing. A relay polls the table with `FOR UPDATE SKIP LOCKED` and publishes.

So the design is not "async everywhere". It is:

> **Throw away cheap notes to stay fast. Guarantee the ones you cannot recover
> from losing.**

ADR-0008 notes that `GatewayEventPublisher` carries an explicit comment saying
it is deliberately **not** outboxed, so nobody later "fixes" it and puts a
database write back on the hot path.

---

## Part 3 — What can still break a request

Not everything is survivable, and pretending otherwise would be the same
dishonesty this project is trying to avoid.

| dependency | if it fails |
|---|---|
| metering, audit, indexer | **nothing happens.** Request succeeds |
| Kafka | **nothing happens.** Events dropped, request succeeds |
| Redis | rate limiter **fails open**, Tier 1 counts a miss. Request succeeds, slower and more expensive |
| PostgreSQL | Tier 2 counts a miss. Request succeeds, goes upstream |
| **the LLM provider** | **the request fails**, after the fallback chain is exhausted |

Only the last one breaks a request, and that is unavoidable — it is the one
component doing the actual work. Everything else is an optimisation or a
bookkeeping job, and the system is arranged so those **degrade** instead of
failing.

> **The one-sentence version:** a failure only breaks a request if the request
> was waiting for it — so the design is mostly about making sure nothing waits
> for anything it does not absolutely need.

### How to see it for yourself

```bash
# events dropped because the broker could not be reached
curl -s http://localhost:18081/actuator/prometheus | grep gateway_events

# the limiter failing open, which is the Redis case above
kubectl logs deploy/aigw-ai-gateway-gateway | grep "failing open"
```

A rising `dropped` counter with a healthy request rate is the whole design
working as intended: telemetry being sacrificed to keep the product up.

---

**Next:** [B5 — the HLD](B5-hld.md).
**Previous:** [B4 — the request flow](B4-request-flow.md).
