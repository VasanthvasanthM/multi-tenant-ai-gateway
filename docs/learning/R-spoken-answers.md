# Spoken answers — the second pass

One page per question, in the order the questions arrive. See
[REVISION-PLAN.md](REVISION-PLAN.md) for the full list and where the depth lives.

These are written to be **said out loud**, not read. Each one has an opening
line, one number, and a deliberate hook that chooses the follow-up question.

---

## R1 — The 60-second pitch

> *"Tell me about this project."*

I built a multi-tenant AI gateway with a two-tier semantic cache.

The problem is that most production LLM traffic is the same questions asked over
and over, and every one of them is billed. A support bot answering *"how do I
reset my password"* five thousand times pays five thousand times for one answer.

So the gateway sits in front of any OpenAI-compatible provider and answers
repeats from cache. Tier 1 is an exact hash match in Redis — 2 to 5 ms. Tier 2
is a semantic match in pgvector, so a *reworded* question still hits.

**81.8% of requests never reach a vendor.** I measured one pod at 271 requests a
second and scaled that to a fleet for two billion requests a day.

The part I would want to talk about is what measuring it honestly turned up —
including a cache failure mode where raising the similarity threshold makes it
*less* safe, not more.

### Why it is shaped that way

- **What it is, not how it is built.** No framework names in the first sentence.
- **The problem is concrete.** Five thousand times for one answer, not "costs are high".
- **One number.** `81.8%` is the whole value proposition. Four numbers and they remember none.
- **It hands over the next question.** The threshold line is counter-intuitive, so they ask —
  and it is the strongest story. The pitch is a menu.

### The careful bit

Never say *"designed for billions"* without immediately saying what was
measured. *"I measured one pod and calculated the fleet from it"* is stronger,
because it shows you know the difference between a measurement and an
extrapolation.

### Follow-ups it invites

| they ask | go to |
|---|---|
| "what's the second tier for?" | R5 |
| "how do you know 271 is real?" | R9 |
| "what's this threshold thing?" | R11 |

---

## R2 — One request, end to end

> *"Walk me through what happens to a request."*

Eight stages, and the order is the whole design: **each stage sits where it
rejects the most work for the least cost.**

```
1  validate          pure CPU, no I/O
2  rate limit        1 Redis round trip
3  budget            2 Redis reads (off by default)
4  admission control in-memory counter
5  Tier 1            Redis exact hash          ~2 ms
6  Tier 2            embed + pgvector ANN      ~30 ms embed + search
7  upstream          the vendor                 300-2000 ms, billed
8  store             both tiers, fire and forget
```

Stages 1 to 4 are all **rejection** — they only ever say no. Nothing expensive
happens until stage 5. Validation is first because rejecting an oversized prompt
costs nothing at all; upstream is last because it is the only genuinely
expensive step.

### The one detail that shows you thought about it

**Rate limiting comes *before* the cache, not after.**

The naive order is cache first — why rate-limit a request that is free to serve?
Because it is not free. A tenant hammering one cached prompt would consume
unlimited capacity: Redis connections, event-loop time, a pod's attention. Cheap
for the vendor bill, still denial of service for everyone else.

The budget gate has a sharper version of the same reason: **a cache hit spends
the tenant's quota.** If the budget were checked after the cache, an exhausted
tenant would be served every cached answer for free forever.

### Where it is in the code

[CompletionPipeline.java:155-200](gateway/src/main/java/io/vasanth/gateway/pipeline/CompletionPipeline.java#L155-L200)
is the whole chain, and it reads like the list above:

```java
return validate(request)
        .then(Mono.defer(() -> rateLimitGate.check(request.tenantId())))
        .then(Mono.defer(() -> budgetGate.check(request.tenantId())))
        .then(Mono.defer(() -> withAdmissionControl(serve(request))))
```

and `serve` is three lines, the fall-through from cheap to expensive:

```java
return tier1.get(key)
        .switchIfEmpty(Mono.defer(() -> tryTier2(request, key, promptText)))
        .switchIfEmpty(Mono.defer(() -> callUpstream(request, key, promptText)));
```

### Two things to say if they dig

**`Mono.defer`, not a bare call.** `then(x)` evaluates `x` when the chain is
*assembled*, so `rateLimitGate.check(...)` would run even when validation had
already failed. Deferring makes the laziness explicit instead of relying on
every collaborator returning a cold publisher — which nothing enforces.

**Stage 8 never delays the response.** The usage event and the budget drawdown
both happen after the response is assembled, fire-and-forget. What that risks is
enforcement accuracy, not billing accuracy: the authoritative figure is written
to `usage_ledger` by the metering service, from the Kafka event, idempotently.

### Follow-ups it invites

| they ask | go to |
|---|---|
| "what is admission control?" | R8 |
| "what happens on a streaming request?" | below |
| "how does the event reach metering?" | R6 |

**Streaming, in one sentence:** it consults neither tier and populates neither,
because reassembling a response from deltas in order to cache it means buffering
every streamed response in memory, and at high concurrency that costs more than
the hit rate buys. It *is* admission-controlled though — and it was not until
2026-11-16, which had it backwards: a stream is the longest-lived request shape
there is, so the requests holding a pod's resources longest were the only ones
that could not be shed.

---

## R3 — Five services, and why they are separate

> *"Why microservices? Why not one application?"*

Five services, and **only one of them is in the request path.**

```
gateway         the front counter   - serves every request, the only sync service
control-plane   reception desk      - signs tenants up, hands out keys
metering        the meter reader    - records what was used, for billing
audit           the logbook         - writes down who asked what, permanently
indexer         the filing clerk    - files answers so Tier 2 can reuse them
```

The gateway answers and then drops three events into Kafka. The other three
services consume them. **Nobody waits for any of that.**

### Why they are split — the real answer

Not "scalability". The honest answer is that these four things differ on axes
that force separate deploys:

| | scales with | can it be down for an hour? | writes |
|---|---|---|---|
| gateway | request rate — 488 pods | **no** — that is the outage | never blocks on one |
| control-plane | tenant count — 2 pods | yes, nobody notices | transactional + outbox |
| metering | request rate | yes, Kafka holds the backlog | **must not lose one** |
| audit | request rate | yes, same | append only, never updated |
| indexer | distinct prompts | yes — **and cache quality just drops** | bulk |

Three things fall out of that table:

1. **Different scaling axes.** The gateway scales with traffic; the control-plane
   is touched once per tenant *lifetime*. Putting them together means scaling a
   signup form to 488 replicas.
2. **Different failure tolerance.** The indexer can be down all afternoon and
   nothing breaks — Tier 2 just stops improving. Metering down means you cannot
   bill. Those two must not share a deployment, because then they share a blast
   radius and the weakest guarantee wins.
3. **Different consistency needs.** The control-plane needs a transactional write
   plus an outbox, so a tenant's config and the event announcing it cannot
   diverge. The gateway must never block on a write at all. Opposite
   requirements, same codebase, is how you end up with one of them wrong.

### The line to land it

> **"The split is along the synchronous boundary, not along the nouns."**

The usual bad microservice split is one service per database table — `UserService`,
`OrderService` — which just turns method calls into network calls. Here the cut
is: *is the customer waiting?* One service is, four are not, and that boundary is
Kafka.

### Be honest about the cost

It is nine Maven modules for a system one person runs. The split is defensible
for metering and audit — regulated data, different retention, must not lose
writes — and it is **arguably over-split for the indexer**, which is small
enough to live inside metering. What it bought is independent deploys: pushing a
new similarity threshold does not restart the thing that bills people.

### One service that should exist and does not

**Embedding is a library, not a service** — it runs in the gateway's own process,
on the request path. The measurement in R9 says that is wrong: at 30 ms per
embedding it is the dominant cost of the whole system, and a pod serving Tier 2
managed 4-8 rps. The fix is to make it the sixth service, behind its own
autoscaler, so it scales on *embeddings per second* instead of inheriting the
gateway's pod count. That is logged as `A37` and not yet done.

Saying that out loud is better than hiding it: it shows the measurement changed
the design.

### Follow-ups it invites

| they ask | go to |
|---|---|
| "what exactly is in those three events?" | R6 |
| "what's an outbox?" | R6 |
| "why is embedding so expensive?" | R9 |
