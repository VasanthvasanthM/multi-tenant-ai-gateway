# B3 — What problems this solves

Six real problems. For each one: what goes wrong without the gateway, what the
gateway does about it, and what it does **not** fix.

Plain words throughout.

---

## Problem 1 — You pay again for answers you already have

**Without it.** Every call to the model is billed. Ask the same question a
thousand times, pay a thousand times. The answer did not change.

**With it.** The answer is stored. The next person asking gets the stored copy.
You pay once.

**The numbers.** On a test mix with a realistic amount of repetition, **69.6%**
of requests were answered from cache. Seven in ten never reached the model.

A worked example, with the assumption written down:

```
2,000,000,000 requests/day
× 69.6% served from cache
= 1,392,000,000 model calls avoided per day
```

Turning that into money needs a price, which is yours and not the software's.
If a request costs $0.0002, that is about **$278,000 a day**. If your prompts
are short and you use a cheap model, far less. **The gateway reports tokens
saved, not money saved**, and the dashboard only shows a dollar figure when you
have configured a price — `estimatedSavingUsd` is absent otherwise, and the UI
says it is a calculation, not an invoice.

**What it does not fix.** If your traffic never repeats, you save nothing. The
hit ratio tells you within an hour.

---

## Problem 2 — Users wait seconds for an answer

**Without it.** A model call takes between half a second and thirty seconds.
That wait is not a metric, it is the product. People stare at a spinner.

**With it.** A cached answer comes back in **2–5 ms** from Redis, or about
**100 ms** from the semantic cache.

Against a real provider that is between 5× and 300× faster. On repeated
questions the feature stops feeling like AI and starts feeling like a web page.

**What it does not fix.** A genuinely new question still costs full price and
full time. The gateway adds a few milliseconds to those. Seven in ten fast, three
in ten unchanged.

---

## Problem 3 — One customer can ruin it for everyone

**Without it.** You run one service for many customers. One of them writes a
loop, or has a bad deploy, and sends ten thousand requests a minute. Everyone
else's requests queue behind them. You also get their bill.

**With it.** Every customer gets their own bucket of allowance. Defaults are a
burst of 100 and a steady 20 per second. When a customer exceeds theirs, **their**
requests are refused and nobody else notices.

Each customer also has a token budget. Spend it and the gateway stops them
before the provider bills you.

**Why a token bucket and not a smooth rate.** Real clients are bursty. A batch
job that fires 50 requests then sleeps for a minute is well within its average,
but a rule that enforces a perfectly even rate would reject it. A bucket allows
the burst and still caps the long-run rate.

**What it does not fix.** The limiter is set to **fail open**: if Redis is
unreachable, requests are allowed rather than refused. That is a deliberate
choice — a cache outage should not take your product down — but it means a Redis
problem quietly turns the limiter off. There is a metric and an alert for it.

---

## Problem 4 — You cannot tell who used what

**Without it.** The provider's bill is one number. You have ten teams or a
hundred customers using one API key. You cannot bill anyone, cannot find the
expensive one, cannot answer "why did the bill double?"

**With it.** One row per request: which customer, which model, how many tokens,
cache hit or miss. Rolled up per hour per customer. The dashboard shows requests,
hit ratio, tokens used and tokens saved for each one.

**What it does not fix.** The rollups are written by a separate service reading
from Kafka. If you deploy only the gateway, that table is not there at all, and
the usage endpoint returns zeros with a 200 status rather than an error. That is
a known bug on the list.

---

## Problem 5 — No record of what was asked

**Without it.** Someone asks "did our assistant tell a customer the wrong refund
policy on Tuesday?" and you have nothing. The provider does not keep a log in a
form you own.

**With it.** An append-only audit log. Every request, who made it, when. Written
by its own service so the request path never waits for it.

This matters in regulated work, and for the ordinary case of debugging a
complaint.

**What it does not fix.** It records that a request happened. Decide for yourself
whether storing prompt text is acceptable under your own privacy rules — prompts
can contain personal data.

---

## Problem 6 — One provider going down takes you with it

**Without it.** Your code calls one vendor. The vendor has a bad hour. So do you.

**With it.** An ordered chain: a main provider and named fallbacks. If the main
one fails, the next is tried.

**Why ordered and not load-balanced.** Different vendors answer the same question
differently. Spreading traffic across them means identical questions get
different answers depending on who happened to serve it — and your cache then
stores whichever one arrived first. A strict primary keeps answers consistent;
fallbacks are for failure, not for sharing load.

**What it does not fix.** Only a mock provider is wired up by default. Real
providers are a configuration job, not a code change, but it is a job.

---

## Who feels these problems

| you are | the problem that bites |
|---|---|
| A startup with an AI feature and a growing bill | 1 and 2 |
| A platform team serving many internal teams | 3 and 4 |
| Anyone in a regulated industry | 5 |
| Anyone whose vendor has had an outage | 6 |
| A company with external customers on an AI product | all six |

---

## When you should not use this

Worth being blunt, because the opposite advice is everywhere:

- **Your prompts never repeat.** Pure creative generation, one-off document
  analysis. Nothing to cache. Do not add a hop for nothing.
- **You have one application and one team.** Tenancy, budgets and attribution
  are the hard parts here, and you need none of them.
- **Wrong answers are unacceptable.** The semantic layer can serve a wrong
  answer; see [`tier2-measured.md`](../architecture/tier2-measured.md). You can
  run Tier 1 only, which cannot be wrong, but then you are running a hash cache
  and a simpler thing would do.
- **You need the newest answer every time.** A cache returns an old one. There is
  a time limit on entries, but a cache is the wrong shape for live data.

---

## The honest summary

The gateway makes repeated questions cheap and fast, and makes a shared
deployment safe to share. That is it.

It does not make the model better, does not make new questions faster, and the
semantic half has a known correctness problem that is measured and written down
rather than hidden.

**The one number that decides whether it is worth it for you is the cache hit
ratio.** Everything else follows from that.

---

**Next:** [B4 — the request flow, end to end](B4-request-flow.md).
**Previous:** [B2 — where the idea came from](B2-the-idea.md).
