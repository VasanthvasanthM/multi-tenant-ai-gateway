# B6c1 — Metering, explained properly

What problem it solves, where the token numbers come from, and why there are two
tables.

---

## Step 1 — the problem it solves

At the end of the month your AI provider sends **you** one bill:

```
OpenAI invoice:  $8,432.00
```

One number. That is all you get.

But **you have 100 customers** using your gateway. Acme, Globex, Initech, and 97
others.

**Which of them caused that $8,432?**

You have no idea. The provider does not know your customers exist - they just saw
requests arriving under your API key.

So you cannot send anyone a bill. You cannot tell which customer is expensive.
You cannot answer "is Acme profitable?"

**That is the problem metering exists to answer.**

---

## Step 2 — so you write down every request

One line per request, forever:

```
who       when        model          tokens in   tokens out   cached?
--------- ----------- -------------- ----------- ------------ -------
Acme      10:03:14    gpt-4o-mini    120         180          no
Acme      10:03:19    gpt-4o-mini    95          0            yes
Globex    10:03:21    gpt-4o         340         520          no
Acme      10:03:22    gpt-4o-mini    120         180          yes
```

Now add up the month:

```
Acme     4,200,000 tokens  ->  charge £84
Globex   1,100,000 tokens  ->  charge £22
Initech    300,000 tokens  ->  charge £6
         ...
                               total charged:  £8,900
AI provider's bill:                            £8,432
                                                ------
your margin:                                   £468
```

**That is the whole point.** Without those lines you have one number and no way
to split it.

### Notice the cached column

```
Acme   10:03:19   95 in, 0 out     yes   <- cost YOU nothing
Acme   10:03:22   120 in, 180 out  yes   <- cost YOU nothing
```

Two of Acme's four requests were cache hits. You paid the provider nothing for
them, but Acme still used your service. So you can also answer:

- *how much did the cache save us?* - add up the cached lines
- *is Acme profitable?* - compare what they pay against what they actually cost

**That one column is where the product's value is measured.**

---

## Step 3 — where do the token numbers come from?

Four different sources, depending on what happened.

### A real provider call: the provider tells you

Every OpenAI-compatible API returns a usage block with the answer:

```json
{
  "choices": [{ "message": { "content": "Paris is the capital of France." } }],
  "usage": { "prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20 }
}
```

You count nothing. **The provider counts it and reports it** - and it is the same
number they bill you on, so your records agree with their invoice by definition.

### A cache hit: the numbers were saved earlier

A cache hit never calls the provider, so nobody is there to report tokens. They
come from the cache entry itself:

```json
{"v":1,"c":"Paris is the capital of France.","m":"gpt-4o-mini",
 "pt":12,"ct":8,"p":"openai","ts":1763049600000}
```

`pt` is prompt tokens, `ct` is completion tokens. The code says it plainly:

```java
 * @param promptTokens      input token count from the original call
 * @param completionTokens  output token count from the original call
```

**"from the original call."** The first time that question was asked the provider
said 12 and 8, and those numbers were stored **alongside the answer**. Every
later cache hit reports the same pair.

That is why those fields exist in the cache entry. Without them a cache hit would
have no usage to report, and billing would have a hole exactly where the cache is
working best.

### Streaming: you have to ask for it

With a streamed answer the text arrives in pieces and there is no single response
object to carry a usage block. So the gateway adds a flag to the request:

```java
body.put("stream_options", Map.of("include_usage", true));
```

Meaning *"when you have finished, send one last message with the token counts."*
And the code notes what happens when a vendor ignores it:

> *"A vendor that ignores this simply sends no usage frame"*

The fallback is then **counting characters** -
`BudgetGate.recordStreamed(tenant, characters)`. An approximation, used only when
no real count arrives.

### The mock provider: it estimates

```java
new TokenUsage(estimateTokens(promptChars), estimateTokens(content.length()))
```

No model, so it guesses from the character count. Fine for tests, meaningless as
a measurement - and **every token number produced by this system so far came from
that estimate**, the same caveat as the 40 ms upstream figure.

### Why it matters that the provider counts them

A token is not a word or a character. It is a chunk the model's own tokenizer
decides on: "unbelievable" might be three tokens, " the" one, an emoji several.
**Different models tokenize differently**, so you cannot reliably count tokens
yourself without reimplementing each vendor's tokenizer and keeping it in sync
forever.

Taking the number from the provider means **your billing matches their billing**.
Counted yourself, every small difference would appear as a discrepancy between
what you charged and what you were charged, and you would never know which side
was wrong.

| path | source | exact? |
|---|---|---|
| real provider call | the usage block in the response | **yes** - it is what they bill |
| cache hit | saved from the original call | **yes** - same request |
| streaming | a terminal usage frame, if sent | yes, when present |
| streaming, no frame | character count | **no** - an estimate |
| mock provider | estimated from characters | **no** - test data only |

---

## Step 4 — why there are two tables

The billing book has one line per request. Correct, and also the problem.

### The problem

The dashboard asks: *"how many requests did Acme make this month?"*

To answer from the billing book you must **read every one of Acme's lines and
count them**. At full scale:

```
2,000,000,000 requests a day  x  30 days  =  60,000,000,000 lines
```

Counting sixty billion lines takes **minutes**. Nobody waits minutes for a
dashboard.

### The fix: keep a running total as well

One line per customer, per hour:

```
customer   hour     requests   cache hits   tokens in   tokens out
---------- -------- ---------- ------------ ----------- -----------
Acme       10:00    4,183      2,901        512,400     689,200
Acme       11:00    3,902      2,744        478,100     641,900
Globex     10:00      812        203         98,300     141,700
```

Now the same question needs `30 days x 24 hours = 720 lines`. **Instant.**

### Two tables, two jobs

| | `usage_ledger` | `tenant_usage_rollup` |
|---|---|---|
| holds | one line per request | one line per customer per hour |
| used for | **evidence** - proving an invoice is right | **speed** - dashboards and reports |
| a month | 60 billion lines | 720 lines per customer |
| customer disputes a charge | show them every single request | only the totals |

You need both. The totals are fast but cannot be audited. The book is auditable
but too slow to read.

---

## Step 5 — the subtle bit in updating the totals

Two metering pods both process Acme requests in the 10:00 hour. Both must add to
the same row.

**The obvious way is broken:**

```
pod A reads:  requests = 4,000
pod B reads:  requests = 4,000      <- same instant
pod A adds 1, writes 4,001
pod B adds 1, writes 4,001          <- one request vanished
```

**The same race as the rate limiter.** Both read the old value, both write, one
update is lost.

**The fix - do the addition inside the database statement:**

```sql
ON CONFLICT (tenant_id, period_hour) DO UPDATE SET
    request_count = tenant_usage_rollup.request_count + EXCLUDED.request_count
```

`x = x + new`, computed by the database. Nothing can slip between the read and
the write, because there is no separate read. The comment names the reason:

> *"two pods updating the same hour would lose one of them"*

### The same lesson, twice now

| where | the fix |
|---|---|
| rate limiter | a Lua script Redis runs start-to-finish |
| hourly totals | `DO UPDATE SET x = x + ...`, computed in the database |

**Both move the read-modify-write to somewhere it cannot be interrupted.**

If you remember one idea from this whole section, that is the one - because the
broken version **works perfectly in testing with one pod** and only fails once
you scale out.

---

## And this explains a bug we hit earlier

The dashboard showed zeros.

The dashboard reads **the rollup** - the fast table. In a gateway-only deployment
metering is not running, so nothing ever writes to it. The table does not even
exist.

And the endpoint returned **200 with zeros** rather than saying the data was
unavailable. That is work item **A8**, still unfixed - and it is the same failure
shape as everything else in
[`problems-and-solutions.md`](../problems-and-solutions.md): reporting success
for data that is not there.

---

**Previous:** [B6c0 — what each service does](B6c0-what-each-service-does.md).
**Next:** Resilience, in [B6b1](B6b1-patterns-where-and-why.md).
