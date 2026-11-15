# B4 — One request, end to end

What happens between your `curl` and the answer coming back. Traced from
`CompletionPipeline.java`, not from memory.

Plain words. Every step says **why it is in that position**, because the order is
the design.

---

## The whole thing on one page

```
   POST /v1/chat/completions
            │
   1.  ApiKeyAuthFilter        who are you?            -> 401 if unknown
            │
   2.  validate                is this request sane?   -> 400 if not
            │
   3.  rate limit              are you over your rate? -> 429 if so
            │
   4.  budget                  any tokens left?        -> 402/429 if not
            │
   5.  admission control       is the pod too busy?    -> 503 if so
            │
   6.  Tier 1   Redis, exact hash        ──hit──►  return  (2-5 ms)
            │ miss
   7.  Tier 2   pgvector, meaning        ──hit──►  return  (~100 ms)
            │ miss
   8.  upstream provider (+ fallbacks)             (0.5-30 s)
            │
   9.  write into Tier 1 and Tier 2
            │
  10.  respond, then fire-and-forget: usage event, budget drawdown
```

Steps 1–5 are all **cheap rejections**. They come first on purpose: it costs
almost nothing to say no, so say no before spending anything.

---

## Step by step

### 1. Who are you? — `ApiKeyAuthFilter`

A `WebFilter`, so it runs before the pipeline is even entered. It reads the
`Authorization: Bearer sk-...` header, hashes the key with SHA-256, and looks the
hash up in Redis to find the tenant.

**The hash, not the key, is what is stored.** A dump of Redis gives an attacker
nothing they can use.

No valid key means **401** and nothing else happens. This is why an unconfigured
API key once made a load test look fantastic: every request was rejected here, in
well under a millisecond, and the "throughput" being measured was the speed of
saying no.

### 2. Is this request sane? — `validate`

Shape checks. Unknown model, empty messages, malformed body → **400**.

### 3. Are you over your rate? — `rateLimitGate`

A token bucket per tenant, held in Redis, updated by a Lua script so the
read-modify-write cannot race between pods.

Over your rate → **429**.

**If Redis is unreachable this allows the request** (`fail-open: true`). A cache
outage should not take the product down. The cost is that a Redis problem
silently switches the limiter off, so there is a metric and an alert for exactly
that.

### 4. Any budget left? — `budgetGate`

Per-tenant token allowance for the period. Out of budget → refused here, before
the provider can bill you.

### 5. Is this pod too busy? — `withAdmissionControl`

A counter of requests currently in flight. Over the limit → **503 Overloaded**,
immediately.

This is the step people leave out, and it is the one that keeps a gateway alive.
Without it an overloaded service accepts everything, queues it, and every client
times out — you fail *all* requests slowly instead of *some* requests fast.

Note the `doFinally` in the code: the in-flight count is decremented on **every**
outcome, including a client disconnecting mid-request. Decrementing only on
success leaks a permit per dropped connection, and the pod slowly strangles
itself until a restart.

### 6. Tier 1 — exact match, Redis

SHA-256 of the normalised prompt plus model and tenant. One Redis `GET`.

Hit → return in **2–5 ms**. Done.

It is first because it is the cheapest possible answer and **cannot be wrong**.

### 7. Tier 2 — meaning match, pgvector

Only reached when Tier 1 missed. Before spending any CPU, three guards:

| guard | why |
|---|---|
| is Tier 2 switched on? | nothing to do if not |
| is the request eligible? | random sampling or tool definitions mean a similarity match cannot honour what was asked |
| is the prompt under the length limit? | the model truncates long text, and truncation turns two different long prompts into one identical vector |

The comment in the code states the reason for this ordering plainly: embedding
costs about 6 ms of CPU, so every guard that can reject without it is checked
first.

Then: turn the prompt into 384 numbers, search for the nearest stored vector in
the tenant's shard, compare to the threshold.

Hit → return in about **100 ms**, and **write it into Tier 1** so the next
identical prompt costs 2 ms instead of another vector search.

If shadow mode is on, the match is logged and thrown away. See
[B2's note on shadow mode](B2-the-idea.md).

### 8. The actual model — `callUpstream`

An ordered chain: primary provider, then named fallbacks on failure. Wrapped in a
timeout, which maps to a clean `UpstreamTimeout` error rather than a hanging
request.

### 9. Store the answer — `storeQuietly`

Writes into Tier 1 and Tier 2. The name is the point: it does not delay or fail
the response. The caller already has a valid answer; a cache write problem must
not turn that into an error.

### 10. Respond, then do the bookkeeping

After the response is assembled, two things happen **fire-and-forget**:

- a usage event is published for the metering service
- the tenant's budget is drawn down in Redis

Both deliberately happen after, and neither can fail the request. The code is
explicit about the trade: what is risked is *enforcement accuracy*, not *billing
accuracy* — the authoritative figure is written to `usage_ledger` by the metering
service from that event, and that ledger is idempotent.

---

## Why the order is the design

Read the list again as a sequence of costs:

| step | cost of rejecting here |
|---|---|
| auth | ~0 |
| validate | ~0 |
| rate limit | one Redis call |
| budget | one Redis call |
| admission control | ~0 |
| Tier 1 | one Redis call |
| Tier 2 | 6 ms CPU + a vector search |
| upstream | money, and seconds |

**Each step is more expensive than the one before.** That is the whole principle:
spend the least possible before finding a reason to stop.

Put auth after Tier 2 and you would embed prompts for people who are not
customers. Put admission control last and an overloaded pod would do all the work
before refusing. The order is not taste, it is arithmetic.

---

## Two things that are easy to miss

**`Mono.defer` everywhere.** In Reactor, `then(something())` runs
`something()` when the chain is *built*, not when it runs. Without `defer`, the
rate-limit check would be invoked even for a request that already failed
validation. The comment in the code notes this is harmless only while every
collaborator returns a cold publisher — a property nothing enforces — so the
laziness is made explicit rather than left accidental.

**Streaming skips both caches.** A streamed request consults neither tier and
populates neither. Reassembling a full answer from the stream in order to cache
it would mean holding every streamed response in memory, and at high concurrency
that costs more than the hit rate is worth.

But the cache key deliberately **ignores** the `stream` flag — so a normal
request can still be answered by an entry a streamed one would have created.

---

## See it yourself

```bash
curl -s -H 'Host: ai-gateway.local' \
  -H 'Authorization: Bearer sk-local-dev-acme-00000000000000000000' \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:18080/v1/chat/completions \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"hello"}]}'
```

The response carries a `gateway` block naming which step answered:

```json
"gateway": { "served_by": "upstream",      "cache_hit": false, "latency_ms": 40 }
"gateway": { "served_by": "tier1_exact",   "cache_hit": true,  "latency_ms": 3  }
"gateway": { "served_by": "tier2_semantic","cache_hit": true,  "latency_ms": 101 }
```

Run it twice and watch `served_by` change. That one field is the entire flow,
reported per request.

> **Warm it up first.** The very first cached hit on a cold JVM measured
> **158 ms** against the upstream call's 40 ms, because that request pays to
> compile the cache path. After about eight requests it settles to 2–5 ms.

---

**Next:** [B5 — the HLD: services and boundaries](B5-hld.md).
**Previous:** [B3 — problems it solves](B3-problems-solved.md).
