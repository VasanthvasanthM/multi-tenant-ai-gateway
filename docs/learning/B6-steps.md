# B6 (steps) — the LLD ideas, one step at a time

The same eight ideas as [B6](B6-lld.md), but broken into numbered steps with
nothing assumed. [B6](B6-lld.md) is the summary; this is the teaching version.

All eight ideas are here. [B6](B6-lld.md) is the short summary.

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

# 6. Why retries live in a wrapper

## Step 1 — the problem

The gateway calls an outside AI provider over the internet. Sometimes that call
fails - the provider is briefly overloaded, or the network hiccups. A single
failure is usually not permanent, so you want to **try again**:

```
call the provider  ->  failed
wait a moment
call again         ->  worked
```

The customer never knows anything went wrong.

There are **four** provider classes: one for real OpenAI-compatible APIs, one for
a local model via Ollama, and two mocks. Each knows how to make its own kind of
call.

**Where does the retry logic go?**

## Step 2 — the obvious answer, and why it rots

Put it inside each provider. Four copies of the same idea. It works on day one.

A year later, after different people have touched different files:

```
OpenAiCompatibleProvider  ->  retries 3 times, waits 1 second
OllamaProvider            ->  retries 5 times, waits 1 second
MockLlmProvider           ->  retries 3 times, no wait at all
the second mock           ->  someone forgot to add retries
```

**Nobody did anything wrong.** Each change was reasonable in its own file. The
four have quietly drifted apart.

And nobody notices, because each provider works fine tested on its own. You find
out from something odd in production - *"why does it recover with OpenAI but
give up instantly on Ollama?"* - and the answer is in a file nobody thought to
compare with another file.

**The shape of the problem:** retrying has nothing to do with *being* OpenAI or
*being* Ollama. It is the same behaviour whoever you call. Behaviour identical
across all implementations should not be copied into each of them.

## Step 3 — the fix

Write it **once**, in something that goes **around** whichever provider is in
use.

```
                  +-------------------------+
request  -------->|  the retry wrapper      |
                  |   +-----------------+   |
                  |   |  OpenAI         |   |
                  |   +-----------------+   |
                  +-------------------------+
```

The wrapper retries. The provider inside makes **one** call and knows nothing
about retries. Swap OpenAI for Ollama and the wrapper behaves identically.

What that buys:

- **one copy of the rules** - change "3 times" to "4 times" once, everyone follows
- **the providers cannot drift**, because there is nothing in them to drift
- **a new provider gets it free** - add a fifth tomorrow and it behaves the same

The pattern is called a **wrapper** (or decorator), and the class here is
`ResilientLlmProvider`.

## Step 4 — it does four jobs, and the order matters

```
Retry ( CircuitBreaker ( TimeLimiter ( Bulkhead ( the actual call ) ) ) )
```

From the inside out:

| job | in plain words |
|---|---|
| **Bulkhead** | no more than N calls at once, so one slow provider cannot tie up every thread |
| **TimeLimiter** | give up after 30 seconds, so a request cannot hang forever |
| **CircuitBreaker** | if it keeps failing, stop calling it for a while |
| **Retry** | try again a few times |

**Retry is outermost**, which means every attempt goes back through the circuit
breaker and asks again *"is this provider still considered down?"*

```
attempt 1  ->  breaker: "fine, go ahead"  ->  call fails
attempt 2  ->  breaker checks AGAIN       ->  "no, it is down now"
               -> fails instantly, no 30-second wait
```

Put Retry **inside** the breaker instead and the breaker is consulted only once,
before the retries begin:

```
breaker says "fine, go ahead"
  attempt 1  ->  fails, wait 30 seconds
  attempt 2  ->  fails, wait 30 seconds
  attempt 3  ->  fails, wait 30 seconds
```

Ninety seconds hammering a provider everyone already agrees is broken - and the
circuit breaker, whose entire job is to prevent exactly that, was never asked
again.

> **In one line:** behaviour that is identical for every implementation belongs
> in one wrapper around them, not copied into each - and when you nest such
> behaviours, which one wraps which changes what they mean.

### A related question: one provider at a time, or all of them?

**One at a time**, like a list of phone numbers in order. Ring the first; if it
answers you are done and the others are never called. Only if it fails do you
ring the second.

```java
attempt = chain.get(0).complete(request);
for (int i = 1; i < chain.size(); i++) {
    attempt = attempt.onErrorResume(error -> next.complete(request));
}
```

`onErrorResume` is "if that failed, do this instead".

**And the list is global, not per-tenant.** `ProviderRegistry` has no mention of
tenants - the chain comes from one config block, so every customer rings the same
first choice. Per-tenant providers would be a reasonable feature; it is not
built.

The chain *is* filtered by **which model** was asked for, so a request for a
model only Ollama serves gets a chain containing only Ollama.

**Why ordered rather than spread across all three:** different providers answer
the same question differently. Spread the traffic and the same question gets a
different answer depending on routing luck - and the cache stores whichever
arrived first, so the answer depends on who happened to serve the first person
who asked. A strict primary keeps answers consistent. **Fallbacks are for
failure, not for sharing load.**

---

# 7. A test that checks the shape of the code

## Step 1 - a rule the language cannot express

The design rule: **no service may call another service directly.** If the gateway
had to wait for the metering service, metering being slow makes the gateway slow
and metering being down makes the gateway down - your uptime becomes everyone's
uptime multiplied. So services talk only through Kafka and the database.

**Java has no way to say that.** You can mark a method private, but you cannot
write "this whole service may not import that whole service". There is no word
for it.

## Step 2 - the usual answer, and why it fails

You write it in the README. Everyone reads it, everyone agrees, and it works for
a while.

Six months later a developer is fixing something urgent and needs a tenant name,
which the control plane has:

```java
import io.vasanth.gateway.controlplane.TenantService;   // one import
String name = tenantService.findName(tenantId);          // one call
```

Two lines. It compiles. Tests pass. It works - and on a laptop it works
**perfectly**, because every service is running there.

What actually happened: the rule is broken, the gateway now waits for the control
plane, and nothing says so. Build green. Tests green. The README still states the
rule, unchanged and now untrue. The review passed, because two lines fixing an
urgent bug look reasonable.

You find out months later when a bad control-plane deploy takes down the
**gateway**, and you spend hours asking why.

**A rule written in a document is a wish.**

## Step 3 - turn the rule into a test

Write a test that **reads the code** and checks it. It does not run the program,
it inspects it:

> "Look at every class in the gateway. List what it imports. If any belongs to
> another service - fail."

Now the same developer makes the same edit, saves, builds:

```
FAILED: layering
  Class gateway.pipeline.CompletionPipeline
  may not depend on controlplane.TenantService
```

The build stops, seconds later, on their own machine. They cannot push it, so
they find another way - and another way exists: read it from Redis, or take it
from a Kafka event.

| | rule in a README | rule as a test |
|---|---|---|
| when you find out | months later, in production | seconds later, on your laptop |
| who finds out | a customer | the person who wrote it |
| what it costs | an outage and hours of confusion | thirty seconds |

**This single test is the only thing actually keeping the no-synchronous-calls
design true.** Without it the design is a story in a document.

## Step 4 - the other eight rules

| rule | what it catches |
|---|---|
| noBlockingOnReactiveTypes | one .block() freezing a thread that serves many requests |
| noThreadSleep | the same damage via Thread.sleep |
| noDirectThreadCreation | threads nobody manages, counts or shuts down |
| noStaticNowCalls | the clock hard-wired, so time cannot be faked in a test |
| noSystemOut | System.out.println instead of logging - invisible to Loki |
| layering | a service importing another service |
| commonStaysFrameworkFree | the shared core quietly growing a Spring dependency |
| noStringEqualsOnSecrets | comparing a secret with == |
| noRandomForSecurity | a predictable random number used for something security-related |

**Two are security rules.**

*Comparing a secret with ==* stops at the first wrong character:

```
stored:  "secret123"
sent:    "xyz..."     ->  wrong at character 1  ->  returns immediately
sent:    "secret9.."  ->  wrong at character 7  ->  takes slightly longer
```

That timing difference is measurable over many attempts, so an attacker can guess
a key one character at a time by watching which guess takes marginally longer. It
turns an impossible problem into an easy one. The fix is a comparison that always
checks every character, so timing never varies.

*A predictable random number* - some generators are fast but predictable: see
enough output and you can compute the next value. Fine for picking a colour,
catastrophic for generating a key.

**Why these two especially need a test:** a human reviewer would have to spot them
every single time, forever, in every pull request, while also thinking about
everything else. A test spots them every time and never has a bad day.

Every one of the nine compiles perfectly, passes normal tests, works on a laptop,
and fails slowly or invisibly in production.

> **In one line:** a rule you cannot express in the language, write as a test. A
> rule in a document is a wish.

---

# 8. Declaration order can crash a class

## Step 1 - the setup

A small class for a provider name - "openai", "ollama", "cache". It holds two
things:

```java
private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");
public  static final ProviderId CACHE = new ProviderId("cache");
```

**VALID is the rule for what names are allowed:** 2 to 32 characters, lowercase
letters, digits and dashes, not starting with a dash. It is checked every time a
ProviderId is created, so "OpenAI", "open ai" and "-openai" are all rejected at
once. It matters because the name ends up in log lines, metric labels and
database rows, where a space or a quote would break something downstream.

**CACHE is a ready-made name meaning "no provider was involved".** Every response
records which provider produced it, but a cache hit had no provider - nobody
called OpenAI, the answer came from Redis. So a cache hit is labelled "cache",
which is where the provider field reading "cache" in a response comes from. It is
used in exactly three places, all cache hits. A constant rather than a new object
each time: created once, spelled correctly in one place, and comparable by
identity.

**And CACHE is built by the constructor that reads VALID.** So CACHE depends on
VALID.

## Step 2 - Java sets them up in written order

Both are static final, so Java creates them once, the first time anything uses the
class - going **top to bottom** in written order.

This way round, fine:

```
Java does line 1  ->  VALID now exists
Java does line 2  ->  CACHE asks for VALID  ->  it is there  ->  works
```

Swapped, broken:

```
Java does line 1  ->  CACHE asks for VALID  ->  not created yet, still empty
                                             ->  CRASH
```

**Nothing warns you.** Swapping two lines is the most harmless-looking edit there
is - someone sorting fields alphabetically, or grouping the public ones together,
makes exactly this change and thinks nothing of it.

## Step 3 - why the crash is worse than it sounds

You would expect an error naming VALID and the line. You do not get one.

Static final fields are created the first time **anything** touches the class - in
Spring, deep inside the framework assembling objects at startup. So you see:

```
ExceptionInInitializerError
  at ... 60 lines of Spring framework ...
  Error creating bean with name 'completionPipeline'
```

The message says Spring failed to create a bean. You look at the bean - fine. Its
configuration - fine. Its dependencies - fine. **The real cause is two swapped
lines in a different file that nothing in the error mentions.**

> **The dangerous error is not the one with no message. It is the one with a
> confident message pointing at the wrong place.**

## Step 4 - the fix

**Part 1, say why.** The real comment in the code:

```java
// ORDER MATTERS. Static initialisers run in declaration order, and the compact
// constructor below reads VALID. If the CACHE constant were declared first it
// would be constructed while VALID is still null, and the class would fail to
// initialise with an ExceptionInInitializerError - at bean-creation time, far
// from the real cause.
```

But a comment is only a request. Someone in a hurry, or an automatic formatter,
is not stopped by it.

**Part 2, a test that touches the constants** - ValueTypeConstantsTest. Touching
ProviderId.CACHE is enough to force Java to set up the class, so a swap fails the
test immediately rather than at some future startup inside a confusing Spring
error.

**Why both:** the comment explains why, so the next person understands rather than
just obeying. The test enforces it, so understanding is not required. A comment
alone decays; a test alone leaves people puzzled about why it exists.

> **In one line:** when one line must come before another and the language will
> not enforce it, write down why - and add a test, so the rule does not depend on
> anyone reading the comment.

This is the same shape as idea 7. There, a rule the language could not express
became a test. Here, an ordering the language will not protect becomes a test.
**Both turn "please remember" into "the build will tell you".**

---

**All eight done.** Short summary of all of them: [B6](B6-lld.md).
