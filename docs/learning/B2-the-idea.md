# B2 — "Where did you get this idea?" — what to say

A script for the question, plus the follow-ups that come after it.

---

## 1. What they are actually asking

Almost nobody asking this is auditing where the document came from. They are
asking one of these:

- *Do you understand the problem, or did you just build what you were told?*
- *Can you explain why this is worth building?*
- *Is there a real use case, or is it a CV project?*

So **answer about the problem, not about the paperwork.** If they want
provenance they will ask a second, more specific question — and there is an
honest one-liner for that in section 4.

---

## 2. The 30-second answer (use this one)

> "From how LLM APIs actually get billed.
>
> Every call to a model is metered and slow — half a second to thirty seconds,
> and you pay per token. But if you look at real production traffic, a huge
> share of it is the same handful of questions asked over and over. A support
> assistant gets 'how do I reset my password' thousands of times a day, and
> you're paying full price for an identical answer every single time.
>
> So the idea is a caching proxy in front of the model. The twist is that a
> normal cache doesn't help much, because people don't repeat themselves
> word-for-word — 'how do I reset my password?' and 'password reset steps' are
> the same question and a hash cache sees two different keys. So you need to
> match on *meaning*, not bytes. That's the semantic cache.
>
> It turned out the hard part wasn't building it — it was working out when two
> prompts are safely the same question. I'll tell you what I found if you want,
> because it surprised me."

That last sentence is deliberate. It hands them the interesting thread and
nearly always gets taken.

---

## 3. The follow-up: "what surprised you?"

This is the answer that makes the conversation memorable. It is in
[`tier2-measured.md`](../architecture/tier2-measured.md) and it is genuinely
counter-intuitive:

> "I assumed a higher similarity threshold meant a safer cache. It's the
> opposite.
>
> My load test generated prompts like 'Explain requirement 7 of the onboarding
> process' and 'requirement 8'. Different questions. The embedding model scored
> them **0.9987 similar** — higher than any genuine paraphrase I measured, which
> all landed between 0.51 and 0.91.
>
> So the ranking is inverted: the *wrong* match scores higher than every right
> one. Raise the threshold to be safe and you reject all the real paraphrases
> and keep only the dangerous near-duplicates.
>
> The reason is that a sentence embedding can't see a short identifier buried in
> long boilerplate — and that identifier is the entire question. Which means any
> prompt built from a template with an order number, a date, or a customer name
> in it has this problem, and that's a lot of real API traffic.
>
> The fix isn't a number. You have to compare the text difference before
> trusting the vector: if two prompts differ only in a numeral or an unknown
> token, that difference *is* the question — refuse the match. That's not built
> yet, so the cache ships in shadow mode: it runs the lookup, logs what it would
> have served, and serves nothing until someone measures the false-hit rate on
> their own traffic."

---

## 4. If they ask directly: "was this your own idea?"

Answer it straight. One line, no squirming:

> "The brief was a design spec I was working from — I didn't invent the concept
> of a semantic cache. What's mine is the implementation and the engineering
> decisions, and I overruled the spec in five places where it was wrong. Each
> one is written up as an ADR in the repo."

Then, if they are still interested, the best example:

> "The spec claimed fifteen thousand requests per second — three nodes times
> five thousand each. That multiplication is the thing that always needs
> proving, because it assumes nodes don't interfere with each other. I replaced
> it with a measured single-node number and an explicit extrapolation, with the
> one coefficient I couldn't measure named rather than hidden."

**Why this is a strong answer, not a weak one:** every professional engineer
works from specs. What distinguishes them is whether they notice when the spec
is wrong. "I was given a document and I found five errors in it, here they are
written up" is a better signal than "I thought of it myself."

---

## 5. What not to claim

| don't say | because |
|---|---|
| "I came up with the idea of semantic caching" | it is a known pattern with commercial products; one follow-up question exposes it |
| "It handles billions of requests a day" | one pod was measured; the fleet is arithmetic. Say "designed for" and name the gap |
| "It's production-ready" | Tier 2 has a known false-hit problem, and the capacity figure assumes Tier 2 is off |
| "I built it from scratch with no reference" | easily disproved and buys nothing |

The honest versions are stronger with anyone technical, and they are the only
versions that survive a second question.

---

## 6. Shorter variants

**LinkedIn comment / casual:**

> "LLM calls are metered and slow, and production prompt traffic repeats itself
> constantly — you're paying full price for the same answer hundreds of times.
> So: a caching proxy that matches on meaning rather than exact text. The
> interesting part was discovering that a stricter similarity threshold makes
> the cache *less* safe, not more."

**One sentence:**

> "From the observation that LLM APIs are billed per call while production
> prompts repeat constantly — so the obvious lever is a cache, and the hard part
> is deciding when two differently-worded prompts are safely the same question."

---

## 7. Why the premise is worth defending

If someone challenges the whole idea — *"is prompt traffic really that
repetitive?"* — the honest answer is that **it is an assumption the system
measures rather than asserts**:

> "That's exactly the thing that decides whether this is useful, so the system
> reports cache hit ratio as its headline metric. If your traffic doesn't
> repeat, the hit ratio tells you within an hour and you should rip the gateway
> out. I measured 69.6% on a representative mix — but that mix is mine, and the
> number is a property of the traffic, not of the software."

That answer shows you know which part of your own design is an assumption. It is
usually the most valuable thing you can demonstrate.

---

**Next:** [B3 — the problems it solves](B3-problems-solved.md).
**Previous:** [B1 — overview](B1-overview.md).
