# B6 — The LLD, one idea at a time

**LLD means Low-Level Design.** HLD is the map of the city — how many servers,
which service talks to which. LLD is the wiring inside one building — how a
single function is written and why.

Eight ideas. Each one is a place where the obvious choice is wrong. Each gets a
plain example before any code.

> **Nothing here is a bug report.** All eight are already in the code. This
> explains *why* the code looks the way it does. Where a real bug was found, it
> is in [`problems-and-solutions.md`](../problems-and-solutions.md) instead.

---

# 1. The cache label

## The everyday version

You file an answer under the question that produced it, like a label on a folder.

The label is built from two pieces of information: **which customer** and
**which model**. The obvious way is to write them one after the other:

```
customer "acme"   model "gpt-4"    ->  label:  acmegpt-4
customer "acmeg"  model "pt-4"     ->  label:  acmegpt-4
```

**The same label.** Two different customers, one folder. Customer `acmeg` opens
the folder and reads `acme`'s private answer.

The problem: squashing things together **loses where one ends and the next
begins**.

## The fix: say how long each piece is, first

```
4 acme   5 gpt-4      ->  "4acme5gpt-4"
5 acmeg  4 pt-4       ->  "5acmeg4pt-4"
```

Different labels. The numbers make the boundary impossible to confuse. That is
all a **length prefix** is: *say how long the next thing is, before you say the
thing.*

## Proven, not asserted

```
WITHOUT the length prefix:
  acme + gpt-4  -> f8aa6f9de8b2dc04...0327d61c
  acmeg + pt-4  -> f8aa6f9de8b2dc04...0327d61c      IDENTICAL

WITH the length prefix:
  acme | gpt-4  -> 0f7323e18a8d29e2...b658e2c6
  acmeg | pt-4  -> c607a94ed99d497c...eb88eab6      different
```

## The code

```java
private static void putString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    putInt(digest, bytes.length);   // the LENGTH first  ("4")
    digest.update(bytes);           // then the VALUE    ("acme")
}
```

Two lines. The first is the entire defence.

## And two things deliberately left out of the label

| left out | why |
|---|---|
| whether the answer was streamed | the same question streamed or not has the same answer. Including it would halve the hit rate for nothing |
| the request's unique id | it is different on **every** request by definition. Include it and every lookup misses — the cache is switched off, silently, while looking healthy |

**The lesson:** when you combine several fields into one identifier, the
boundaries between them must be part of what you combine.

---

# 2. Tidying up the question

## The everyday version

The same question can be typed slightly differently. You want those to share one
folder — so you tidy the text up a bit first.

But tidy too much and you merge questions that are genuinely different. Four
rules follow.

## Rule 1 — strip spaces at the start and end ✓

```
"What is the capital of France?"
"   What is the capital of France?   "
```

Same question, accidental spacing. Strip the outside. One folder.

## Rule 2 — never touch spaces in the middle ✗

Lots of people send **code** to an AI, and in code the leading spaces change the
meaning:

```
if raining:
    take umbrella
    wear coat
```

*when it rains, take an umbrella and wear a coat*

```
if raining:
    take umbrella
wear coat
```

*when it rains take an umbrella. Always wear a coat.*

**Different instructions.** Squash the spaces and both become one label, share a
folder, and one gets the wrong answer.

## Rule 3 — keep the capitals ✗

Lowercasing feels right. But capitals can carry meaning, and lowercasing damages
non-English text unpredictably.

## Rule 4 — unify letters that look identical ✓

Some letters arrive in **two forms that look the same on screen**. Take `café`:

- some keyboards send **é** as one letter
- others send **e**, then *"put an accent on that"*

On screen: identical. To the computer: different → two labels → paid twice.

So both are converted to one standard form. Safe, because they genuinely are the
same word.

### The trap inside rule 4

There are two strengths of this conversion. The aggressive one also does:

```
x²   becomes   x2
```

**That is a different sum.** `x²` is *x squared*; `x2` is *x times 2*. These two
questions would share a folder:

```
"What is the integral of x²?"
"What is the integral of x2?"
```

Same answer for both — and one is wrong. **So: the gentle version only.**

## And measuring length

There is a limit on question length. The usual way of counting in Java counts an
emoji as **2**, so a "1,000 character limit" is secretly 500 for an emoji user.
The code counts real characters instead.

> This is where the one real bug was: the Tier-2 guard used the wrong count. See
> [`problems-and-solutions.md`](../problems-and-solutions.md).

**The lesson:** tidying that is right for a *search box* is wrong for a *label*.
A search box wants to find similar things. A label must keep every difference
that could change the answer.

---

# 3. Vectors must be exactly length 1

## The everyday version

Tier 2 compares meanings by turning each question into an **arrow** — a direction
in space. Two questions mean the same thing if their arrows point the same way.

To compare directions, you only care about **which way** each arrow points, not
**how long** it is. So every arrow is made exactly **1 unit long** first.

```
arrow A: ———————————>        length 1
arrow B:        ———>         length 0.4     <- same direction, different length
```

If you compare these without fixing the lengths, B looks "less similar" purely
because it is shorter — and the shortness means nothing.

## Why this is not just tidiness

The database index is built for a fast comparison that **only equals a true
direction comparison if every arrow is length 1**. Feed it a longer arrow and it
does not fail. It **ranks wrongly** — plausible numbers that are quietly
meaningless.

## The clever part: it refuses to exist wrongly

Most code would normalise the arrow wherever it happens to remember. This class
**rejects** a bad one at creation:

```java
if (Math.abs(magnitude - 1.0d) > NORM_TOLERANCE) {
    throw new IllegalArgumentException(
        "embedding must be L2-normalised; magnitude was " + magnitude
            + ". Use Embedding.normalizing(float[]) if the source is raw.");
}
```

So you **cannot hold** a vector that would compare wrongly. If your input is raw,
you must say so explicitly by calling `normalizing(...)`.

And note the error message tells you the fix. That is deliberate — the person
hitting it is usually not the person who wrote this.

## One detail worth stealing

```java
private static final double NORM_TOLERANCE = 1e-4d;
```

Why not `1e-6`, the obvious "close enough"? Because adding up a few hundred
decimal numbers accumulates drift bigger than that. `1e-4` is loose enough to
accept genuinely-correct vectors and tight enough to catch a wrong one, whose
length is usually nowhere near 1.

**The lesson:** if a rule must always hold, enforce it where the object is
**made**, not where it is used. There is one constructor and many callers.

---

# 4. Why the rate limiter is a script

## The everyday version

Think of a bucket of tokens. Each request takes one. The bucket refills slowly.
Empty bucket → request refused.

Four steps: **look** in the bucket, **work out** how many to add back,
**take** one, **write down** the new count.

## What goes wrong with many servers

Two servers do those four steps at the same instant:

```
server A looks:  1 token left
server B looks:  1 token left        <- same moment
server A takes it, writes 0, allows the request
server B takes it, writes 0, allows the request
```

**The limit was 1. Two got through.** With hundreds of servers this is not rare —
it is constant.

## The fix: send all four steps to Redis as one script

Redis runs scripts **one at a time, start to finish**, with nothing else
interleaved. So look-decide-take-write becomes a single indivisible step.

The file is real: `gateway/src/main/resources/scripts/token_bucket.lua`. There is
a second one, `budget_record.lua`, for the token budget — same reasoning.

## And this is why the Redis keys have those braces

A script may only touch keys that live on **one** machine in a Redis cluster.

```
{tenant:acme}:bucket
{tenant:acme}:quota
```

The braces tell Redis *"group everything with this tag onto one machine"*. That
is what makes the script legal.

**Without the braces:** works perfectly on one Redis, breaks the instant you run
a cluster. The worst kind of bug — invisible in development.

**The lesson:** when several steps must happen with nothing in between, move them
to where they can be atomic, rather than hoping the gap is too small to matter.

---

# 5. Errors as a list, not as exceptions

## The everyday version

Something goes wrong. You need to tell the caller *what* went wrong, and turn it
into an HTTP status.

The usual way is one exception class per problem. The problem with that: when you
add a new one, **nothing reminds you** to decide its status. It falls through to
a generic 500 and nobody notices until a customer complains.

## What is done instead

One **sealed** list — a closed set the compiler knows about:

```java
public sealed interface GatewayError {
    record Unauthenticated(String message)         implements GatewayError {}
    record PayloadTooLarge(int actual, int limit)  implements GatewayError {}
    record RateLimited(Duration retryAfter)        implements GatewayError {}
    record BudgetExhausted(long limit, long used)  implements GatewayError {}
    record UpstreamTimeout(Duration elapsed)       implements GatewayError {}
    ...
}
```

**"Sealed" means: this is the complete list, and no one may add to it from
elsewhere.**

Because the list is closed, the compiler can check you handled every case. Add a
tenth error and **every piece of code that switches over them stops compiling**
until you say what it should do.

The reminder is the build failing, not a customer email.

## And each error carries its own facts

```java
record RateLimited(Duration retryAfter)
record PayloadTooLarge(int actualCodePoints, int limitCodePoints)
```

`RateLimited` holds *how long to wait*, so the handler can set a real
`Retry-After` header instead of guessing. `PayloadTooLarge` holds both the actual
size and the limit, so the message can say "600, limit is 100" rather than
"too large".

**The lesson:** make the set of possibilities closed, and the compiler becomes
the thing that reminds you.

---

# 6. Retries live outside the providers

## The everyday version

You call an outside service. Sometimes it fails. You want to retry, and to stop
calling it entirely if it is clearly down.

There are four provider classes (OpenAI-compatible, Ollama, two mocks). The
obvious approach is to put retry logic in each one.

**Why that goes wrong:** four copies drift. One retries 3 times, another 5, a
third forgets to wait between attempts. Nobody notices, because each works in
isolation.

## What is done instead

One **wrapper** that goes around any provider:

```
ProviderRegistry  ->  ResilientLlmProvider  ->  OpenAiCompatibleProvider
                                            ->  OllamaProvider
                                            ->  MockLlmProvider
```

Written once. Every provider gets identical behaviour, and the providers stay
dumb — each knows only how to make one call.

## The order inside the wrapper is itself a decision

```
Retry ( CircuitBreaker ( TimeLimiter ( Bulkhead ( call ) ) ) )
```

Read it outside-in:

- **Retry outermost**, so each retry gets a **fresh** check of whether the
  service is considered down. Put retry inside and it would hammer a service
  everyone has already agreed is broken.
- **Bulkhead innermost**, capping how many calls run at once, so a slow provider
  cannot consume every thread.

**The lesson:** behaviour that is the same for every implementation belongs in a
wrapper, not copied into each one. And when you nest such behaviours, the order
changes what they mean.

---

# 7. A test that checks the shape of the code

## The everyday version

Some rules cannot be expressed in the language itself. *"No service may call
another service directly."* Java has no way to say that.

The usual answer is a line in a README. That gets broken by the first person in a
hurry, and nobody notices for months.

## What is done instead

A test that inspects the codebase and fails the build. Nine real rules:

| rule | what it prevents |
|---|---|
| `noBlockingOnReactiveTypes` | one `.block()` call freezing a thread that serves many requests |
| `noThreadSleep` | the same, by a different route |
| `noDirectThreadCreation` | threads nobody manages or counts |
| `noStaticNowCalls` | `Instant.now()` hard-wired, making time untestable |
| `noSystemOut` | print statements that bypass logging entirely |
| **`layering`** | **a service importing another service** |
| `commonStaysFrameworkFree` | the shared core quietly growing a Spring dependency |
| `noStringEqualsOnSecrets` | comparing secrets with `==`, which leaks length by timing |
| `noRandomForSecurity` | a predictable random number used for something security-relevant |

**`layering` is the important one.** It is the only thing actually keeping the
no-synchronous-calls design true. Without it, someone adds one convenient
import, then one convenient method call, and every service's uptime becomes
every other service's uptime — invisibly, because it still works in development
where everything is up.

Two of them — `noStringEqualsOnSecrets` and `noRandomForSecurity` — are security
rules that a code review would have to catch every single time. A test catches
them every single time.

**The lesson:** a rule you cannot express in the language, write as a test. A
convention in a document is a wish.

---

# 8. Declaration order can crash a class

## The everyday version

In Java, `static final` fields are set up **in the order they are written**, when
the class is first used. If one depends on another, the order matters.

```java
private static final Pattern VALID = Pattern.compile("...");
public  static final ProviderId CACHE = ProviderId.of("cache");
```

`CACHE` is created by `ProviderId.of(...)`, which checks the name against
`VALID`.

Write them the other way round, and `VALID` is still **null** when `CACHE` is
created. The class dies with `ExceptionInInitializerError`.

## Why it is nasty

It happens during application startup, deep inside the framework creating beans,
so it surfaces as **an unrelated-looking Spring failure**. You spend an hour
reading the wrong stack trace.

## The fix

A comment stating the dependency, and a test pinning it:

```java
// VALID must be declared BEFORE CACHE: the CACHE constant's constructor reads it.
```

The test is `ValueTypeConstantsTest`. It touches these constants, so if someone
reorders them the test fails immediately rather than at some future startup.

**The lesson:** when one line must come before another and the language will not
enforce it, write down why **and** add a test — a comment alone is a request.

> **Correction to an earlier draft:** I previously described this as failing with
> a *wrong value* rather than an exception. It throws
> `ExceptionInInitializerError`. The problem is not silence, it is a misleading
> error far from its cause.

---

# What all eight have in common

| | the obvious choice | what goes wrong |
|---|---|---|
| 1. Cache label | glue the fields together | boundaries become confusable → one customer reads another's data |
| 2. Tidying text | clean it up properly | cleaning destroys differences that change the answer |
| 3. Vectors | normalise when you remember | one caller forgets, and results rank wrongly instead of failing |
| 4. Rate limiter | count in your own code | two servers both see "1 left" and both allow |
| 5. Errors | an exception class each | a new one silently becomes a generic 500 |
| 6. Retries | put them in each provider | four copies drift apart |
| 7. Boundaries | write it in the README | broken by the first person in a hurry |
| 8. Field order | assume it does not matter | a misleading crash during startup |

**Seven of the eight fail silently.** Only the last one throws, and it throws in
a way that points at the wrong place.

That is the thread running through the whole low-level design: **the dangerous
bug is not the one that crashes — it is the one that returns a plausible wrong
answer.** It is also why this project leans on tests that pin exact values,
mutation testing, and architecture rules, rather than on checking that things
broadly work.

---

**Previous:** [B5d — shard migration in plain words](B5d-shard-migration-in-plain-words.md).
**Next:** B6b — which architecture and patterns were used.
**Full detail:** [`LLD.md`](../architecture/LLD.md).
