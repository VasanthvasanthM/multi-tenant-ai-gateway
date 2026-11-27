# B1 — Overview

What this system is, who it is for, and what it replaces.

---

## 1. One paragraph

It is a **proxy that sits between your application and an LLM provider**. Your
code keeps calling the OpenAI API shape it already knows; the gateway intercepts
the call, and if the same question (or a close enough one) has been answered
before, it returns the stored answer instead of paying the provider again. It is
**multi-tenant**: many customers share one deployment, and no customer can see,
starve, or be billed for another's traffic.

Swap `api.openai.com` for the gateway's hostname and nothing else in your
application changes. That is the whole integration.

---

## 2. The problem, concretely

LLM calls have two properties that make them unlike normal API calls:

| | normal REST call | LLM call |
|---|---|---|
| latency | 5–50 ms | **500 ms – 30 s** |
| cost per call | effectively zero | **metered per token** |

And production prompt traffic is extremely repetitive. A support assistant is
asked *"how do I reset my password?"* thousands of times a day. Each one is a
fresh bill and a fresh multi-second wait, for an answer that has not changed.

The gateway's bet is simple: **most prompts have been seen before, so most
prompts should never reach the model.**

Measured on this system:

| path | who answers | latency |
|---|---|---|
| **Tier 1** — exact SHA-256 match | Redis | **2–5 ms** |
| **Tier 2** — semantic match | PostgreSQL + pgvector | **~100 ms** |
| **miss** | the actual LLM | 500 ms – 30 s |

At a measured **69.6% hit ratio**, roughly seven in ten requests never reach the
provider. Scale that to 2 billion requests a day and it is ~1.39 billion
upstream calls a day that do not happen.

> **Read the Tier 2 number honestly.** ~100 ms is slower than the *mock*
> provider used in testing (40 ms), so in the test rig Tier 2 looks like a
> regression. Against a real provider at 500 ms–30 s it is a 5–300× win. The
> mock is fast precisely because it is not a model.

---

## 3. The two tiers, and why there are two

This is the core idea, so it is worth being precise.

**Tier 1 — exact match.** Hash the normalised prompt with SHA-256, look the hash
up in Redis. Either the exact same bytes were asked before or they were not.
Cheap, certain, and useless for rewording: *"How do I reset my password?"* and
*"how do I reset my password"* are different hashes.

**Tier 2 — semantic match.** Convert the prompt into a 384-number vector (an
*embedding*) that represents its meaning, then find the nearest stored vector in
pgvector. "Close enough" is a cosine-similarity threshold. This catches
rewordings that Tier 1 cannot.

Tier 1 runs first because it is 20× cheaper and cannot be wrong. Tier 2 only
sees what Tier 1 missed.

> **Tier 2's denominator is not the same as Tier 1's**, which matters whenever
> you compare their hit ratios: Tier 2 is only ever asked about prompts Tier 1
> already failed on. The dashboards label this; a naive comparison makes Tier 2
> look much worse than it is.

**Tier 2 is also the risky one.** It can be *wrong* in a way Tier 1 never can —
it can decide two different questions are the same and serve the wrong answer.
Measured on this system: two prompts asking about *different* requirements
scored **0.9987** similar, higher than any genuine paraphrase tested. Details in
[`tier2-measured.md`](../architecture/tier2-measured.md); it is the single most
important thing to understand about semantic caching, and it is why Tier 2 ships
in **shadow mode** (query it, log what it would have served, serve nothing)
until someone has measured the false-hit rate on their own traffic.

---

## 4. What "multi-tenant" buys you

A single-tenant cache is easy. Multi-tenancy is where the engineering is, because
one shared deployment must behave as if each customer had their own:

| concern | mechanism |
|---|---|
| **Isolation** — tenant A must never see tenant B's cached answer | `tenant_id` is in every cache key, every SQL `WHERE`, and is the partition key |
| **Fairness** — one tenant's burst must not starve the rest | per-tenant token-bucket rate limiter in Redis |
| **Cost control** — a runaway tenant must not run up an unbounded bill | per-tenant token budget ledger |
| **Attribution** — every request must be billable to someone | usage ledger, one row per request |
| **Auditability** — who asked what, when | append-only audit log |

The isolation point is not a feature, it is the product's licence to exist. A
semantic cache that leaked one customer's answers to another would be a data
breach, not a bug. That is why `tenant_id` is the **first** column in the Tier-2
`WHERE` clause and the hash-partition key: a search is *structurally* incapable
of crossing tenants, rather than merely being written not to.

---

## 5. The shape of the system

Five deployable services, nine Maven modules.

| service | owns |
|---|---|
| **gateway** | the request path: auth, rate limit, Tier 1, Tier 2, upstream call |
| **control-plane** | tenants, API keys, budgets — the admin side |
| **metering** | turns usage events into billable rollups |
| **indexer** | writes embeddings into Tier 2 |
| **audit** | append-only audit trail |

Plus four shared library modules — `common`, `embedding`, `kafka-support`, and
`coverage-report`.

**Only the gateway is on the request path.** Everything else consumes events
from Kafka *beside* it. That is deliberate and is
[ADR-0003](../adr/0003-kafka-beside-request-path.md): if metering, audit or the
indexer is down or slow, requests still succeed. The alternative — calling them
synchronously — makes your availability the product of everyone's availability.

**There are no synchronous calls between services at all**
([ADR-0010](../adr/0010-no-synchronous-inter-service-calls.md)). Services
communicate only through Kafka topics and the database. A service cannot make
another one slow.

---

## 6. Who it is for

- **A company running an LLM feature at scale** that is paying for the same
  answers repeatedly, and wants the bill to stop growing linearly with traffic.
- **A platform team serving several internal teams** that needs per-team quotas,
  attribution and isolation without running a separate gateway per team.
- **Anyone who needs an audit trail** of what was asked and answered — the
  provider does not give you one in a form you own.

It is **not** for a single application making a handful of unique calls. If your
prompts never repeat, the cache has nothing to offer and you are adding a hop
for no reason.

---

## 7. What it replaces

| alternative | why this exists instead |
|---|---|
| **Calling the provider directly** | no caching, no quotas, no attribution, no audit; every repeat is a fresh bill |
| **A caching layer in each application** | every team reimplements it, nobody implements the semantic half, and tenancy rules drift between copies |
| **A commercial AI gateway** | costs money, and the interesting parts (threshold policy, shard strategy, what happens when Redis is slow) are decisions you cannot see or change |
| **A plain HTTP cache (Varnish, nginx)** | keys on the URL and headers; LLM calls are POSTs with a JSON body where meaning, not bytes, decides equivalence |

The last row is the real argument for building rather than configuring: deciding
that two *different* strings mean the same thing is a judgement call about your
traffic, and it is exactly the call that can serve a confidently wrong answer.
That judgement cannot live in a generic cache.

---

## 8. What is real, and what is not

Being clear about this is the point of the whole project.

**Measured:**
- One pod sustains **≥271 rps** at a 69.6% hit ratio, p50 198 ms — with Tier 2
  **off**, which is the shipped default.
- Tier-1 hits at **2–5 ms** against a 40 ms mock upstream.
- Tier 2 works end to end with a real `all-MiniLM-L6-v2` model and serves
  genuine semantic hits.

**Calculated, not measured:**
- The 488-pod fleet for 2 billion requests/day. That is arithmetic from one
  measured pod, and it assumes linear scaling.

**Not measured at all:**
- The USL coherency coefficient *kappa*, which is what would prove the fleet
  scales linearly. The laptop's load generator saturated before the gateway did.
- The false-hit rate of Tier 2 on real traffic.
- Per-pod throughput with Tier 2 **on** — it is CPU-bound and one to two orders
  of magnitude lower, so the 488-pod figure does not describe that configuration.

> **The README's status section is stale.** It still says no cluster run or load
> campaign has happened and that PITest has never run; both have since happened.
> Treat [`scaling-model.md`](../architecture/scaling-model.md) and
> [`tier2-measured.md`](../architecture/tier2-measured.md) as current, and the
> README as needing an update.

---

## The mental model to carry forward

Three paths, and every design decision in this system is protecting the first
two:

```
request ─► auth ─► rate limit ─► Tier 1 (Redis, 2-5 ms)   ──hit──► response
                                     │ miss
                                     ▼
                                 Tier 2 (pgvector, ~100 ms) ──hit──► response
                                     │ miss
                                     ▼
                                 upstream LLM (0.5-30 s)  ────────► response
                                     │
                                     └─► events to Kafka: usage, audit, cache-write
```

**Next:** [B2 — where the idea came from](B2-idea.md).
