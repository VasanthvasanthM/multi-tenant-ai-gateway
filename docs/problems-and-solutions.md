# Problems found, and how they were solved

A running log. Newest first. Each entry: **what was wrong**, **how it was
found**, **why it was invisible**, and **the fix**.

The pattern worth noticing across almost all of them: **nothing crashed.** Every
one of these either returned a plausible wrong answer, silently did nothing, or
reported success while skipping the work. That is why this project leans on
known-answer tests, mutation testing and architecture rules rather than on
smoke tests.

---

## 2026-11-15 — Tier-2 length guard counted the wrong thing

**What was wrong.** The guard that decides whether a prompt is short enough for
Tier 2 used `promptText.length()`.

```java
if (promptText.length() > TIER2_MAX_PROMPT_CHARS) {    // 1,000
```

`String.length()` counts UTF-16 units, not characters. One emoji counts 2. Every
character outside the Basic Multilingual Plane counts 2.

**The consequence.** The 1,000 limit was really **~500** for such text. Prompts
comfortably inside the truncation-safe length were skipped from Tier 2 for no
reason — so the semantic cache quietly worked **worse for non-Latin scripts and
emoji** than for English. A fairness bug disguised as a constant.

**How it was found.** Explaining the normalisation rules to someone, who then
said "solve all of these problems" — so rather than trusting the code, each rule
was checked against a test. `lengthInCodePoints` existed, was tested, was used
in `validate()`... and was not used in the Tier-2 guard twelve lines away.

**Why it was invisible.** Nothing errors. The prompt is simply skipped, a counter
increments, and the request goes upstream and gets a correct answer. The only
symptom is a hit ratio that is lower than it should be for some users.

**The fix.** Use the helper that already existed:

```java
int promptCodePoints = PromptNormalizer.lengthInCodePoints(promptText);
if (promptCodePoints > TIER2_MAX_PROMPT_CHARS) {
```

Plus `tier2LengthGuardCountsCodePoints`: 600 emoji, which is 600 code points
(under the limit) but 1,200 UTF-16 units (over it), and asserts Tier 2 is
consulted. **Verified the test earns its place** by reverting the fix and
confirming it fails.

**Also learned:** the test had to build its own pipeline, because the shared one
caps prompts at 100 code points and `validate()` rejected the request before it
reached the guard under test. A test that passes for the wrong reason is worse
than no test.

---

## 2026-11-15 — Shadow mode logged the wrong prompt

**What was wrong.** Shadow mode exists to measure Tier 2's false-hit rate before
trusting it. Its only product is a log line a human reads. That line printed the
**stored** prompt and not the **caller's**:

```
tier-2 SHADOW would have served tenant=acme similarity=0.9987
                                prompt="Explain requirement 7 ..."
```

**The consequence.** A reviewer could not tell whether the request had asked
about requirement 7 (a correct match) or requirement 8 (a false hit). Both cases
produced identical output. **The false-hit rate — the entire reason the feature
exists — was uncountable from its own logs.**

**How it was found.** Being asked "how do I know it served 7's answer instead of
8's?" The honest answer was: you cannot.

**Why it was invisible.** The counters were always correct, and there *was* a
shadow-mode test — it asserted the counters. Nothing looked at the thing a human
actually reads.

**The fix.** Thread the caller's prompt from `CompletionPipeline` (which had it)
through `lookup()` into `decide()`, and print both:

```
tier-2 SHADOW tenant=acme similarity=0.9987
    asked="steps to reset a password" wouldHaveServed="how do I reset my ..."
```

Plus `shadowLogCarriesBothPrompts`, which asserts on log output — unusual, and
right, because here the log **is** the feature. The two test prompts are
deliberately different text, so the test cannot pass against an implementation
that prints the stored prompt twice.

---

## 2026-11-15 — `latency_ms` meant a different thing per tier

**What was wrong.** The latency reported in every response changed meaning
depending on which tier answered:

| path | what it measured |
|---|---|
| `tier1_exact` | the Redis round trip |
| `tier2_semantic` | the vector search **only** — not the embedding before it |
| `upstream` | `properties.latency()`, the mock's **configured** delay |

**The consequence.** The three numbers were not comparable, and I had publicly
compared them ("Tier 2 at ~100 ms vs upstream's 40 ms") — a partial measurement
against a constant someone chose.

**How it was found.** Being asked "how do we know this duration? how did we
measure it?" and tracing it instead of answering from memory.

**The fix.** `CompletionResponse.withLatency()`, called once at the edge, so
`latency_ms` is wall-clock for the whole request — the same measurement that
feeds `gateway_request_duration_seconds`. The record's javadoc already *claimed*
this, so the code was brought in line with its own documentation.

**And the second half:** embedding had **no meter at all**, despite being the
dominant cost of a Tier-2 deployment. Added `gateway.embedding.duration`, with
`doFinally` rather than `doOnSuccess` — a failed or cancelled embedding still
burned the CPU, and a timer that counts only successes understates exactly the
overload it exists to reveal.

**Found while writing that test:** a full miss embeds the same prompt **twice**,
once for the lookup and again for the write-back. Logged as A14 rather than
fixed, because this change was about making the cost visible first.

---

## 2026-11-14 — Tier 2 could never have run in a container

**What was wrong.** The local image was `eclipse-temurin:21-jre-alpine`. Alpine
is musl; ONNX Runtime's bundled `libonnxruntime.so` is linked against glibc.

```
UnsatisfiedLinkError: libonnxruntime.so:
  Error loading shared library libstdc++.so.6: No such file or directory
```

`apk add libstdc++` does **not** fix it — `ldd` still reports the glibc loader
missing plus three failed relocations (`__memcpy_chk`, `__fprintf_chk`,
`__vsnprintf_chk`).

**The consequence.** The semantic cache — the feature the product is named for —
could never have served a request from any deployed container.

**Why it hid for months.** Three independent defaults each masked it:

1. CI builds images with buildpacks, which produce an Ubuntu base, so CI images
   were always glibc and always fine. The broken path was the one used only for
   local and k3d runs.
2. `gateway.embedding.model-path` is unset by default, so the gateway logs
   `EMBEDDING FALLBACK ACTIVE` and starts on a lexical model, never touching the
   native library.
3. `tier2-enabled: false` by default. Nothing asked for an embedding at all.

Unit tests never caught it because they run on the host JVM, which is glibc.

**The fix.** Base image `eclipse-temurin:21-jre-noble`. Verified by running the
built container with the model mounted. **Plus a CI check**, because nothing in
CI built this Dockerfile at all — the infrastructure job now asserts the runtime
base is glibc both by name and by running `ldd` in the base image.

---

## 2026-11-14 — The semantic cache can serve a confidently wrong answer

**What was found.** Two prompts asking about *different* requirements scored
**0.9987** similar — higher than every genuine paraphrase measured (0.51–0.91).

```
0.9987  "Explain requirement 7..." vs "...requirement 8..."   <- WRONG, scores highest
0.9101  payment methods paraphrase
0.8308  password reset paraphrase
0.5093  "business hours" vs "when are you open?"
```

**Why it matters.** The ranking is **inverted**: the wrong match scores higher
than every right one. So **raising the similarity threshold makes the cache less
safe**, not more — above 0.99 these near-duplicates are the only thing that
still matches.

Any prompt built from a template with an ID, order number, date or customer name
has this property. A sentence embedding cannot see a short identifier inside
long boilerplate, and that identifier is the entire question.

**The existing guard protects against the wrong thing.** `GatewayProperties`
refuses to boot below 0.90, and its comment cites "cancel my order" vs "cancel
my subscription" as the danger. **Measured, that pair scores 0.7561** — below the
0.80 it warns about.

**Status: not fixed.** The real fix is to compare the lexical diff before
trusting the vector — if two prompts differ only in a numeral or an unknown
token, that difference *is* the question, so refuse the match. Written up in
`tier2-measured.md` with the measurements. Meanwhile `shadow-mode: true` is the
correct default.

---

## 2026-11-14 — Four alert rules were never deployed

**What was wrong.** `alerting-setup.md` listed four gateway alert rules under a
heading saying "What is already configured". Prometheus had **134 alerting rules
loaded and none of them were this project's**.

Two causes: nothing in the repo creates the `observability` namespace the rules
declare, and no install step applies the file.

**Why it was invisible.** Completely silent. Alertmanager was healthy, the Slack
webhook worked, `Watchdog` arrived on schedule, and the channel looked alive —
because the **stock Kubernetes alerts kept it warm** and masked the absence
entirely.

**The fix.** Created the namespace, applied the rules, and rewrote that section
with the apply steps plus a note to **verify by counting rules**, never by
observing that alerts arrive.

---

## 2026-11-14 — A script committed non-executable, twice

**What was wrong.** `seed-demo-data.sh`, then `shard-migrate-all.sh`, both
committed as mode `100644`. Windows records no executable bit.

**The consequence.** `Permission denied` on any Linux runner.

**How it was found.** CI's infrastructure job checks every `scripts/*.sh` for
the bit — a guard added after an earlier incident with `mvnw`.

**The second occurrence is the interesting one.** `git update-index --chmod=+x`
ran *before* the file was staged, so it had nothing to change and reported
success. The fix is to run it after `git add`.

---

## 2026-11-15 — I truncated a test file

**What was wrong.** A Python edit opened the file with `open(p, "w")`, which
**empties it immediately**, then failed on a Unicode error before writing. The
454-line file was left at 0 bytes.

**The fix.** Restored with `git checkout --`, and from then on: write to a temp
file, then `os.replace()`. A failure then leaves the original untouched.

**Also:** the Unicode error itself was a known trap — a `\u` escape sequence in
the content gets rewritten by the tooling before Python sees it. The fix was to
build the character from a code point (`new String(new int[] {0x1F600}, 0, 1)`)
and avoid the escape entirely.

---

## 2026-11-16 — The dashboard login was built from both ends and never joined

**What was wrong.** Noticed by the user, from memory of a screenshot: *"I didn't
log in, I just saw the dashboard data — but now you say there are login and
logout sessions."*

Both are true, and that is the bug.

| piece | status |
|---|---|
| `POST` / `DELETE /v1/dashboard/session` on the gateway | **built and tested** (`DashboardSessionsIT`) |
| `startSession()` / `endSession()` in the dashboard's API client | **built** |
| a login form that calls them | **does not exist** |

`grep -rn "startSession\|endSession" dashboard/src` returns the definitions and
**no callers**.

So the only working credential path is the development shortcut:

```jsx
if (!(apiKeyConfigured ?? hasApiKey())) {
  return <ErrorPanel title="No API key configured" ... />;
}
```

Without `VITE_API_KEY` you do not get a login screen - you get an error panel
telling you to set an environment variable. And that variable is exactly what
the session work existed to remove:

> *"A key in a `VITE_` variable is compiled into the bundle and is therefore
> **public**, so this is for local development only."*

**Why it was invisible.** Every part passes its own tests. The gateway endpoints
have an integration test. The client functions are plain functions. Nothing
tests *"can a human log in"*, because that would need the two halves joined -
which is the thing that is missing.

The client's own javadoc describes a flow the UI cannot perform: *"The key is
typed into a form, sent once, and never stored."* **There is no form.**

**The fix.** Applied the same day. `SignIn.tsx` takes the key in a masked field,
posts it once as a bearer token, and keeps nothing: the response is an
`HttpOnly` cookie that script cannot read. The tenant *name* goes into
`sessionStorage` so a reload does not flash a sign-in form at someone who
already has a session - a display label, not a credential, since the gateway
checks the cookie.

Three details that needed deciding:

- **the cookie must actually be sent.** `fetchBaseQuery` now sets
  `credentials: 'same-origin'` explicitly. It matches fetch's own default, but
  the session is the only credential once the build-time key is gone, and a
  default a refactor could change would break authentication in a way that looks
  like an expired session.
- **a 401 on a session we believed in** drops back to the sign-in form rather
  than showing an error panel telling someone to check a key they never typed.
  The cookie is `HttpOnly`, so there is no way to ask whether it is still valid
  except by using it.
- **the build-time key still wins**, so a developer's loop is unchanged, and the
  sign-out button is hidden in that mode - a key baked into the bundle cannot be
  signed out of, and a button that silently does nothing is worse than no button.

Five tests on the component plus two on the gate; 43 dashboard tests pass.

**The general shape, now seen three times.**

| | built | tested | called in production |
|---|---|---|---|
| `SagaOrchestrator` | yes | yes | **no** - nothing implements `SagaStep` |
| `ReplicaLagMonitor` | yes | yes | **no** - `start()` had no `@PostConstruct` |
| dashboard sessions | yes | yes | **no** - no UI calls them |

Each passes its tests because the test constructs the thing directly. **The
question a unit test rarely asks is "does anything in production actually invoke
this?"** Two of the three were found by being asked to explain the system, and
the third by a user remembering what they saw on screen.

---

## 2026-11-16 — Dual-write, and the service that cannot shard at all

**What was asked.** Pausing a tenant during a migration is a real outage, however
short. The production answer is dual-write: serve the tenant normally and write
every row to both shards, so the target is current at cutover.

**What building it exposed.** Dual-write has to live where writes are routed —
`ShardedDatabase`. Checking which services route through it:

| table | written by | uses `ShardedDatabase`? |
|---|---|---|
| `prompt_cache` | gateway | **yes** |
| `usage_ledger` | metering | **no** — one plain `DatabaseClient` |
| `audit_log` | audit | **no** — one plain `DatabaseClient` |

**metering and audit do not shard.** They hold a single client and write every
tenant's rows to one database. And they cannot be given dual-write without first
being given sharding, because `ShardedDatabase` lives in the `gateway` module
while they depend only on `common` and `kafka-support`.

**The larger bug this implies, which is not about migration at all.** In a
multi-shard deployment, metering writes every tenant's ledger row to its one
configured database, while the gateway *reads* usage through
`ShardedDatabase.forRead(tenant)` — which routes by the ring. **The reader and
the writer would be looking at different databases.** Usage dashboards would read
empty for most tenants.

Nothing is broken today: there is one database, so every route resolves to it.
This is the same shape as the replica lag monitor — correct while the feature is
off, wrong on the day it is switched on, and nothing says so. Logged as **A28**.

**What was built anyway, and why it is still worth having.** Dual-write in
`ShardedDatabase`:

- `forWriteAll` / `forWriteBucketAll` return one client normally and **both**
  shards during a migration, source first — so if the target is unreachable the
  row is still durable where reads are currently being served from
- `forRead` is pinned to the **source** for the whole window, whatever the local
  ring says

That second one is the part worth keeping. It removes the need to merge results
from two shards: the source holds the historical rows *and* everything written
since, so it is the only shard guaranteed complete until the copy finishes. It
also makes the rolling restart safe for free — pods disagree about the ring for a
minute or two, but they agree about this, because the state comes from Redis and
not from local config.

So dual-write today keeps `prompt_cache` warm through a migration (previously the
cache was simply abandoned and refilled over a day), and it is the mechanism
metering and audit will use once A28 lands.

**Two guards that exist because the failure would be silent:**

- a dual-write where `from == to` is **refused**, in the record's constructor and
  in the script. It would otherwise degrade to an ordinary single write while the
  operator believed both shards were being kept current.
- a dual-write for a **sub-sharded whale** throws. Per-bucket source/target
  mapping is not something the planner emits, and writing to the wrong bucket
  pair while calling it replicated is worse than refusing.

**Tests.** 24 across state parsing, the registry, the filter and the routing,
including reads-come-from-the-source with the ring deliberately pointing at the
target. 437 pass.

**The honest summary.** `dual-write` is the no-downtime option and it protects
the cache. The durable tables still need `pause-writes` or the
copy-again-after-cutover procedure until metering and audit can route by shard.
The runbook says which to use and why, in a table, rather than leaving it to be
discovered.

---

## 2026-11-16 — The replica lag monitor never started

**What was wrong.** `ReplicaLagMonitor.start()` was called from exactly one
place: its own test. Nothing in `src/main` called it, and it carried no
`@PostConstruct`. **Lag was never measured in a running gateway.**

**Why it was worse than "no monitoring".** The class registers its gauge in the
constructor, and the comment explains why:

> *"Registered at construction rather than on first successful probe, so the
> series exists from startup. A gauge that only appears once it has a value
> cannot alert on 'this shard has stopped reporting', which is the symptom of
> the worst case."*

That reasoning is correct, and it is what made this invisible.
`gateway_replica_lag_seconds` **existed and read 0.0 forever** — indistinguishable
from a perfectly healthy replica. The mitigation against a silent failure became
the thing that silenced it.

Worse, the constructor also wires the health predicate:

```java
database.useReplicaHealth(this::replicaTooFarBehind);
```

So `ShardedDatabase` was asking a live question and getting a permanently stale
answer: **every replica was judged "not behind"**, whatever it was doing.

**Why that matters beyond a missing graph.** ADR-0007 permits reading from
replicas *on the condition* that lag is monitored and alarmed. The class javadoc
states the stakes itself:

> *"Without this gauge the ADR's safety argument was resting on a mitigation
> that did not exist, which is the worst kind of documented decision."*

It still was. The mitigation existed as code and never ran.

**How it was found.** While wiring A19's pause registry, by copying this class's
background-poll idiom — and checking who called its `start()`. Nobody did.

**The fix.** `@PostConstruct` on `start()`, which Spring calls and a direct
`new` in a unit test does not — preserving the property the javadoc wanted
("separate from the constructor so a test can drive one pass"). Plus
`isProbing()` so the question is answerable, and two tests: one asserting the
annotation (that *is* the wiring, since there is deliberately no other caller),
one asserting start/stop flips `isProbing()`. Verified by removing the
annotation and watching the first test fail.

**Scope, honestly.** No replicas are deployed today — a single Postgres pod — so
nothing was at risk yet. The bug was that the safety mechanism would have been
dead on the day replicas were switched on, and nothing would have said so.

**The general shape.** Same family as `SagaOrchestrator`: a well-written,
well-tested component that **nothing calls**. Unit tests pass because the test
constructs it directly. The question a test rarely asks is *"does anything in
production actually invoke this?"*

---

## 2026-11-16 — Closing the shard-migration write window (A19)

**What was wrong.** Between `copy` starting and the cutover, a tenant's new rows
landed on the **source** shard and were never copied. After the cutover nothing
reads there, so a `usage_ledger` row stops existing — a billing record that
vanishes with no error anywhere.

**The decision.** The runbook listed three fixes: dual-write, dual-read, or a
brief per-tenant pause. Built the pause, because sharding by tenant already makes
it cheap: **one customer is affected for the seconds a copy takes** and the other
99 never notice. Dual-write is more code and more ways to be half-consistent.

**How it works.** `pause-writes` adds the tenant to a Redis set; every pod polls
it every 2 s into an immutable in-memory set, so the request path costs a
`Set.contains` instead of a Redis round trip. A filter placed immediately after
authentication refuses that tenant with **503 `tenant_migrating`** and
`Retry-After: 5`.

**Two deliberate deviations from the runbook's sketch:**

**503, not 429.** A 429 claims the caller sent too much, which is false, and it
would record an operator-initiated migration as rate limiting — a planned
maintenance spike appearing on the abuse dashboard. Fixed the runbook to match
the code.

**It pauses the whole request, not only writes.** Serving a request always emits
a usage event, so there is no way to answer a tenant and write nothing. Called
it what it is: a brief per-tenant outage.

**The failure modes that needed deciding, and the evidence for each:**

| what if | chosen behaviour | why |
|---|---|---|
| one Redis read fails | **keep** the pause | dropping it reopens the window mid-copy; one blip is not evidence |
| Redis unreachable for 60 s | **drop** the pause, count it, log it | holding a paying customer's traffic hostage to a cache outage is the worse failure - the same fail-open logic as the rate limiter |
| the operator forgets to resume | the Redis key's **TTL** expires the whole set | a migration abandoned halfway must not block a tenant forever |
| the filter is ordered before auth | caught by a test | it reads the tenant attribute auth sets, so it would silently pause nobody |

**Tests.** 16 added across the registry, the filter and the error type, including
the stale-view fail-open and the ordering assertion. 424 pass.

**What it does NOT do.** Dual-write and dual-read are still unbuilt, so a tenant
too large for a seconds-long pause still needs the manual
copy → cutover → copy-again procedure, which the runbook keeps.

---

## 2026-11-16 — A limit that could no longer fire, and a rule only one build could see

Two findings from fixing the load-shedding thresholds, both of the same family:
**something stopped working and nothing reported it.**

### The bulkhead became dead code

The per-provider bulkhead allowed **64** concurrent calls. Lowering
`max-in-flight` from 256 to the measured **64** made it unreachable: the pipeline
admits at most 64 requests at a time, so 64 concurrent calls to one provider can
never be exceeded.

Nothing failed. No test broke - **a limit that cannot trigger still passes every
test it has.** Its Micrometer gauge reads zero forever, which is exactly what a
bulkhead that is working correctly also looks like.

**The fix.** Derive it rather than hardcode it:

```java
int perProvider = Math.max(1, properties.limits().maxInFlight() / 2);
```

Half, so one slow provider can hold at most half the pod's admitted capacity and
the rest stays free for cache hits and the fallback provider. The two numbers
now move together, which is the actual defect: they were always coupled and only
one of them knew it.

**The general shape:** when you tighten one limit, check every limit that was
sized against the old one. Two independent constants encoding one relationship
is a bug waiting for someone to change either.

### The architecture rule that only a full reactor build could see

`ArchitectureRulesTest.noSystemOut` bans console writes. `ShardMigrationCli`,
added the day before for A18, violates it **seven times** - and it had been
committed.

It only fails in a **full reactor build**. ArchUnit scans class *directories*,
not jars, so `mvn -pl gateway test` resolves `common` from the installed jar and
sees nothing: 9 tests, all green. Add `-am` so `common` builds in the same
reactor, and the same 9 tests report a violation.

**So "I ran the tests and they passed" was true and meaningless** - it depended
entirely on which module list the command happened to use.

**The fix.** The rule was right to fire and wrong to apply here: a CLI's stdout
is not a side channel, it is **the product** - `ShardMigrationCli` writes TSV on
stdout for another script to consume and its summary on stderr precisely so a
pipe can separate them. Routing either through the logger destroys the contract.
The rule now exempts `*Cli` and says why.

**The lesson is not about ArchUnit.** It is that a green test run proves only
that the checks which *ran* passed. Same family as the CI entries in this log:
a skipped step and a passing step look identical from the outside.

---

## 2026-11-16 — The three load-shedding limits were never checked against the measurement

**What was wrong.** Asked *"how do you know these counts?"* about `max-in-flight:
256`, `queue-capacity: 128` and `maxConcurrentCalls(64)`, the honest answer turned
out to be: **nobody knows.** Checking each against the project's own measured
numbers found four separate problems.

**1. `max-in-flight: 256` has no justification, and the measurement contradicts
it.** It is the only value in its config block with no explanatory comment.

The git history settles where it came from, because the obvious guess is that
the load-testing campaign produced it:

```
2026-11-06   d06d834   max-in-flight: 256 written, in the FIRST commit
2026-11-14   b0dae0d   "Record the first measured throughput figure"
```

**The limit predates every measurement by eight days**, and `git log -S` returns
exactly one commit for it - it has never been changed since the day it was
typed. No deploy profile or load-test script sets it either, so no sweep ever
varied it.

Worse, the campaign that followed **contradicts** the number. The sweeps ran at
64 and 90 VUs, so in-flight never approached 256 and the limit was never once
reached. And at 90 concurrent the gateway did not plateau, it collapsed:

> *"Throughput collapses rather than plateauing - 347 rps at 64 VUs, 55 rps at 90."*

**The system falls over at 90 in flight. The limit that exists to prevent exactly
that is set at 256.**
Against the one measured figure — a 1000m pod sustaining 271.3 rps at a 198 ms
mean — Little's Law gives the concurrency at saturation:

```
271.3 rps x 0.198 s = 53.7 requests in flight
```

The pod is already CPU-throttled in 92.9% of periods at ~54 in flight. The shed
limit is **5x past that**. Inverted, 256 in flight at 271.3 rps implies **944 ms
per request** — shedding only begins once latency is ~5x the measured mean. It
is a memory backstop wearing an admission controller's name.

**2. The embedding queue javadoc argues for a number that is not the one
shipped.** It reasons carefully — *"at roughly 6 ms per inference and 4 workers,
a queue of 256 means the last entry waits about 380 ms, already far beyond the
15 ms Tier-2 budget"* — and the arithmetic is right (`256/4 x 6 = 384 ms`). But
`application.yml` ships **128**, which the same formula puts at 192 ms, still
12.8x over the stated budget. Worse: workers are
`min(maxConcurrency, availableProcessors)`, so on the 1000m-2000m pods the
capacity plan actually specifies, 128 entries over 2 workers is **384 ms — the
exact figure the comment rejects.** By its own rule the queue should be ~10.

**3. `maxInFlight` is documented as something it is not.** The javadoc says
*"concurrent upstream calls before load is shed"*, but the gate wraps
`serve(request)` — the whole pipeline, Tier 1 and Tier 2 included. At the
measured 0.696 hit ratio, **roughly 70% of the slots are held by requests that
never reach a provider.** The limit means a different thing at every hit ratio.

**4. Streaming is exempt from admission control entirely.** `execute()` passes
through `withAdmissionControl`; `stream()` does not. Streaming requests are by
design the **longest-lived** ones — the time limiter is deliberately omitted
because their duration is unbounded — so the requests that occupy resources
longest are precisely the ones the in-flight cap cannot see or shed. They are
bounded only by the provider bulkhead.

**Why it was invisible.** Every one of these limits *works*: they bound,
they refuse, they emit the right error, and the tests covering them pass. A
limit that is too high is indistinguishable from a correct one until the day it
is reached — and on a laptop it never is. The one number with a derivation
looked the most trustworthy and was the one whose derivation did not match the
shipped value.

**How it was found.** By being asked where the numbers came from while writing
up load shedding — then computing Little's Law against `scaling-model.md`
instead of accepting the config at face value.

**The fix.** **A21 and A23 applied the same day.** `max-in-flight` is now **64** -
the highest concurrency the campaign proved healthy - and the line finally
carries its derivation, including the warning that it is a 1000m figure to be
re-derived per pod shape. Not 89: 90 is where collapse was *observed*, 64 is
what was *proven*, and the untested gap between them is not somewhere to put a
safety limit. The `maxInFlight` javadoc now says what the gate actually counts -
every admitted request, cache hits included - and points at the bulkhead as the
separate bound on the vendor's share.

**A22, A24 and A25 followed the same day.** The embedding queue is now derived
from the worker count rather than hardcoded for a 4-CPU pod; `stream()` goes
through the same admission gate as `execute()`, sharing one counter, so the
longest-lived requests can finally be shed; and the bulkhead is derived as half
of `maxInFlight` after it turned out to be unreachable - see the entry above.

### Proving the new tests were worth having

Admission control had **no test at all** before this - which is how the streaming
path came to bypass it: nothing existed to notice. Three were added, and then
checked the only way a test can be checked: **by reverting the fix and confirming
they fail.**

Reverting A24 made exactly one of them fail, which is what a targeted test should
do. It also exposed a trap worth keeping: the first version used an unbounded
`verifyErrorSatisfies`, so against the old code the second stream was admitted,
hung on a `never()` provider, and **the build hung for seven minutes instead of
failing.** A test that hangs on regression is nearly as bad as one that passes.
Bounded `.verify(Duration.ofSeconds(5))` turns the same regression into a
5-second failure with a clear message.

All 408 tests pass. What remains is measurement, not code: the derived depths
rest on an assumed ~6 ms inference cost, which the `gateway.embedding.duration`
meter added in A13 can now replace with a real number.
The honest statement meanwhile: **the mechanisms are right, the thresholds are
placeholders** — which is the better way round, since a tuned threshold on a
broken mechanism cannot be fixed by tuning.

**The general shape.** A number with no comment beside it has usually never been
checked. Same as A16 (Redis entry size assumed, never measured) and kappa in the
scaling model.

---

## 2026-11-16 — A javadoc comment was silently orphaned from its method

**What was wrong.** When `embedTimed()` was inserted into `CompletionPipeline`
(the A13 fix), it landed **between** the "Bounded admission control" javadoc and
the `withAdmissionControl()` method that comment described. The file then held
two javadoc blocks back to back:

```java
/**
 * Bounded admission control.
 * ... doFinally rather than doOnTerminate ...
 */
/**
 * {@code embeddings.embed}, timed.
 */
private Mono<Embedding> embedTimed(String promptText) {
```

Javadoc attaches a block to the declaration that follows it, so the second block
won and the first became a floating comment attached to nothing. The explanation
of **why the permit must be released on cancellation** — the reasoning that stops
someone "simplifying" `doFinally` into `doOnTerminate` and leaking a permit per
dropped connection — now sat above the wrong method, describing code two methods
away.

**Why it was invisible.** Nothing fails. It compiles, every test passes, PMD and
Checkstyle do not flag consecutive javadoc blocks, and `mvn javadoc:javadoc`
emits no warning because both blocks are well-formed. The only symptom is a
reader being told the wrong thing — and only if they read carefully enough to
notice the comment does not match the method under it.

**How it was found.** By reading the file to explain load shedding for
[`learning/B6b1`](learning/B6b1-patterns-where-and-why.md). The quote needed for
the write-up was fetched by line number, and the surrounding lines showed two
`/**` blocks in a row.

**The fix.** Moved the block back above `withAdmissionControl()`.

**The general shape.** Inserting a method immediately before an existing one
silently re-parents the comment above it. Worth a glance at the lines *before*
an insertion point, not just the insertion itself — and the comments most at risk
are exactly the load-bearing ones, since those are the long blocks that sit
above the methods people are tempted to simplify.

---

## Earlier — the load-testing campaign

Nine sweeps were run; **eight were rejected** by the project's own tooling. Each
rejection had a cause worth keeping. Full detail in
[`load-testing.md`](runbooks/load-testing.md); the short list:

| problem | why it was invisible |
|---|---|
| The sweep could only report success — it offered a fixed rate and asked "did it keep up?" | once one pod sustains the rate every step reports the same number, and the model concludes perfect linear scaling from a test that never loaded anything |
| Every rerun looked better than the last | "unique" prompts were a function of worker id and a counter, both of which reset, so a second run found them all cached |
| The cache switched itself off under load | a 500 ms Redis timeout against a deep queue; each timeout became an upstream call, deepening the queue. Throughput collapsed instead of plateauing, and the only symptom was the hit ratio falling |
| The default API key was never configured | every request 401'd in under a millisecond, and the "excellent throughput" was the speed of saying no |
| The hardware wall was a config file I had written | `.wslconfig` capped the VM at 3 CPUs; six runs blamed the laptop |
| More cores made pods *slower* | a 400m limit on a 6-CPU node: the JVM saw 6 processors, made 6 event loops, and they shared an unchanged 40 ms quota — throttled in 96.9% of periods |
| `kubectl top` reported 51–76% for pods that were throttled in 46–72% of periods | it reports a decaying average on the metrics-server scrape cycle, so read after a run it samples the idle period afterwards |
| A run reported 65% twice, byte for byte | the config had never been patched — CRLF line endings against patterns ending in `\n` |
| 16 careful tests killed one mutant | the tests passed values *near* the boundary, which `>` and `>=` agree on. Seven known-answer tests pinning exact output killed nineteen |
| Tracing never worked | the endpoint pointed at Tempo's gRPC port while Spring Boot drives the HTTP exporter. Dependency present, sampling set, endpoint populated, zero spans |

---

## The lesson the log keeps repeating

Count the entries where something **crashed**: one — the Alpine/glibc
`UnsatisfiedLinkError`, and even that only appeared once someone enabled the
feature.

Everything else returned a plausible wrong answer, silently skipped work, or
reported success. Several were **found by being asked to explain the code**,
which turns out to be an effective review technique: you cannot explain why a
line is correct without checking whether it is.
