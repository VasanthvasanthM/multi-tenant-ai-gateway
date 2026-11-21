# B6c0 — What each service actually does

Five services. One customer. Follow them through and it becomes obvious.

---

## The cast

```
control-plane   the reception desk    - signs customers up, hands out keys
gateway         the front counter     - serves every actual request
metering        the meter reader      - records what was used, for billing
audit           the logbook           - writes down who asked what, permanently
indexer         the filing clerk      - files answers so they can be reused
```

**Only one of them talks to your customer.** The other four work behind the
scenes.

---

## Day 1 - a company called Acme signs up

**`control-plane` does this.**

```
create a customer record for "Acme"
generate an API key:   sk-acme-7f3b...
set a spending limit:  10 million tokens a month
```

Acme now has a key they can use.

**That is it. The control-plane is done** until something changes. It will not be
involved again today, tomorrow, or next week.

---

## Day 2 - Acme's app asks a question

**`gateway` does this.** It is the only service in the request.

```
1. check the key sk-acme-7f3b...       -> valid, this is Acme
2. is Acme over their rate limit?      -> no
3. is Acme over their spending limit?  -> no
4. have we seen this question before?  -> no
5. ask the AI                          -> an answer, 800 ms
6. send the answer back to Acme        -> done
```

Acme has their answer. **The request is finished.**

---

## Then, after the answer has gone

The gateway drops **three notes into a post box** - that post box is Kafka - and
immediately forgets about them:

```
note 1  ->  "Acme used 150 tokens on gpt-4o-mini at 10:03"
note 2  ->  "Acme asked a question at 10:03"
note 3  ->  "here is the answer we got - file it for reuse"
```

The customer has already gone. These notes are collected later, by three
different services.

---

## `metering` collects note 1

```
"Acme used 150 tokens"
        |
writes it in the billing book
```

It keeps **two** records:

- **every single request**, one line each - the evidence behind an invoice
- **an hourly total** - so a dashboard can say "Acme: 4,200 requests today"
  without counting four thousand lines

**Why it matters:** without metering you cannot bill Acme. You would know your AI
provider charged you, but not which customer caused it.

---

## `audit` collects note 2

```
"Acme asked a question at 10:03"
        |
writes it in the permanent logbook
```

Never deleted, never changed. Only appended.

**Why it matters:** six months later someone asks *"did our system tell a
customer the wrong refund policy on 3rd March?"* Without the logbook you have
nothing to look at.

---

## `indexer` collects note 3

```
"here is the answer - file it for reuse"
        |
stores it in the searchable store, so a similar
question later can find it
```

**Why it matters:** this is what makes the cache work for **reworded** questions.
Without the indexer, only word-for-word identical repeats would ever be found.

**And it takes out the rubbish.** Old answers expire after a day, and the indexer
deletes them. Nothing else does - so if the indexer stops, storage grows forever.

---

## End of the month - Acme gets an invoice

Built entirely from what **metering** wrote down.

---

## Later - Acme's key is leaked

**`control-plane` again.**

```
cancel key sk-acme-7f3b...
issue a new one
```

The reception desk is back for one minute, then idle again.

---

## Why they are separate services

Look at how often each one works:

| service | how busy |
|---|---|
| **gateway** | every single request - 92,000 a second at peak |
| metering | every request, but *later*, not in the queue |
| audit | every request, but *later* |
| indexer | only when the answer was new - about 3 in 10 |
| **control-plane** | once per customer, ever |

**The gateway needs ~294 copies. The control-plane needs 3.**

As one program you would have to pick a single number: either 294 copies of a
reception desk that serves one person a week, or 3 front counters trying to
handle 92,000 requests a second.

---

## The part that makes it safe

**The gateway never waits for any of the other four.**

It drops the notes and replies to the customer immediately. So if metering,
audit, the indexer, or the control-plane is completely broken:

```
Acme's requests  ->  still work perfectly
```

Acme never notices. **That is the whole reason for the post box in the middle.**

What you lose while something is broken:

| broken | what happens |
|---|---|
| metering | invoices are late. The notes wait in the post box; nothing is lost |
| audit | the logbook is behind. Same - the notes wait |
| indexer | the cache stops learning new answers. The hit rate slowly drifts down, and old answers stop being deleted |
| control-plane | you cannot sign up or cancel anyone. Everyone already using the system is fine |
| **gateway** | **everything stops.** It is the only one on the request path |

---

## One honest note

The post box only holds notes for **24 hours**. So "nothing is lost" has a
deadline: if metering is broken for longer than that, the unread notes are
deleted.

And that is only true while the post box itself is working. **If Kafka is down,
the gateway throws the notes away rather than waiting** - so that window of
billing records is gone for good. The reasoning, and why it is the right trade,
is in [B4a](B4a-failure-behaviour.md).

---

**Previous:** [B6b1 — each pattern, where and why](B6b1-patterns-where-and-why.md).
**Next:** B6c — the APIs we expose and the ones we call.
