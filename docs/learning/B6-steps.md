# B6 (steps) — the LLD ideas, one step at a time

The same eight ideas as [B6](B6-lld.md), but broken into numbered steps with
nothing assumed. [B6](B6-lld.md) is the summary; this is the teaching version.

Done so far: **3, 4, 5**. The rest are added as we work through them.

---

# 3. Why vectors must be exactly "length 1"

## Step 1 — the computer stores numbers, not words

The computer cannot understand words. So when a question arrives, it scores that
question on a list of topics:

| question | account | security | cooking | sport |
|---|---|---|---|---|
| "How do I reset my password?" | 8 | 7 | 0 | 0 |

High on *account* and *security*. Zero on *cooking* and *sport*.

**That row of numbers is how the computer stores the question.** The words are
thrown away.

## Step 2 — two questions are compared as two rows

| question | account | security | cooking | sport |
|---|---|---|---|---|
| "How do I reset my password?" | 8 | 7 | 0 | 0 |
| "Steps to reset a password?" | 9 | 8 | 0 | 0 |

Different words. **Nearly identical rows.** So the computer says: same meaning.

That is the whole idea of the semantic cache. It compares rows, never words.

## Step 3 — how it decides

It **multiplies the matching numbers and adds up the results.**

The two password questions:

```
account:    8 x 9  =  72
security:   7 x 8  =  56
cooking:    0 x 0  =   0
sport:      0 x 0  =   0
                      ---
            total  =  128      <- big total, same meaning
```

Question 1 against something unrelated:

| question | account | security | cooking | sport |
|---|---|---|---|---|
| question 1 | 8 | 7 | 0 | 0 |
| "how do I boil rice?" | 0 | 0 | 9 | 0 |

```
8 x 0  +  7 x 0  +  0 x 9  +  0 x 0   =   0      <- nothing in common
```

**Big total means the same meaning; small total means different.** A column only
contributes when *both* questions score high on it.

## Step 4 — where it goes wrong

A third question, about exactly the same thing, but long and rambling, so every
score comes out ten times bigger:

| question | account | security | cooking | sport |
|---|---|---|---|---|
| question 1 | 8 | 7 | 0 | 0 |
| the long rambling one | 80 | 70 | 0 | 0 |

**Same mix.** Just bigger numbers.

```
question 1 against ITSELF:            8 x 8  + 7 x 7   =  113
question 1 against the rambling one:  8 x 80 + 7 x 70  = 1130
```

The computer now believes the rambling question is **ten times more similar to
question 1 than question 1 is to itself.** Which is impossible.

It happened only because the numbers were bigger. **So the comparison is
measuring two things mixed together: how well the mix matches, AND how big the
numbers are.** Only the first was wanted.

## Step 5 — the fix

Before comparing, **shrink every row to the same standard size** - divide each
row by its own size.

| | account | security |
|---|---|---|
| question 1 | 8 -> **0.75** | 7 -> **0.66** |
| the rambling one | 80 -> **0.75** | 70 -> **0.66** |

**Now identical.** The size difference is gone; only the mix survived.

```
question 1 against itself            ->  1.0
question 1 against the rambling one  ->  1.0      <- correctly "the same"
```

The unrelated cooking question is still 0.

**"Every vector must be length 1" means exactly this: shrink every row to the
same standard size before comparing.** "Length 1" is just the standard size
chosen.

## Step 6 — why forgetting is dangerous, and what the code does

Store a row without shrinking it and **nothing breaks**. No error, no crash, no
log line. The comparison still produces numbers - they are just **in the wrong
order**.

So "which stored question is most similar?" returns whichever row had the
**biggest numbers**, not whichever actually matched. The cache serves a wrong
answer and looks healthy doing it.

The class therefore **refuses to be created** with a badly-sized row:

```java
throw new IllegalArgumentException(
    "embedding must be L2-normalised; magnitude was " + magnitude
        + ". Use Embedding.normalizing(float[]) if the source is raw.");
```

It is impossible to hold a bad row by accident. If yours genuinely is not shrunk
yet, you must say so out loud by calling `normalizing(...)`.

**Why check at creation:** there is **one** place a row is created and **many**
places that use one. Shrinking "wherever we remember" means one of the many will
forget. At the single point of creation there is nothing to remember.

> **In one line:** compare the mix, not the size - so force every row to the
> same size first, and refuse to accept one that is not.

---

# 4. Why the rate limiter is a script

## Step 1 — the bucket

Each customer gets a bucket of tokens.

```
acme's bucket:   ##########     10 tokens
```

Every request takes one out. The bucket refills slowly - say 20 a second. Empty
bucket means the request is refused.

That is how one customer is stopped from flooding the system while everyone else
carries on.

## Step 2 — what the server actually has to do

The bucket is a number in Redis.

```
Redis:   acme's tokens = 1
```

So each request needs **four separate steps**:

```
1. LOOK     read the number from Redis     ->  "1"
2. DECIDE   is it more than zero?          ->  yes
3. TAKE     subtract one                   ->  0
4. WRITE    save it back                   ->  Redis now says 0
```

Between step 1 and step 4 the server holds the number in its own memory, and
**Redis still has the old value**.

## Step 3 — two servers at the same instant

There are hundreds of servers. The bucket has **1 token**. Two requests arrive at
the same moment on two different servers.

```
Server A  --  LOOK    ->  reads "1"
Server B  --  LOOK    ->  reads "1"      <- same moment, same answer

Server A  --  DECIDE  ->  more than zero, fine
Server B  --  DECIDE  ->  more than zero, fine

Server A  --  TAKE    ->  1 - 1 = 0
Server B  --  TAKE    ->  1 - 1 = 0      <- it also started from 1

Server A  --  WRITE   ->  Redis = 0, allows the request
Server B  --  WRITE   ->  Redis = 0, allows the request
```

**There was 1 token. Two requests were allowed.**

Server B read the number before Server A had finished writing. With hundreds of
servers doing this thousands of times a second, this is not a rare accident - it
happens constantly.

## Step 4 — the fix

Stop doing the four steps on the server. **Send all four to Redis as one
instruction.**

```
BEFORE                        AFTER
Server: look                  Server: "Redis, do all four steps and tell me"
Server: decide
Server: take                  Redis: looks, decides, takes, writes
Server: write                 Redis: "allowed"
```

**Redis does one job at a time, start to finish.** It begins nothing else until
the current instruction is completely done. So:

```
Server A's instruction  ->  Redis does all four  ->  tokens 0  ->  "allowed"
Server B's instruction  ->  Redis does all four  ->  tokens 0  ->  "REFUSED"
```

There is no longer a gap for the second server to slip into.

That instruction is a small script in a language called **Lua**, stored at
`gateway/src/main/resources/scripts/token_bucket.lua`. Redis runs the whole
script as one indivisible job. There is a second, `budget_record.lua`, doing the
same for the spending budget.

## Step 5 — why the keys have curly braces

A customer has several keys, and the script needs more than one of them:

```
acme's token bucket
acme's spending budget
acme's cached answers
```

Redis runs across several machines and **spreads keys between them**:

```
machine 1:   acme's bucket
machine 2:   acme's budget      <- different machine
```

And the rule is: **a script may only touch keys that are all on one machine.**
Across two machines Redis simply refuses to run it.

Redis picks the machine from the key's name - normally the **whole** name. But if
part of the name is wrapped in braces, it uses **only that part**:

```
{tenant:acme}:bucket
{tenant:acme}:quota
{tenant:acme}:prompt:9f86d081...
 ----+------
 only this part decides the machine
```

All three share that part, so all three land on the same machine, and the script
is legal.

**Why this trips people up:** leave the braces out and everything works - on a
single Redis. A developer tests locally, it is perfect, and then in production
Redis is a cluster, the keys scatter, and the script starts failing. **A bug
invisible in development.**

> **In one line:** four steps done separately can be interrupted, so send all
> four to Redis as one script - and because a script can only touch keys on one
> machine, use the braces to force one customer's keys to live together.

---

# 5. Errors as a closed list

## Step 1 — every problem needs a status number

When something goes wrong, the caller has to be told *what* went wrong, as an
HTTP status number:

| what went wrong | status |
|---|---|
| no valid API key | 401 |
| too many requests | 429 |
| prompt too long | 413 |
| spending limit reached | 402 |
| the provider timed out | 504 |

So somewhere there is code saying *"given this problem, send that number"*.

## Step 2 — the usual way, and what goes wrong

The normal Java approach is one exception class per problem, and code to map
them:

```java
if  (error is UnauthenticatedException)   send 401
if  (error is RateLimitedException)       send 429
if  (error is PayloadTooLargeException)   send 413
else                                      send 500
```

Note that last line. **Anything unrecognised becomes a generic 500.**

Six months later someone adds `BudgetExhaustedException`. They throw it in the
right place. It compiles, tests pass, **and nobody adds it to the list above.**

So a customer hitting their spending limit gets **"500 Internal Server Error"** -
which means *"something broke on our side"*. Nothing broke. They ran out of
budget, and the right answer was 402 with a clear message.

**Nothing failed, no test broke, no log said anything.** The only way to find out
is a customer reporting that your service is broken when it is not.

## Step 3 — what is done instead

One list of every possible error, in one file:

```java
public sealed interface GatewayError {
    record Unauthenticated(String message)          { }
    record PayloadTooLarge(int actual, int limit)   { }
    record RateLimited(Duration retryAfter)         { }
    record BudgetExhausted(long limit, long used)   { }
    record UpstreamTimeout(Duration elapsed)        { }
    ...
}
```

### `sealed` is a real Java keyword (Java 17+)

A **normal** interface can be implemented by anyone, anywhere, in any file. So
Java can never know how many kinds exist.

A **sealed** interface can only be implemented by the types written inside the
same file. From anywhere else:

```java
record MyOwnError(String msg) implements GatewayError { }   // WILL NOT COMPILE
```

The compiler enforces it. It is a language rule, not a convention.

Java learns the list either from an explicit `permits` clause, or - as here -
because every implementation is nested inside the same file.

**The one thing it buys:** because the list is closed, Java knows exactly how
many kinds there are, and can check whether your code handled all of them.

## Step 4 — the compiler becomes the reminder

```java
int status = switch (error) {
    case Unauthenticated e  -> 401;
    case RateLimited e      -> 429;
    case PayloadTooLarge e  -> 413;
    case BudgetExhausted e  -> 402;
    case UpstreamTimeout e  -> 504;
};
```

**No `else`. No `default`.** There is no "anything I forgot becomes 500".

Because the interface is sealed, Java checks every kind appears. Miss one and:

```
error: the switch expression does not cover all possible input values
```

The build fails. So now repeat the six-months-later scenario: someone adds
`QuotaFrozen` to the list, compiles, and **the build breaks immediately**,
pointing at the switch. They cannot continue until they decide its status.

| | exception classes | sealed list |
|---|---|---|
| you add a new error | compiles fine | **build fails** |
| what the customer sees | `500 Internal Server Error` | the status you chose |
| how you find out | a customer complains | immediately, on your machine |

## Step 5 — each error carries its own facts

The errors are not just names. They hold data:

```java
record RateLimited(Duration retryAfter)
record PayloadTooLarge(int actualCodePoints, int limitCodePoints)
```

**`RateLimited` holds how long to wait.** HTTP has a `Retry-After` header for
exactly this, so the client stops hammering you:

```java
case RateLimited e -> response.header("Retry-After", e.retryAfter().toSeconds())
```

Without the data on the error the handler would have to guess - and a guessed
`Retry-After` is worse than none: too short and the client retries into another
refusal, too long and you slowed them down for nothing.

**`PayloadTooLarge` holds both numbers**, so the message can be
*"prompt is 600 characters, limit is 100"* rather than *"payload too large"*. The
first tells the caller what to change; the second makes them guess.

> **In one line:** keep every possible error in one closed list, so the compiler
> forces you to decide what each one means - and put the relevant numbers on each
> error, so the message can be specific instead of generic.

---

**Still to add:** 6 (the resilience wrapper), 7 (the architecture test),
8 (declaration order). Summary of all eight: [B6](B6-lld.md).
