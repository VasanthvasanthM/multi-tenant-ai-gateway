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
