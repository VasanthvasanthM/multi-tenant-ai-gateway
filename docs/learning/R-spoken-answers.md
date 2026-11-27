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

---

## R3 again, in plain words — the restaurant

The table version above is for revision. This is the version to explain from.

A restaurant has five people:

- **the waiter** — takes your order, brings your food. **You sit and wait for him.**
- **the office person** — gave you a membership card months ago. Not involved tonight.
- **the bill writer** — notes what you ate, *after* the food is on the table.
- **the diary keeper** — writes "table 4, 8 PM, chicken" in case an inspector asks next year.
- **the fridge person** — packs tonight's curry away so tomorrow's order takes one minute, not thirty.

```
waiter        = gateway          the only service you wait for
office        = control-plane    signs tenants up, issues keys
bill writer   = metering         usage for billing
diary keeper  = audit            who asked what
fridge person = indexer          stores answers for Tier 2
```

> **Only the waiter makes you wait. The other four work after you have gone.**

The waiter does not walk over and brief each of them — that would make him slow.
He drops **three notes into a post box** and goes back to serving. The post box
is Kafka. He never checks whether anyone collected them. If the fridge person
went home early, the notes wait.

### Why not one person doing all five jobs

**1. You need different numbers of them.** Forty tables means forty waiters. It
still means *one* office person, because people sign up once, not every evening.
Gateway runs 488 pods; control-plane runs 2. One program means 488 copies of a
signup form.

**2. Some jobs can stop for an hour, and one cannot.** Fridge person absent:
dinner still works, just slower and costlier. Bill writer absent: food goes out,
billing catches up later from the post box. Waiter absent: the restaurant is
shut. If all five are one program, a crash while storing something in the fridge
**kills the waiter too** — a harmless problem just closed the restaurant. That is
blast radius.

**3. You want to change one job without stopping the others.** Tuning what the
fridge person keeps should not restart the thing serving customers.

### The line

Most splits are by **nouns** — UserService, OrderService — which just turns
function calls into network calls. This one is by a different question:
**is the customer waiting?** One service is, four are not, and the line between
them is Kafka.

> *"I split along the synchronous boundary, not along the nouns."*

### The honest parts

The indexer is **probably over-split** — small enough to live inside metering.
And embedding **should** be a service and is not: it runs inside the gateway, on
the request path, 30 ms of CPU per call, which drops a Tier-2 pod to 4-8 rps.
Like asking the waiter to grind the spices at your table. Logged as `A37`.

---

## R4 — Tier 1: Redis, keys, and rate limiting

> *"How does the cache actually work?"*

### The shop with a notebook

A customer asks a question. Before paying the expert, you check a notebook: *has
anyone asked this exact question before?* If yes, read out the old answer. If
no, pay the expert and **write the answer in the notebook** for next time.

That notebook is Redis. Looking in it takes **2 ms**. Asking the expert takes
**300 to 2000 ms** and costs money.

### Why the question text is not the key

Three problems with using the question itself:

1. **It is long.** Prompts run to thousands of characters; keys should be short.
2. **The same question can need different answers.** Same words, different model,
   or a different `temperature`, is a different answer.
3. **Tenants must never see each other's answers.** Acme's answer must not be
   served to Globex.

So the key is a **fingerprint** — SHA-256 over every input that can change the
answer. Same inputs, same 64-character fingerprint, every time, forever.

```
tenant + model + temperature + topP + maxTokens + tools
       + message count + every message (role + normalised text)
       -> 3f9a1c... (64 hex characters)
```

**Deliberately left out:** `stream` and `idempotencyKey`.
A streamed request should be allowed to hit an entry a buffered one stored — the
answer is the same, only the delivery differs. And the idempotency key identifies
*one retry of one call*, so including it would give every retry its own key and
destroy the point of the cache.

### The bug worth telling: two fields that ran together

The first version just concatenated the fields:

```
tenant "acme"  + model "gpt-4"   ->  "acmegpt-4"
tenant "acmeg" + model "pt-4"    ->  "acmegpt-4"      SAME KEY
```

Two different tenants, one key. A tenant who picks their own identifier could
**deliberately** craft one that collides with another tenant and read their
answers.

The fix is to hash the **length** of each field before the field itself:

```java
private static void putString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    putInt(digest, bytes.length);   // <- this line is the fix
    digest.update(bytes);
}
```

`4 acme 5 gpt-4` can no longer be confused with `5 acmeg 4 pt-4`. The message
**count** is hashed before the messages for the same reason, so one conversation
cannot be confused with a differently-split one carrying the same text.

### The Redis key shape

```
{tenant:acme}:c1:3f9a1c...
 ^^^^^^^^^^^^  ^^
 hash tag      Tier-1 namespace
```

The braces are a **Redis Cluster hash tag**: Redis hashes only what is inside
them, so every key for one tenant lands on the same node. That makes a
multi-key operation for one tenant possible at all — across nodes it would be
refused. The `c1` keeps cache entries distinct from that tenant's rate-limit
bucket and quota counter, which share the same hash tag.

### Two settings that matter

```
tier1-ttl: 1h                   every entry deletes itself after an hour
maxmemory-policy: allkeys-lru   when full, evict the least recently used
```

TTL is about **staleness** — an answer from last week may be wrong.
LRU is about **memory** — the cache must never refuse a write because it is full.
Together: entries leave either because they are old or because they are unpopular.

### If Redis dies, nothing breaks

Every Redis call here resolves its own failure to "empty" and logs a warning:

```java
.onErrorResume(error -> {
    errors.increment();
    log.warn("tier-1 lookup failed for {}: {}", key.shortForm(), error.toString());
    return Mono.empty();            // looks exactly like a cache miss
})
```

A miss just means calling upstream. **Redis down = expensive, not broken.** This
is called **failing open**, and it is the right choice for a cache: the cache
exists to save money, so losing it should cost money, not availability. A write
failure is treated the same way — the customer already has a valid answer, and
losing the chance to cache it is strictly better than discarding work they paid
for.

### Rate limiting: the bucket of coins

Each tenant has a bucket of coins. One request spends one coin. Coins drip back
in at a fixed rate, and the bucket has a maximum size.

```
capacity         200 coins     the biggest burst allowed
refillPerSecond   50 coins     the sustained rate
```

Idle for a while, the bucket fills to 200 and a burst of 200 is allowed. Keep
pushing and you settle at 50 per second. Empty bucket means `429`, with
`retryAfterMillis` saying **exactly** how long to wait — so a well-behaved client
backs off by the right amount instead of guessing and making the overload worse.

**Token bucket, not leaky bucket**, deliberately: a leaky bucket enforces a
perfectly uniform rate, and real clients are bursty — a batch job fires fifty
requests then idles a minute. That is well inside its average and a leaky bucket
would still reject it.

### Why the rate limiter is a Lua script

The obvious implementation is three steps: read the coin count, work out the new
one, write it back.

With **two gateway pods** serving the same tenant at the same moment:

```
pod A reads: 1 coin left        pod B reads: 1 coin left
pod A says:  allowed            pod B says:  allowed
```

One coin, two requests allowed. Under load this is not a rare race — it is the
**normal case**, and the real limit silently becomes *configured limit x number
of pods*.

Redis runs a script **atomically**: read, compute and write cannot interleave
with another pod's. That is the entire reason the logic lives in Lua rather than
Java.

Two more details inside it:

- **The clock comes from Redis** (the `TIME` command), not from the pod. Pods run
  on different machines with drifting clocks; a pod running a few seconds fast
  would store a future timestamp, and every other pod would then compute a
  negative elapsed time and refill nothing. Redis is the one clock they already
  share.
- **A new bucket starts full, not empty.** Starting empty makes a tenant's very
  first request fail, which looks exactly like an outage to them.

### The order detail from R2, restated

The rate limiter runs **before** the cache. A tenant hammering one cached prompt
is free in vendor money but still eats Redis connections, event-loop time and a
pod's attention. Cheap bill, real denial of service.

### Follow-ups it invites

| they ask | go to |
|---|---|
| "what if the prompt is only reworded?" | R5 |
| "how big is one entry?" | R9 — measured at 0.60 KiB, assumed 2.00 |
| "what happens when Redis is slow rather than down?" | R8 |

### R4 follow-ups — four questions worth their own section

#### Why exactly 64 characters?

It is not a choice. It is arithmetic.

```
SHA-256 produces 256 BITS
one hex character carries 4 bits (16 values)
256 / 4 = 64 characters
```

The same digest in other encodings: 32 raw bytes, or 44 base64 characters. Hex
is chosen because it is safe everywhere — no `/`, `+` or `=` to escape in a Redis
key, a URL or a log line — and it is case-insensitive, so a lowercasing log
pipeline cannot corrupt it.

**Why 256 bits and not 128?** Because a collision here does not mean a slow
lookup, it means **serving the wrong customer's answer**. Two different prompts
with one key is a correctness and privacy failure. The birthday bound says a 50%
chance of any collision needs about `2^128` keys. At 2x10^8 distinct prompts a
day — 7.3x10^10 a year — the probability is roughly:

```
N^2 / 2^257  =  (7.3x10^10)^2 / 2^257  =  about 1 in 10^56
```

**Why not MD5 (128 bits) or SHA-1 (160)?** Both are broken: an attacker can
deliberately construct two inputs with the same digest. That is not academic
here, because the tenant controls the prompt text — a chosen collision is a way
to read someone else's cached answer. **SHA-512** would work and buys nothing: it
doubles the key length, and key bytes are multiplied by hundreds of millions.

#### One real entry, end to end

Produced by running the project's own derivation (schema byte, then each field
length-prefixed) over a concrete request:

```
tenant       acme
model        gpt-4o-mini
temperature  0.0      topP 1.0      maxTokens 500
tools        (none)
messages     1 x user: "How do I reset my password?"
```

```
key    {tenant:acme}:c1:6c93c827c3013d4fe01e8bb9299941661452f632d74caac2a46a67e10840d5bf
value  {"v":1,"c":"Click \"Forgot password\" on the sign-in page and request a new
        link. Reset links expire after 30 minutes for security.","m":"gpt-4o-mini",
        "pt":14,"ct":62,"p":"openai","ts":1764201600000}
TTL    3600 s
```

```
key    81 bytes
value 199 bytes
             = 280 bytes of payload
```

That 280 is this hand-made sample, and it is NOT what the 0.60 KiB measurement
was divided over - that used longer text and a 356-byte payload. See "Where the
600 bytes came from" below for the real arithmetic.

Field names are one or two letters on purpose — at 10^8 entries a day, `"ct"`
instead of `"completionTokens"` saves tens of gigabytes of field names. `v` is
the schema version: an entry whose `v` is unrecognised is treated as a **miss**,
which is what makes the format safe to change during a rolling deploy.

Sensitivity of the key, same algorithm:

```
"...password?"        6c93c827c3013d4f...
"...password!"        ecb261a17c535ace...   one character, unrecognisable
tenant globex         b5798eade150cf28...   same prompt, different tenant
```

And the collision, demonstrated rather than asserted:

```
NAIVE   sha256("acme"  + "gpt-4")  f8aa6f9de8b2dc04...
NAIVE   sha256("acmeg" + "pt-4")   f8aa6f9de8b2dc04...   IDENTICAL
FIXED   4"acme" 5"gpt-4"           0f7323e18a8d29e2...
FIXED   5"acmeg" 4"pt-4"           c607a94ed99d497c...   different
```

The rate-limit bucket for the same tenant, which is why the hash tag exists:

```
{tenant:acme}:rl    hash: tokens -> 199.26, ts -> 1764201600123   TTL 60 s
```

#### The retry-after formula

Two formulas run on every call. **Refill first:**

```
tokens = min(capacity, tokens + elapsedMillis * refillPerSecond / 1000)
```

At capacity 200 and 50 coins/second:

```
idle     20 ms  ->    1.0 coins
idle    100 ms  ->    5.0
idle  1,000 ms  ->   50.0
idle  4,000 ms  ->  200.0   (full)
idle 10,000 ms  ->  200.0   (capped - idling longer earns nothing)
```

**Then, only if there are not enough coins:**

```
deficit          = requested - tokens
retryAfterMillis = ceil(deficit / refillPerSecond * 1000)
```

```
tokens=0.00  requested=1   deficit=1.00  ->  20 ms
tokens=0.26  requested=1   deficit=0.74  ->  15 ms
tokens=0.00  requested=5   deficit=5.00  -> 100 ms
tokens=3.00  requested=10  deficit=7.00  -> 140 ms
```

`ceil`, not `round`: rounding down tells the client to come back a moment before
the coin exists, so it fails again, retries again, and the limiter becomes a
retry generator. Rounding up is wrong by at most one millisecond in the safe
direction.

The reply also floors the remaining count — reporting 4 when 4.9 are available is
the safe direction for the same reason.

#### Leaky bucket, and why this is a token bucket

**The picture.** A bucket with a hole in the bottom. Requests are water poured
in. The hole drips at a **fixed** rate — that is the allowed rate. Pour faster
than it drips and the bucket fills; once it is full, extra water spills and those
requests are rejected.

The defining property: **the output rate is perfectly constant.** A leaky bucket
smooths traffic. Fifty requests arriving together leave one every 20 ms, in a
queue, whatever the sender does.

**Token bucket is the mirror image.** Instead of water leaving at a fixed rate,
*allowance arrives* at a fixed rate and is **saved up** while idle. Output can
burst up to whatever has been saved.

Same client, idle one minute, then 50 requests at once, at 50/second:

```
LEAKY  (queue of 10)     10 queued and served one per 20 ms, 40 rejected
TOKEN  (capacity 200)    all 50 pass immediately; the bucket drops to 150
```

Both enforce 50/second on average. They disagree completely about bursts.

**Leaky bucket is right** when the thing downstream cannot survive a burst — a
legacy system, a disk, a vendor with a hard instantaneous cap. It protects by
making the shape of the traffic uniform.

**Token bucket is right here** for two reasons. Real clients are bursty: a batch
job fires fifty requests and then idles a minute, which is far inside its average
and a leaky bucket would reject most of it. And a burst is genuinely cheap for
this system — 81.8% of it is answered from Redis in 2 ms, so there is no fragile
downstream to protect.

There is a third reason, which is about this codebase specifically: a leaky
bucket implies a **queue** — requests wait for their drip. This gateway
deliberately does not queue under pressure, it **sheds** (`max-in-flight: 64`,
measured, not chosen). Adding a queue in the limiter would reintroduce exactly
the latency the admission control exists to prevent.

#### Where the 600 bytes came from

It was not calculated from Redis internals. **It was divided.**

```
                 used_memory_dataset
bytes per entry = -------------------
                        DBSIZE
```

Both sides come from Redis itself: `INFO memory` reports how much memory the data
occupies, `DBSIZE` reports how many keys there are. Write a known number of
real-shaped entries, ask Redis how much memory it is using, divide.

**The exact entries that produced it.** `Tier2CostBenchmarkIT` wrote 500 keys in
the shape `Tier1Cache` writes:

```
key    {tenant:acme}:c1:0000...0000                              81 bytes, always
value  {"v":1,"c":"Click 'Forgot password' on the sign-in page
       and request a new link. Reset links expire after 30
       minutes for security. If link 0 also fails, clear your
       browser cache and try an incognito window.","m":"gpt-4o-
       mini","pt":22,"ct":48,"p":"openai","ts":1763049600000}   273-275 bytes
```

```
payload per entry   81 + 275 = 356 bytes
payload for 500             = 177,890 bytes

MEASURED  0.60 KiB/entry  = 614 bytes  ->  307,200 bytes for 500 keys
overhead                  = 614 - 356  =  258 bytes per entry, 42% of the total
```

The recorded figure is rounded to two decimals, so the true overhead is somewhere
in **253-263 bytes**. Nothing finer than that can be claimed from it.

**What that 258 bytes is made of**, as far as the published structure sizes go —
64-bit Redis 7.4 on jemalloc:

```
key string      3-byte sds header + 81 + null = 85 needed -> 96 allocated
value string    3-byte sds header + 275 + null = 279      -> 320 allocated
robj header on the value                                      16
main hash-table entry, three pointers                         24 -> 32
hash-table bucket slot, amortised                             ~8
a SECOND entry in the expiry table, because the key has a TTL  ~32
                                                             -----
                                                              ~504 allocated
                                                              -356 payload
                                                             = ~148 named
```

So roughly 148 of the 258 bytes have names; the remaining ~110 is allocator slack
and per-database bookkeeping that **this measurement does not break down**.
`MEMORY USAGE <key>` on a live instance would settle it exactly, and that needs
Docker running. Until then: 614 is measured, the breakdown above is partly
estimated, and the two should not be quoted with the same confidence.

**Two honest caveats about the 614 itself:**

1. **It is for an answer of this length.** The response text here is ~190
   characters. If real answers average 1,000 characters, the entry is ~1.3 KiB,
   not 0.6. The measurement replaced an assumption about *Redis overhead* with a
   fact; the assumption about *how long an answer is* is still an assumption, and
   it is a property of the workload rather than of the code.
2. **So 2 KiB was not a crazy guess** — it assumed a much longer answer. What was
   wrong with it was not the arithmetic but that nobody had checked it.

**The lesson that generalises:** at this entry size, Redis bookkeeping is 42% of
the memory. Any capacity plan that counts only the bytes it writes will undercount
by nearly half, and the smaller the entries the worse it gets.

#### Leaky bucket, on its own terms

Forget tokens and coins. This one is literally a bucket of water.

```
        requests arrive, 1 litre each
                 |  |  |
                 v  v  v
            +-------------+
            |             |   <- capacity 5 litres
            |~~~~~~~~~~~~~|      full? extra water SPILLS and is rejected
            |~~~~~~~~~~~~~|
            +------+------+
                   |
                   v   a hole, draining at a FIXED rate
             1 litre per 100 ms  =  10 requests per second
```

Three numbers describe it, and only three:

```
inflow      whatever the clients happen to send - uncontrolled
hole rate   1 per 100 ms       the rate you are enforcing
capacity    5 litres           how many may WAIT
```

**Worked example.** Capacity 5, hole 1 per 100 ms:

```
time    event                  level   what happened
0 ms    3 requests arrive      0 -> 3  all 3 accepted, and they WAIT in the bucket
100     hole drains            3 -> 2  request 1 is served
200     hole drains            2 -> 1  request 2 is served
250     4 requests arrive      1 -> 5  all 4 accepted; bucket now exactly full
300     hole drains            5 -> 4  request 3 is served
350     3 requests arrive      4 -> 5  1 accepted, 2 REJECTED - the bucket overflowed
400     hole drains            5 -> 4
500     hole drains            4 -> 3
600     hole drains            3 -> 2
700     hole drains            2 -> 1
800     hole drains            1 -> 0  empty again
```

Now read one request's life out of that table. A request that arrived at
**250 ms** was fifth in the bucket, so the hole reached it at **600 ms**. It
waited **350 ms** — and it did nothing wrong; it simply arrived behind four
others.

That is the whole character of a leaky bucket:

- **The output is a perfectly steady drip.** Exactly 10 per second leave, however
  they arrived. Sixty arriving in one millisecond still leave one per 100 ms.
- **Burstiness is absorbed by delay, not by permission.** The bucket does not say
  "yes, you saved up allowance". It says "yes, wait your turn".
- **Rejection happens only on overflow**, and it is the *newest* request that is
  refused.

**Where it is genuinely the right tool:** a printer queue; an ISP shaping a link
to exactly 100 Mbps; a fragile legacy system that falls over above a fixed
instantaneous rate; a vendor API with a hard "never more than N at once" cap.
Anything where a burst is **physically** unacceptable and waiting is better than
failing.

**Why this gateway does not use it**, in one line: a leaky bucket's defining
feature is a **queue**, and this gateway deliberately refuses to queue under
pressure — it sheds at `max-in-flight: 64`, because a queue deeper than the
500 ms Redis timeout turns every Tier-1 lookup into a paid upstream call and the
extra load deepens the queue further. A leaky bucket would build the exact
structure the admission control exists to prevent.

> Footnote for completeness: there is a second form, the **leaky bucket as a
> meter** (the GCRA), which keeps no queue and only tracks a theoretical arrival
> time. That form is mathematically equivalent to a token bucket, which is why
> the two names sometimes get used interchangeably. The queue form above is what
> people normally mean, and it is the one that actually differs.

#### Why the key is exactly 81 bytes

Count it:

```
{          1
tenant:    7
acme       4      <- the tenant id
}          1
:          1
c1         2      <- the Tier-1 namespace
:          1
<digest>  64      <- the SHA-256, in hex
--------  --
          81
```

Two separate reasons it does not vary, and they are worth keeping apart.

**1. The digest half is 64 characters whatever the prompt was.** That is the
defining property of a hash function: any input length produces a fixed output
length. A three-character prompt and a thirty-thousand-character prompt both come
out as exactly 64 hex characters. Contrast the *value*, which does vary - it
contains the answer text.

**2. The prefix half is fixed for a given tenant.** `{tenant:acme}:c1:` is 17
characters and never changes for `acme`.

So "always 81" is true **for this tenant**, not in general:

```
key length = 13 + len(tenant) + 64 = 77 + len(tenant)
             ^^
             "{tenant:" (8) + "}" (1) + ":c1:" (4)
```

Tenant ids are constrained by `^[a-z0-9][a-z0-9_-]{2,63}$`, so 3 to 64
characters, and real keys therefore range **80 to 141 bytes**. The benchmark used
one tenant, `acme`, so every one of its 500 keys was 81.

**And characters equal bytes here** because every part is ASCII - hex digits, and
a tenant id restricted to `[a-z0-9_-]`. That is why the count can be exact at
all. Allow Unicode tenant ids and `length()` stops predicting byte count, which
is the same trap as the Tier-2 code-point bug.

**One substitution to know about.** The benchmark did not compute real digests; it
wrote `String.format("%064x", i)` - the loop counter padded to 64 hex characters.
Legitimate for a size measurement, because the length is identical to a real
digest and Redis stores strings raw with no compression, so a low-entropy string
costs exactly what a high-entropy one does. But it is a stand-in, not a real key.

#### Fill rate and leak rate, in this codebase

First the mapping, because the two models name things differently:

| leaky bucket | this token bucket | where | value |
|---|---|---|---|
| **leak rate** - the drain, the rate you enforce | `refill-per-second` - the rate allowance arrives | `application.yml` | **20 / second** |
| **capacity** - how many may *wait* | `capacity` - how big a *burst* may pass | `application.yml` | **100** |
| water poured in - uncontrolled | one token spent per request | `tryAcquire(tenant)` | **1** |
| overflow spills | empty bucket -> `429` | `token_bucket.lua` | - |

The real configuration, `gateway/src/main/resources/application.yml`:

```yaml
  rate-limit:
    enabled: true
    capacity: 100                 # burst size
    refill-per-second: 20         # sustained rate
    fail-open: true
```

Earlier examples in this document used 200 and 50 to keep the arithmetic round.
The committed defaults are **100 and 20**, and they are development defaults -
per-tenant production limits would be set far higher.

Everything the script needs, and where it comes from:

```
ARGV[1] capacity          100     application.yml
ARGV[2] refillPerSecond    20     application.yml
ARGV[3] requested           1     TokenBucketRateLimiter.tryAcquire(tenant)
ARGV[4] ttlSeconds      3,600     TokenBucketRateLimiter.BUCKET_TTL_SECONDS
KEYS[1] bucket key              {tenant:acme}:rl
```

The refill line itself, which is the nearest thing in this system to a leak rate:

```lua
tokens = math.min(capacity, tokens + (elapsedMillis * refillPerSecond / 1000.0))
```

At 20 per second that is **one token every 50 ms**, and 100 / 20 = **5 seconds of
idling to refill from empty to full**.

```
idle     50 ms  ->    1.0 tokens
idle    100 ms  ->    2.0
idle  1,000 ms  ->   20.0
idle  5,000 ms  ->  100.0   full
idle 10,000 ms  ->  100.0   capped
```

And the retry-after, recomputed on the committed numbers:

```
tokens=0.00  requested=1   short 1.00  ->  ceil(1.00/20*1000)  =  50 ms
tokens=0.26  requested=1   short 0.74  ->  ceil(0.74/20*1000)  =  37 ms
tokens=0.00  requested=5   short 5.00  ->  ceil(5.00/20*1000)  = 250 ms
tokens=3.00  requested=10  short 7.00  ->  ceil(7.00/20*1000)  = 350 ms
```

**The bucket TTL is 3,600 s** and is not a rate at all - it is how long an idle
bucket survives before Redis deletes it. Deleting is safe precisely because a
missing bucket is recreated **full**, which is the same state an hour of
refilling would have produced anyway. Without it, a million one-off tenants would
occupy Redis memory permanently.

**Two honest notes.**

`tryAcquire(tenant, tokens)` takes a variable cost so an expensive call can draw
down more than one token - and nothing calls it with anything but 1. A
30,000-character prompt costs a 10-character one exactly the same allowance
today, which is wrong in an obvious way and is a small change to fix.

And there is **no leak rate in this system**, strictly speaking, because there is
no queue to drain. `refill-per-second` is the *mirror* of a leak rate: the same
sustained ceiling, arrived at by handing out permission over time instead of by
draining a backlog over time.
