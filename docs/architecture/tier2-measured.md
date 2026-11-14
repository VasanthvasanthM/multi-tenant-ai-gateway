# Tier 2, measured

Tier 2 — the semantic cache — is the feature the product is named for. Until
2026-11-14 it had never served a single request in a deployed container, and
could not have. This file is what running it for the first time revealed.

Everything below is measured on one k3d node (6 vCPU VM, 4 GB) with
`all-MiniLM-L6-v2` loaded from `models/`, unless marked **(C)** for calculated.

---

## 1. The container could never run it

The local image built from `deploy/docker/Dockerfile.jvm` was based on
`eclipse-temurin:21-jre-alpine`. Alpine is musl; ONNX Runtime's bundled
`libonnxruntime.so` is linked against glibc. Enabling Tier 2 with a real model
produced:

```
UnsatisfiedLinkError: /tmp/onnxruntime-java.../libonnxruntime.so:
  Error loading shared library libstdc++.so.6: No such file or directory
```

`apk add libstdc++` is not a fix. With it installed, `ldd` still reports:

```
Error loading shared library ld-linux-x86-64.so.2: No such file or directory
Error relocating libonnxruntime.so: __vsnprintf_chk: symbol not found
Error relocating libonnxruntime.so: __fprintf_chk:  symbol not found
Error relocating libonnxruntime.so: __memcpy_chk:   symbol not found
```

Those `_chk` symbols are glibc's fortified string functions, and the loader name
is glibc's. No Alpine package supplies them. The base image is now
`eclipse-temurin:21-jre-noble`, and the model loads:

```
loaded ONNX embedding model 'all-MiniLM-L6-v2' (dimension=384, predictors=2)
embedding service ready: model=all-MiniLM-L6-v2 dimension=384 threads=2
```

**Why it hid for so long.** Three layers each masked it:

1. CI builds images with Spring Boot's buildpack integration, and paketo builds
   on Ubuntu — so CI images were always glibc and always fine. The broken path
   was the one used only for local and k3d runs.
2. `gateway.embedding.model-path` is unset by default, so the gateway logs
   `EMBEDDING FALLBACK ACTIVE` and starts happily on the lexical model. The
   native library is never touched.
3. `tier2-enabled: false` by default. Nothing asked for an embedding at all.

A test asserting "the image can construct an EmbeddingService from a real model
directory" would have caught it on day one. There was not one, because the unit
tests run on the host JVM — which is glibc.

---

## 2. What the model actually scores

Measured by sending prompts through the gateway and reading the stored vectors
back, so these are the same embeddings the cache compares:

```sql
SELECT round((-(a.embedding <#> b.embedding))::numeric, 4)
  FROM prompt_cache a JOIN prompt_cache b ON a.id < b.id;
```

### The corpus paraphrases

`loadtest/k6/lib/corpus.js` says its rewordings are "close enough in meaning to
clear a 0.95 cosine threshold". **Six of twelve do. Six do not.**

| base | paraphrase | similarity | clears 0.95 |
|---|---|---|---|
| What payment methods do you accept? | which payment methods work? | 0.9101 | no |
| Can I transfer my account to someone else? | transferring an account to another person | 0.8922 | no |
| How do I change my email address? | changing my email - how | 0.8645 | no |
| How do I reset my password? | password reset steps? | 0.8308 | no |
| How do I enable two-factor authentication? | turning on 2FA - how do I do it | 0.7395 | no |
| What are your business hours? | when are you open? | 0.5093 | no |

The other six (cancel subscription, invoice, team member, export data, card
declined, refund policy) cleared the threshold and were served from Tier 2 —
which is how they are identified: a served paraphrase is never written back, so
it has no row.

Two consequences:

- **The declared mix is not the delivered mix.** The corpus header documents
  `PARAPHRASE -> Tier 2`. In practice that class splits roughly half to Tier 2
  and half to upstream. Any hit ratio derived from the declared mix is wrong by
  about half the paraphrase share.
- **"when are you open?" at 0.5093** is the interesting one. It is an
  unmistakable paraphrase to a human, and it scores below some *unrelated* pairs
  elsewhere in the corpus. MiniLM is matching surface form more than this
  project's documentation assumes, and no threshold fixes that case.

### The dangerous pair, measured

`GatewayProperties.Cache` refuses to boot below 0.90, with this reasoning:

> At 0.80, "how do I cancel my order" and "how do I cancel my subscription" are
> close enough to match, and the cache starts returning wrong answers
> confidently.

**Measured, that pair scores 0.7561** — below the 0.80 the comment warns about.
Against the genuine paraphrases above, the ordering is:

```
0.9101  payment methods        <- genuine paraphrase
0.8922  transfer account       <- genuine paraphrase
0.8645  change email           <- genuine paraphrase
0.8308  reset password         <- genuine paraphrase
---- 0.80 ----------------------------------------
0.7561  order vs subscription  <- FALSE pair the guard is about
0.7395  2FA                    <- genuine paraphrase
--------------------------------------------------
0.5093  business hours         <- genuine paraphrase
```

So a threshold near **0.78–0.80** would have excluded the false pair and
admitted four of the six paraphrases that 0.95 rejects. The guard's instinct is
right — a false hit serves a confidently wrong answer, which is worse than a
miss — but the specific numbers in its comment are not supported by this
measurement, and the hard floor at 0.90 forbids the entire window where the
separation actually sits.

The margin is thin: 0.7561 against 0.7988 is 0.04. That is the real finding —
not "the threshold is too high" but **the false pair and the true paraphrases
are 0.04 apart, so no single global threshold cleanly separates them.** Tuning
per tenant, or on a false-hit rate measured in shadow mode, is the only honest
path. Refusing to boot below 0.90 at least fails loudly rather than silently
serving wrong answers.

### The false hit that no threshold prevents (M)

This is the most important measurement in this file.

The load corpus generates its "never seen before" prompts as:

```js
`Explain requirement ${RUN_ID}-${vu}-${uniqueCounter} of the onboarding process.`
```

Every one is the same sentence with a different identifier. Measured against
each other:

| a | b | similarity |
|---|---|---|
| Explain requirement demo-...-8-1 of the onboarding process | Explain requirement demo-...-7-1 of the onboarding process | **0.9987** |
| Explain requirement demo-...-3-1 ... | Explain requirement demo-...-1-1 ... | 0.9983 |
| Explain requirement demo-...-6-1 ... | Explain requirement demo-...-7-1 ... | 0.9982 |

**Requirement 7 and requirement 8 are different questions, and Tier 2 answers
one with the other's cached response.** At the shipped 0.95 threshold this is a
hit. At 0.99 it is still a hit. There is no threshold below 0.9987 that admits
any real paraphrase and rejects this, and the genuine paraphrases in the same
corpus sit between 0.51 and 0.91 — *below* the false pair. The ordering is
inverted:

```
0.9987  requirement 7 vs 8     <- WRONG ANSWER, scores highest
0.9101  payment methods        <- genuine paraphrase
0.8308  reset password         <- genuine paraphrase
0.7561  order vs subscription  <- the false pair the guard warns about
0.5093  business hours         <- genuine paraphrase
```

So the guard in `GatewayProperties.Cache` protects against the wrong thing. It
assumes false hits come from *semantically adjacent* prompts, which sit a little
below real paraphrases and can be excluded by a floor. The dangerous case is the
opposite: **text that is lexically almost identical and differs only in the token
that carries the entire meaning.** Cosine similarity on a sentence embedding
cannot see a short identifier inside long boilerplate, and a higher threshold
makes it strictly worse by admitting only these.

This is not a corpus artefact. It is the shape of a large fraction of real API
traffic:

- "Summarise order 88213" vs "Summarise order 88214"
- "What changed in release 4.2.1?" vs "... 4.2.2?"
- "Translate the attached clause for tenant Acme" vs "... for tenant Globex"
- any prompt built from a template with an ID, date, amount or name substituted

For all of these, a semantic cache with a global cosine threshold will serve
confidently wrong answers, and the only visible symptom is a *better* hit ratio.

**What would actually fix it**, none of which is built:

1. **Refuse to match when the differing tokens are high-information.** Compare
   the lexical diff, not only the vectors: if the prompts differ only in a
   numeral or an identifier, that difference is the question. A cheap guard —
   reject when the symmetric token difference contains a digit or an unknown
   token — would have caught every row above.
2. **Scope the cache per template.** Hash the prompt with identifiers masked and
   require that hash to match before comparing vectors.
3. **Opt in per tenant or per route**, defaulting off, because whether a
   paraphrase is safely interchangeable is a property of the application and not
   of the embedding.

Until one of them exists, Tier 2 is safe only for traffic known to be free of
in-prompt identifiers, and `shadow-mode: true` is the correct default. The
measured false-hit rate it exists to produce should be the gate on enabling it.

> It also invalidates the load corpus whenever Tier 2 is on: the UNIQUE class is
> designed as a 30% upstream share, and in practice almost all of it is served
> as a Tier-2 false hit. In one live window Tier 2 served **2,125 hits against
> 2,137 Tier-1 misses** — a 99.8% overall hit ratio against a designed 70%. Any
> throughput measured in that state is measuring the cache, not the mix.

### Discrimination is still strong where it matters

| pair | similarity |
|---|---|
| password question vs "Describe the migration patterns of arctic terns" | 0.0308 |
| cancel-subscription vs arctic terns | -0.0212 |

Unrelated content scores near zero or negative. The model is not broken; the
threshold sits in the one region where it is ambiguous.

---

## 3. Shadow mode is on by default

`shadow-mode: true` ships as the default: Tier 2 is queried, what it *would*
have served is logged and counted as `result="shadow"`, and nothing is served.
That is the correct way to roll out a semantic cache — but it means a fresh
deployment shows **zero Tier-2 hits no matter how well it is working**, and the
only signal that anything happened is a counter nobody looks at.

With `GATEWAY_CACHE_SHADOWMODE=false`, Redis flushed so Tier 1 cannot answer,
and only vectors in `prompt_cache`:

```
punctuation-only variant  -> tier2_semantic  hit=true  1991 ms
capitalisation variant    -> tier2_semantic  hit=true   101 ms
the stored prompt itself  -> tier2_semantic  hit=true   105 ms
```

That is Tier 2 serving, verified: a flushed Redis cannot produce a Tier-1 hit.

---

## 4. The cost, and what it does to the capacity model

This is the part that matters for [`scaling-model.md`](scaling-model.md).

### Tier 2 is CPU-bound and expensive

| configuration | p50 | per-pod throughput |
|---|---|---|
| Tier 1 only (scaling-model §4a) | 198 ms | **>=271 rps** |
| Tier 2 enabled and serving | 103 ms | **~4-8 rps** at 80% of 2 cores |

The first cached response after a restart was **1991 ms** — cold ONNX session
plus JIT. It settles to ~100 ms.

Idle CPU was measured at **5.3% of one core**, so this is not ONNX Runtime
spin-waiting; it is genuine inference work. A 384-dimension MiniLM forward pass
costs real CPU, every Tier-1 miss pays it, and a 1-vCPU pod can do very few per
second.

**The 271 rps/pod figure is a Tier-1-only measurement.** Every fleet number in
`scaling-model.md` §4b — 488 gateway pods for 2 billion requests/day — inherits
that assumption. It is valid for the configuration it was measured in, and that
configuration has Tier 2 switched off. With Tier 2 serving, embedding dominates
and the per-pod figure is one to two orders of magnitude lower.

The architectural answer is not more gateway pods. It is to stop doing inference
on the request path:

- embed asynchronously on the write path only (the indexer already exists for
  exactly this, and Kafka is its transport)
- or run embedding as its own horizontally-scaled service, sized on inference
  cost rather than on request rate
- or batch inference, which MiniLM benefits from substantially

None of that is built. It is named here so the capacity table is not read as
covering a configuration it never measured.

### Tier 2 starves Tier 1 when CPU is tight

At a 1000m limit with Tier 2 serving, the logs produced **68 occurrences** in
200 lines of:

```
rate limiter unavailable for tenant acme, failing open
rate limiting degraded for tenant acme: limiter unavailable
```

Redis was healthy throughout (`PING` -> `PONG`, DNS resolving to the right
ClusterIP). These were **timeouts, not connectivity**: `spring.data.redis.timeout`
is 500 ms, embedding saturated the CPU the reactive event loop needed, and Redis
commands aged out. `fail-open: true` then did what it promises — kept serving,
with the rate limiter switched off.

So under CPU pressure the failure is not "Tier 2 gets slow". It is **the rate
limiter silently stops limiting**, which is a multi-tenant isolation failure
reached through a cache feature. Raising the limit to 2000m took those warnings
to **zero**.

This is the same cascade shape as the Tier-1 timeout collapse in
`scaling-model.md` §3b, from a different cause: there, queue depth exceeded the
Redis timeout; here, CPU starvation did. Both end with a component disabling
itself and the only symptom being a counter.

---

## 5. What is still unmeasured

- **Tier-2 throughput proper.** The 4-8 rps above is an observation at 4 VUs,
  not a saturation sweep. No concurrency ladder was run, so it is not an X(N).
- **The false-hit rate.** The whole point of shadow mode, and the only thing
  that would justify moving the threshold. Needs real traffic, not a 12-prompt
  corpus.
- **Whether batching changes the economics.** MiniLM is much faster per item in
  batches; the current path embeds one prompt at a time.
- **Where a request actually spends its time.** There is no custom span
  instrumentation anywhere in the codebase - no `@Observed`, no `Observation`,
  no `tracer.nextSpan` - so every trace is a single HTTP server span. The CPU
  cost attributed to embedding in section 4 was inferred from cgroup counters
  and p50 shifts rather than measured directly. A child span around the
  inference call, the Tier-1 lookup and the Tier-2 search would have turned that
  inference into a measurement, and is the cheapest next step for making any of
  this falsifiable.

---

## 6. Reproducing this

```bash
./scripts/fetch-model.sh                      # ~90 MB, not in git

docker build -f deploy/docker/Dockerfile.jvm --build-arg MODULE=gateway \
  -t ai-gateway-local/ai-gateway-gateway:0.2.0-glibc .
k3d image import -c ai-gateway ai-gateway-local/ai-gateway-gateway:0.2.0-glibc

# the model has to reach the node; hostPath is the shortcut, a PVC is the answer
for n in k3d-ai-gateway-server-0 k3d-ai-gateway-agent-0; do
  docker exec "$n" mkdir -p /opt/aigw-models
  docker cp models/all-MiniLM-L6-v2 "$n:/opt/aigw-models/"
done
```

Then mount `/opt/aigw-models` and set:

```
GATEWAY_EMBEDDING_MODELPATH=/opt/aigw-models/all-MiniLM-L6-v2
GATEWAY_EMBEDDING_CONCURRENCY=2
GATEWAY_CACHE_TIER2ENABLED=true
GATEWAY_CACHE_SHADOWMODE=false     # demo only; measure the false-hit rate first
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=45
```

Two of those need saying out loud:

- **`MaxRAMPercentage=45`, down from the image default of 70.** ONNX sessions are
  native allocations outside the heap. At 70% of a 1 GB limit the heap takes
  717 MB and the inference arenas have nowhere to live, so the container is OOM
  killed while the heap looks healthy.
- **`k3d image import` of a 523 MB image wedged the cluster twice** on this host:
  `PLEG is not healthy: pleg was last seen active 6m38s ago`, then
  `TLS handshake timeout` on every API call. Recovery is Docker Desktop restart
  plus `wsl --shutdown`. Import once, before the monitoring stack is running, or
  on a host with more than 400 MB free.

**The Helm chart cannot express any of this.** There is no volume, model-path or
embedding block in `values.yaml`, so Tier 2 with a real model is reachable only
by patching the Deployment directly. That is the next piece of work if Tier 2 is
meant to be deployable rather than demonstrable.
