# B2 — Where the idea came from

The provenance, honestly stated, and then the reasoning that makes the idea hold
up — which is the part worth being able to defend in an interview.

---

## 1. Provenance, plainly

**The brief came from a design blueprint**, not from a flash of insight. That
document specified a multi-tenant AI gateway with a two-tier semantic cache, and
this repository is an implementation of it.

Say that plainly if asked. "I was given a specification and implemented it" is
an ordinary and respectable answer; claiming a personal origin myth that does not
survive one follow-up question is not.

**What is genuinely yours is everything below the brief.** A blueprint says
*what*; every *how* was a decision made during construction, and in five places
the blueprint was **wrong and was overruled** — each with a written argument.
That is recorded in [`docs/adr/README.md`](../adr/README.md):

| the blueprint said | what was built instead | why |
|---|---|---|
| embedding source unspecified | in-process embeddings | [ADR-0002](../adr/0002-in-process-embeddings.md) — a network hop blows the Tier-2 latency budget |
| Kafka **inside** the request path | Kafka **beside** it | [ADR-0003](../adr/0003-kafka-beside-request-path.md) — a broker hiccup must not fail a request |
| PgBouncer in front of R2DBC | no PgBouncer | [ADR-0004](../adr/0004-no-pgbouncer.md) — breaks prepared statements; R2DBC already pools |
| "3 × 5,000 = 15,000 RPS" | per-node curve + explicit extrapolation | [ADR-0013](../adr/0013-usl-not-headline-rps.md) — multiplying one node by three is not a scaling claim |
| leaky bucket rate limiter | token bucket | [ADR-0005](../adr/0005-token-bucket.md) — real API clients are bursty |

**Deviation 4 is the one to talk about.** The blueprint contained the sentence
every capacity plan wants to contain: three nodes at five thousand requests per
second each, therefore fifteen thousand. That multiplication is the single most
common error in capacity planning — it assumes nodes do not interfere with each
other, which is the exact thing that needs proving. Replacing it with a measured
single-node curve and a stated extrapolation, with the unproven coefficient
*named*, is a better engineering answer than the number it replaced.

---

## 2. Why the idea is sound

Forget the blueprint for a moment. If you had to arrive at this product
yourself, the chain of reasoning goes like this.

### Step 1 — LLM calls break the assumptions of normal API design

| | normal REST call | LLM call |
|---|---|---|
| latency | 5–50 ms | 500 ms – 30 s |
| marginal cost | ~0 | metered per token |
| same input → same output? | yes | yes, *if* you pin the model and temperature |

Two things follow. First, **latency is now the product's problem**, not a number
you monitor — a multi-second wait is a user-visible defect. Second, **cost scales
with traffic**, which no amount of ordinary efficiency work fixes: a 20% faster
service costs the same per call.

### Step 2 — the traffic is repetitive

This is the empirical claim the whole product rests on. A support assistant, a
documentation bot, an internal helpdesk: a large share of their prompts are the
same questions, asked again. Not similar workloads — literally the same question
in slightly different words.

If that claim is false for your traffic, **the product is worthless to you** and
you should know that before adopting it. That is why the system reports hit ratio
as its headline metric rather than latency: the hit ratio *is* the test of the
premise.

### Step 3 — therefore cache, and the only interesting question is what counts as the same question

An exact-match cache is obvious and gets you the easy wins. The interesting
question is the one humans answer effortlessly and computers do not:

> Are *"how do I reset my password?"* and *"password reset steps"* the same
> question?

Answering it needs **embeddings** — turning text into a vector positioned by
meaning, so nearness in vector space approximates nearness in meaning. The idea
of the product is really this one substitution: **equality of bytes → nearness of
meaning.**

### Step 4 — and that substitution is dangerous, which is what makes it engineering

Exact match cannot be wrong. Nearness can. Set the threshold loose and the cache
answers a different question confidently.

This is not a hypothetical. **Measured on this system, two prompts asking about
different requirements scored 0.9987** — nearer to each other than any genuine
paraphrase tested (0.51–0.91). The cache would answer requirement 8 with
requirement 7's response. See
[`tier2-measured.md`](../architecture/tier2-measured.md).

So the real shape of the idea is not "cache LLM calls". It is:

> **Cache LLM calls by meaning, and treat the question of what counts as the
> same meaning as a safety problem rather than a tuning parameter.**

That framing is why the system has shadow mode, a threshold floor that refuses
to boot, and per-tier hit metrics. Those are not features bolted on; they are
the consequence of taking step 4 seriously.

---

## 3. Why multi-tenant from the start

Multi-tenancy was in the brief, but it is also the right call, and the reason is
worth understanding: **it is the one requirement that cannot be retrofitted.**

Add caching to a system later — fine, it is a layer. Add observability later —
fine. Add *tenancy* later and you must revisit every cache key, every SQL query,
every rate limit, every metric label and every table's partitioning, because
each one silently assumed a single customer. Worse, the failure mode of getting
it wrong is not a bug report: it is one customer reading another's data.

So tenancy is load-bearing from the first line. `tenant_id` is in every cache
key, is the first predicate in the Tier-2 query, and is the hash-partition key —
which means a search is **structurally unable** to cross tenants rather than
merely written not to. That distinction is the whole point:
[ADR-0006](../adr/0006-shard-key-is-tenant-id.md).

---

## 4. Ideas that were considered and rejected

The ADRs are the record. The pattern worth noticing is that most of them reject
the *more obvious* option:

| rejected | chosen | the trap in the obvious choice |
|---|---|---|
| Virtual threads (Java 21's headline feature) | WebFlux | virtual threads scale blocking code, but provide **no backpressure** — under overload the queue grows until memory does ([0001](../adr/0001-webflux-over-virtual-threads.md)) |
| A separate embedding service | in-process | a network hop plus a queue inside a 15 ms budget ([0002](../adr/0002-in-process-embeddings.md)) |
| Synchronous calls between services | Kafka + DB only | your availability becomes the product of everyone's ([0010](../adr/0010-no-synchronous-inter-service-calls.md)) |
| Choreographed saga | orchestrated | choreography has no single place to ask "where did this get stuck?" ([0009](../adr/0009-orchestrated-saga.md)) |
| Round-robin load balancing | least-connections | round-robin sends work to a pod that is already stuck ([0012](../adr/0012-least-connections.md)) |
| Running a real local LLM (Ollama) in benchmarks | mock provider | a benchmark dominated by a local model measures the model ([0011](../adr/0011-mock-provider-default.md)) |

If you remember one of these, make it the first. "Java 21 added virtual threads,
so use virtual threads" is the answer most people give, and the counter-argument
— **backpressure, not concurrency, is the scarce resource in a gateway** — is a
genuinely good thing to be able to explain. A gateway's job under overload is to
*refuse work*, and that is exactly what an unbounded queue of cheap threads
prevents you from doing.

---

## 5. How to talk about it

If someone asks where the idea came from:

> "The brief was a design blueprint for a multi-tenant AI gateway with a
> two-tier semantic cache. The implementation departs from it in five places,
> each recorded as an ADR — the most significant being that the blueprint
> claimed 15,000 RPS by multiplying one node's throughput by three. I replaced
> that with a measured single-node curve, an explicit extrapolation, and the one
> coefficient I could not measure named rather than hidden.
>
> The thing I did not expect to learn is that a semantic cache's danger is not a
> low threshold. Two prompts differing only in an identifier score 0.9987 —
> higher than any real paraphrase — so raising the threshold makes it *less*
> safe, not more."

That answer says what was given, what was yours, and what you learned that is
not in any textbook. All three are true and checkable.

---

**Next:** [B3 — the problems it solves](B3-problems-solved.md).
**Previous:** [B1 — overview](B1-overview.md).
