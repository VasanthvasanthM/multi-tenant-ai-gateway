# B13 — Challenges, and how they were solved

Twenty-odd real problems, grouped by **kind** rather than by date, because the
kinds repeat and the dates do not.

The full log, with dates and fixes, is in
[`problems-and-solutions.md`](../problems-and-solutions.md). This is the version
to read before an interview.

---

## The one thing every entry has in common

Count the problems where something **crashed**: one. The Alpine/glibc
`UnsatisfiedLinkError` - and even that only appeared once someone enabled the
feature.

**Everything else returned a plausible wrong answer, silently skipped work, or
reported success.**

That is not a coincidence about this project. It is what failure looks like in a
distributed system, and it is why the toolkit here is known-answer tests,
mutation testing, architecture rules and a problem log - rather than smoke tests.

---

# Kind 1 — Built, tested, and never called

Four components, each written carefully, each unit-tested, each doing nothing.

| | status when found |
|---|---|
| `SagaOrchestrator` | a `@Component`, unit-tested, and **nothing implements `SagaStep`** |
| `ReplicaLagMonitor` | `start()` called only from its own test - no `@PostConstruct` |
| dashboard sessions | endpoints and client functions on both sides, **no login form between them** |
| `OutboxRelay` | correctly scheduled every second, and **nothing ever inserts a row** |

### Why unit tests cannot catch this

Each test constructs the thing directly:

```java
new ReplicaLagMonitor(...).start();   // the test calls start() itself
```

**The question a unit test never asks is "does anything in production invoke
this?"** The component works perfectly in isolation, which is precisely what the
test proves.

### The worst of the four

`ReplicaLagMonitor` was worse than "not monitoring", because its gauge is
registered in the constructor:

```
gateway_replica_lag_seconds   existed, and read a healthy 0.0 forever
replicaTooFarBehind()         answered "never behind" for every shard
```

ADR-0007 permits reading from replicas **on the condition** that lag is
monitored. The monitoring reported perfect health without ever querying a
database.

And the irony is in the class's own javadoc: the gauge is registered up front
*precisely so a missing series cannot hide a dead probe*. **That made the dead
probe indistinguishable from a healthy one instead.**

### How they were found

```
SagaOrchestrator       by being asked "which problem does it solve?"
ReplicaLagMonitor      by copying its polling idiom and checking who called start()
dashboard sessions     by the user remembering a screenshot: "I didn't log in"
OutboxRelay            by being asked "where is the outbox used, and why?"
```

**Three of the four were found by explaining the code**, not by running it.

---

# Kind 2 — Numbers nobody checked

| number | what it drove | reality |
|---|---|---|
| `max-in-flight: 256` | the shed limit | written 8 days **before** any measurement; the campaign showed collapse at 90 |
| `queue-capacity: 128` | embedding backpressure | its own javadoc derived a limit for a 4-CPU pod; the deployment has 1-2 |
| bulkhead `64` | per-provider cap | became **unreachable** the moment max-in-flight dropped to 64 |
| `2 KiB` Redis entry | the entire Redis fleet | never measured; a sample row computes to 0.45 KiB |

### The one worth telling

```
written 2026-11-06   max-in-flight: 256, in the first commit
measured 2026-11-14  271.3 rps at 198 ms
```

Little's Law on the measurement puts saturation at **~54 requests in flight**.
The campaign recorded collapse at **90**. The limit was set at **256** - five
times past the point the pod is already finished.

So it could never fire. **The gateway would die before its own protection
engaged**, and every test passed, because a limit that is too high is
indistinguishable from a correct one until the day it is reached.

### And the sequel

Fixing it broke something else, in a way worth knowing about:

> Lowering `max-in-flight` to 64 made the per-provider bulkhead of 64
> **unreachable** - the pipeline can never admit enough requests to trip it. A
> limit that cannot trigger still passes every test it has, and its gauge reads
> zero forever, which is also what working looks like.

**When you tighten one limit, check every limit that was sized against the old
one.** Two independent constants encoding one relationship is a bug waiting for
whoever changes either.

---

# Kind 3 — Measurements that measured the wrong thing

Nine load-test sweeps were run. **Eight were discarded.** Not one of them
errored; each returned a plausible number.

```
the sweep could only report success        it asked "did you keep up?" at a fixed rate
every rerun looked better than the last    "unique" prompts reset between runs
the cache switched itself off under load   a 500 ms Redis timeout cascade
the API key was never configured           "excellent throughput" was the speed of 401s
the hardware wall was my own config file   .wslconfig capped the VM at 3 CPUs
more cores made pods SLOWER                400m limit, 6 event loops, one 40 ms quota
kubectl top said 51-76%                    for pods throttled in 46-72% of periods
a run reported 65% twice, byte for byte    CRLF endings against patterns ending in \n
```

### The best one to tell

**"More cores made the pods slower."**

A 400m CPU limit on a 6-CPU node. The JVM read the *node's* core count, created
six Netty event loops, and those six loops shared the unchanged 40 ms CPU quota -
throttled in **96.9%** of periods. More parallelism, less throughput, because the
parallelism was imaginary.

The fix is one line of configuration. **Finding it took six runs**, because every
symptom pointed at the laptop.

### What came out of it

Every gate in the sweep script exists because a run was once wrongly accepted:

```bash
HIT_RATIO_FLOOR=0.62     # below this, Tier 1 was failing, not serving
die "the gateway container has no CPU limit"
# an invalid point is treated as ABSENT, never as zero
RUN_ID + __VU + counter  # so "unique" prompts are unique across runs AND users
```

> **A load test that cannot fail is not a measurement.**

---

# Kind 4 — Correct code, wrong environment

### Tier 2 could never have run in a container

```
eclipse-temurin:21-jre-alpine
```

ONNX Runtime's `libonnxruntime.so` is linked against glibc. Alpine uses musl. It
needs the `ld-linux-x86-64.so.2` loader and fortified symbols musl does not
supply, and `apk add libstdc++` does not help.

**So the semantic cache - the product's entire point - could never have served a
request from a deployed container.**

Three independent defaults hid it:

```
CI builds images with buildpacks     Ubuntu, glibc, always fine
gateway.embedding.model-path unset   starts on the lexical fallback, never loads the .so
tier2-enabled: false                 the shipped default
unit tests run on the host JVM       glibc
```

**Four layers of "it works here", and none of them was the thing that ships.**
Fixed, and CI now asserts the base image is glibc by running `ldd --version`
inside it.

### Tracing never worked

Dependency present. Sampling configured. Endpoint populated. **Zero spans.**

The endpoint pointed at Tempo's **gRPC** port while Spring Boot drives the
**HTTP** exporter. 4317 vs 4318 - one digit.

---

# Kind 5 — Worked, but worse for some inputs

### The cache worked badly for non-Latin text

```java
if (promptText.length() > TIER2_MAX_PROMPT_CHARS)   // 1,000
```

`String.length()` counts UTF-16 units, not characters. One emoji counts 2; every
character outside the Basic Multilingual Plane counts 2.

```
English prompt   1,000 characters allowed
Japanese/emoji     ~500 allowed
```

So prompts well inside the truncation-safe length were **skipped from Tier 2 for
no reason** - a cache that quietly worked worse for some languages. Nothing
failed; the hit ratio was just lower for those tenants.

And the helper that counts code points already existed, used one line away in
`validate()`.

---

# Kind 6 — Confidently wrong answers

### The 0.9987 false positive

```
"requirement 7"  vs  "requirement 8"   →  similarity 0.9987
```

Two questions about **different things**, scoring higher than every genuine
paraphrase measured (0.51-0.91).

The consequence is counter-intuitive and the reason it is the project's most
important finding:

> **Raising the threshold makes the cache LESS safe.** Above 0.99 these
> near-identical prompts are the only thing that still matches, so the surviving
> hits are disproportionately the dangerous ones.

Any prompt carrying an ID, a date or a customer name has this property. **Not
fixed - documented**, because the fix is a fine-tuned model, not a number.

### Shadow mode logged the wrong prompt

Shadow mode exists to show what a lower threshold *would* have served, so you can
judge it before trusting it. It logged only the **stored** prompt - so a correct
match and a 0.9987 false positive looked identical in the log.

**The one thing its output existed to let you do was impossible.** Fixed to log
both prompts side by side.

### The sign that would return the least similar row

```sql
-(embedding <#> CAST(:query AS vector)) AS similarity
```

`<#>` returns the **negative** inner product. Drop the minus and `ORDER BY` puts
the *least* similar row first.

No error. The cache answers "how do I reset my password" with "Lima" - and it
*"presents as 'the model is bad' rather than 'the query is wrong', and can
survive a long time in a codebase."*

---

# Kind 7 — The environment fighting back

Worth including because interviews ask about debugging, and these cost real
hours:

```
heredocs truncating on apostrophes      → write via a file, or python with chr(10)
\u escapes rewritten before Python saw them → build chars from code points
a Python edit that truncated a 454-line test to 0 bytes
    open(p, "w") empties immediately, then the write failed
    → always write to a temp file, then os.replace()
PowerShell split -Djacoco.skip=true on the dot
kubectl cp rejected C:/... paths as a remote spec
a script committed 100644 twice          → git update-index --chmod=+x AFTER git add
Maven reported BUILD SUCCESS on a stale incremental build
    while six test call sites still used the old signature
-pl gateway tested against the INSTALLED common jar
    NoSuchMethodError / NoClassDefFoundError for code that was right there
```

**The last two are the dangerous ones**, because they are green builds that prove
nothing. Both are solved the same way: a full-reactor `clean` build is the only
one whose result means anything.

---

## What I would say if asked "what was the hardest bug?"

Not the hardest to fix - they were all small fixes. The hardest to **see**:

> *"`ReplicaLagMonitor` registers its gauge in the constructor, deliberately, so
> that a missing metric series cannot hide a dead probe. But nothing ever called
> `start()`. So the gauge existed, read a healthy 0.0 forever, and the replica
> health check answered 'never behind' for every shard - while an ADR rested its
> entire safety argument on that monitoring existing. The mitigation against
> silent failure was the thing that silenced it. I found it by copying the class's
> polling idiom for something else and asking who called its start method."*

That answer demonstrates the five things worth demonstrating: you read code you
did not write, you check wiring rather than assuming it, you understand why the
original decision was right, you can explain the irony, and **you found it by
explaining the system rather than by waiting for an alert.**

---

## The counting, as of 2026-11-17

```
15   entries in the problem log
 8   load-test runs discarded, of 9
 4   components built, tested, and never called
 4   load-bearing numbers that were never measured
 1   thing that actually crashed
```

And the review technique that found most of them:

> *"Several were found by being asked to explain the code, which turns out to be
> an effective review technique: you cannot explain why a line is correct without
> checking whether it is."*

---

**Previous:** [B12 — testing with k3d and k6](B12-testing-with-k3d-and-k6.md).
**The log:** [`problems-and-solutions.md`](../problems-and-solutions.md).
