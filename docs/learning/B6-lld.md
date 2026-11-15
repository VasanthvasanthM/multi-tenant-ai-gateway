# B6 — The LLD: the decisions inside the classes

[`LLD.md`](../architecture/LLD.md) is 754 lines. This is the guided tour: eight
decisions where the obvious choice is wrong, each one explained in plain words.

These are the ones worth being able to talk about. Every one is a place where a
reasonable developer would have written something subtly broken.

---

## 1. `CacheKey` — the bug that would be a data breach

A cache key is built from several fields: tenant, model, prompt, temperature,
and so on. You hash them together to get one short string.

**The obvious way:** glue them together and hash the result.

```java
digest.update(tenantId.getBytes());
digest.update(modelId.getBytes());
```

**Why that is a security hole.** Consider two different customers:

| tenant | model | glued together |
|---|---|---|
| `acme` | `gpt-4` | `acmegpt-4` |
| `acmeg` | `pt-4` | `acmegpt-4` |

**The same string.** So the same hash. So **the same cache entry** — and tenant
`acmeg` is served tenant `acme`'s answer.

Measured, not argued:

```
WITHOUT a length prefix:
  acme + gpt-4  -> f8aa6f9de8b2dc04d0d9f13f22e4277ace4a3e38e09f4c2eb749e92d0327d61c
  acmeg + pt-4  -> f8aa6f9de8b2dc04d0d9f13f22e4277ace4a3e38e09f4c2eb749e92d0327d61c
  SAME DIGEST?   True
```

**The fix** is to write each field's length before the field itself:

```java
private static void putString(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    putInt(digest, bytes.length);   // <- four bytes saying how long the next bit is
    digest.update(bytes);
}
```

Now the hash sees `4|acme|5|gpt-4` versus `5|acmeg|4|pt-4` — different inputs,
different digests:

```
WITH the length prefix:
  acme | gpt-4  -> 0f7323e18a8d29e2d0cbea377881891393de8d0481c8738dbf0ba29db658e2c6
  acmeg | pt-4  -> c607a94ed99d497c11d7e8ceb3ad8c92b0b5d549e52a1eed57a0d811eb88eab6
  SAME DIGEST?   False
```

**The general lesson:** when you hash several fields together, the boundaries
between them must be part of what you hash. Otherwise the boundaries are
guessable, and in a multi-tenant system a guessable boundary is a way to read
someone else's data.

### And two fields deliberately left OUT of the key

| excluded | why |
|---|---|
| `stream` | the same question streamed or buffered has the same answer. Including it would halve the hit rate for nothing |
| `idempotencyKey` | it is unique per request **by definition**. Include it and every lookup misses — the cache is silently switched off while looking perfectly healthy |

That second one is the kind of mistake that is never found, because nothing
errors. You just quietly pay full price forever.

---

## 2. `PromptNormalizer` — three traps in "tidying up text"

Before hashing a prompt you normalise it, so trivial differences do not cause
misses. Three choices here are each the *opposite* of the obvious one.

### NFC, not NFKC

Unicode has several ways to write the same character, so you normalise. There
are two common forms, and the aggressive one is wrong:

| input | NFKC gives | problem |
|---|---|---|
| `x²` | `x2` | **different mathematics** |
| `ﬁ` (one ligature char) | `fi` | arguably fine |

NFKC would merge `x²` and `x2` into one cache entry. For a cache key that is a
*meaning* change. So NFC — which only unifies genuinely identical characters.

### Internal spaces are preserved

Collapsing runs of spaces is the standard tidy-up. It is wrong here.

**A large share of LLM prompts contain code**, and in code indentation is
meaning. Collapsing it would merge two different Python functions into one cache
entry.

So only leading and trailing whitespace is stripped — and with the Unicode-aware
`\p{IsWhite_Space}`, not `String.trim()`, because `trim()` only knows ASCII and
would leave a non-breaking space in place.

### Case is preserved

Lowercasing feels obviously right. But `SELECT` and `select` can mean different
things, and lowercasing damages non-English text in ways that are hard to
predict.

### And length is counted in code points

```java
value.codePointCount(0, value.length())   // not value.length()
```

`String.length()` counts UTF-16 units. An emoji counts as 2, and much
non-Latin text counts higher than its visible length. Using it would make a
"1,000 character limit" twice as strict for someone writing in an emoji or in a
script outside the basic plane.

**The theme across all four:** normalisation that is right for search is wrong
for a cache key, because a cache key must preserve every difference that changes
the answer.

---

## 3. `Embedding` — an invariant enforced at construction

Tier 2 compares vectors with inner product. **Inner product equals cosine
similarity only if both vectors have length 1.**

A vector that is not normalised does not throw. It ranks **wrongly** — you get
plausible-looking numbers that are quietly meaningless.

So the class refuses to exist in an invalid state: normalisation happens in the
constructor, not in whichever caller remembered to call it. You cannot hold an
`Embedding` that would compare wrongly.

**The pattern:** if a rule must always hold, enforce it where the object is
made, not where it is used. There is one constructor and many call sites.

---

## 4. `token_bucket.lua` — why the rate limiter is a script

The rate limiter has to: read the token count, work out how many to add back,
subtract one, and write it. Four operations.

**Done from Java, over the network:**

```
pod A reads: 1 token left
pod B reads: 1 token left      <- same instant
pod A writes: 0, allows the request
pod B writes: 0, allows the request    <- the limit was 1, two got through
```

With hundreds of pods this is not a rare race, it is constant.

**The fix:** send the whole thing to Redis as a Lua script. Redis runs scripts
**atomically** — single-threaded, nothing interleaves. The read-modify-write
becomes one indivisible step.

**And this is why the Redis keys have braces.** A script may only touch keys
living on one Cluster node. `{tenant:acme}` forces all of one tenant's keys onto
one node, which is what makes the script legal. Without the braces the limiter
works perfectly on a single Redis and breaks the moment you run a cluster — a
bug invisible in development.

---

## 5. `GatewayError` — a sealed list instead of exceptions

Errors are a **sealed interface** with one record per failure, rather than a
handful of exception classes:

```java
sealed interface GatewayError {
    record RateLimited(Duration retryAfter) implements GatewayError {}
    record Overloaded(int inFlightLimit) implements GatewayError {}
    record UpstreamTimeout(Duration limit) implements GatewayError {}
    ...
}
```

**What this buys:** the compiler checks you handled every case. Add a new error
and every `switch` over it stops compiling until you decide what HTTP status and
message it gets.

With exceptions, a new one just falls through to a generic 500 and nobody
notices until a customer asks why they got an unhelpful error.

**Also:** each error *carries its data*. `RateLimited` holds the retry delay, so
the handler can set a `Retry-After` header without guessing.

---

## 6. `ResilientLlmProvider` — a wrapper, not a provider

Retries and circuit-breaking are not in the provider classes. They are in a
**decorator** that wraps any of them.

```
ProviderRegistry → ResilientLlmProvider → OpenAiCompatibleProvider
                                        → OllamaProvider
                                        → MockLlmProvider
```

**Why:** otherwise every provider reimplements retry logic, and they drift. One
has a 3-attempt retry, another 5, a third forgets backoff. Written once in a
wrapper, every provider gets identical behaviour and the providers stay dumb —
each one only knows how to make one HTTP call.

**The pattern is the Decorator**, and this is the case it is actually for:
behaviour that is orthogonal to what the thing does.

---

## 7. `ArchitectureRulesTest` — nine rules the compiler cannot express

A test that asserts things about the *shape* of the codebase, not its behaviour.
Rules like:

- `common` must not import Spring
- the gateway must not import another service's packages
- no service may import another service at all

**Why this is a test and not a convention:** a convention in a README is broken
by the first person in a hurry, and nobody notices for months. A test fails on
the commit that breaks it, with a message naming the rule.

The one that matters most here is *"no service imports another service"*. That
is the single rule keeping
[ADR-0010](../adr/0010-no-synchronous-inter-service-calls.md) true. Without it,
somebody adds one convenient import, then one convenient method call, and the
uptime-multiplication problem is back — invisibly, because it still works in
development where everything is up.

---

## 8. The static-initialiser trap

A real bug, worth knowing because it is invisible.

In Java, a `static final` field is computed when the class is **first loaded**.
If that computation reads configuration, it reads whatever the configuration was
at that moment — which may be before Spring has finished setting it up.

The failure mode is the nasty kind: it works on your machine, where class
loading happens to occur after configuration, and fails in a container where
the order differs. And it fails as a **wrong value**, not an exception.

**The rule the LLD draws from it:** anything depending on configuration must be
an instance field set in a constructor, never a static initialiser.

---

## The pattern across all eight

Look at what they have in common:

| | the obvious choice | why it is wrong |
|---|---|---|
| CacheKey | concatenate and hash | field boundaries become guessable → cross-tenant read |
| Normalizer | tidy the text up | tidying destroys differences that change the answer |
| Embedding | normalise at the call site | one call site will forget, and it ranks wrongly rather than failing |
| Rate limiter | read-modify-write in Java | races constantly across many pods |
| Errors | exception classes | a new one silently becomes a 500 |
| Resilience | retry inside each provider | implementations drift apart |
| Boundaries | a convention in the README | broken by the first person in a hurry |
| Config | a `static final` | reads the value before it is set, and gives a wrong answer |

**Every single one fails silently.** None of them throws, none shows up in a
log, and most work fine on a laptop. That is the thread running through the
whole low-level design: the dangerous bug is not the one that crashes, it is the
one that returns a plausible wrong answer.

That is also why this project leans so hard on tests that pin exact values,
mutation testing, and architecture rules — the failures it is most afraid of are
exactly the ones a smoke test cannot see.

---

**Previous:** [B5d — shard migration in plain words](B5d-shard-migration-in-plain-words.md).
**Next:** B7 — Redis.
**Full detail:** [`LLD.md`](../architecture/LLD.md).
