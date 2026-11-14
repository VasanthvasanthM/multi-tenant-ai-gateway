# LinkedIn post — drafts

**Option A is the recommended one.** It leads with the capacity claim you asked
for, then earns it. Options B and C are alternatives for a different audience.

None of them says "proven to handle billions of requests", and the reason is at
the bottom of this file. The short version: what exists is one pod measured and
arithmetic to a fleet, which is a capacity *plan*. Engineers who have run
capacity tests can tell the difference in one reading, and they are the audience
worth attracting.

---

## Option A — recommended

> **Built an AI gateway designed for billions of requests a day — and the most
> useful thing I did was measure where that claim stops being true.**
>
> It is a multi-tenant caching layer that sits in front of any
> OpenAI-compatible provider. When a prompt repeats, it answers from cache
> instead of paying the model again.
>
> • **Tier 1** — exact SHA-256 match in Redis. Measured at **2-5 ms** against a
> 40 ms upstream call.
> • **Tier 2** — semantic match in PostgreSQL + pgvector, so "how do I cancel my
> subscription?" also answers "steps to cancel my subscription".
>
> **The capacity arithmetic**
>
> One pod, measured: **≥271 requests/second** at a **69.6% cache hit rate**,
> p50 198 ms.
>
> Two billion requests a day with a 4× daily peak is 92,593 rps at peak. That
> comes to **488 gateway pods, 9 Redis nodes, 8 PostgreSQL shards (24 instances
> with replicas) and 9 Kafka brokers** — and about **1.39 billion upstream model
> calls a day that never happen.**
>
> Every one of those numbers is in the repo with its assumptions labelled
> (M)easured or (C)alculated, because the gap between the two is where capacity
> plans go wrong.
>
> **What I could not prove**
>
> The Universal Scalability Law has a coefficient — kappa — for the cost that
> instances impose on each other. Near zero, the fleet scales linearly and the
> arithmetic above holds. I could not measure it: at three pods, my laptop's
> load generator saturated before the gateway did. So the repo says "capacity
> plan conditional on linear scaling", with the one unmeasured coefficient
> named. If kappa were 0.01, that table is wrong by two orders of magnitude.
>
> **The finding I did not expect**
>
> Semantic caching has a failure mode I had not seen written down.
>
> My load corpus generated "unique" prompts like *"Explain requirement 7 of the
> onboarding process"* and *"...requirement 8..."*. Different questions. The
> embedding model scored them **0.9987 similar** — higher than any genuine
> paraphrase in the same corpus, which ranged 0.51 to 0.91.
>
> So the cache would answer "requirement 8" with the response for
> "requirement 7". Confidently. And **raising the similarity threshold makes it
> worse**, because above 0.99 these near-duplicates are the *only* thing that
> still matches, while real rewordings get rejected.
>
> The intuition I started with — "a higher threshold is a safer cache" — is
> backwards for any prompt built from a template with an ID, order number, date
> or customer name substituted in. Which is a large share of real API traffic.
> Cosine distance on a sentence embedding cannot see a short identifier buried
> in long boilerplate, and that identifier is the entire question.
>
> The fix is not a number. It is comparing the lexical diff before trusting the
> vector: if two prompts differ only in a numeral or an unknown token, that
> difference *is* the question — refuse the match. Not built yet; written up
> with the measurements.
>
> This is why the cache ships with shadow mode on by default: query Tier 2, log
> what it would have served, serve nothing, and read the false-hit rate before
> trusting it in production.
>
> **The stack**
>
> Java 21, Spring WebFlux, Kubernetes + Helm, Redis, PostgreSQL + pgvector,
> Kafka, Prometheus, Grafana, Loki, Tempo, Alertmanager, Terraform, GitHub
> Actions. React 19 + TypeScript dashboard. Consistent-hash sharding on tenant,
> transactional outbox, saga orchestration.
>
> **What I am taking away**
>
> Nine load tests. My own tooling rejected eight of them, and every rejection
> taught me something — the cache that switched itself off under load, the test
> corpus that regenerated identical "unique" prompts so every rerun looked
> better than the last, the hardware wall that turned out to be a config file I
> had written myself.
>
> Building it was the easy half.
>
> #Java #Kubernetes #SpringBoot #SystemDesign #Observability #AI #DistributedSystems

---

## Option B — the bug-hunting angle

> Nine load tests. Eight rejected by my own tooling. Here is what each one
> taught me about measuring a distributed system.
>
> I built a multi-tenant AI gateway — two-tier semantic cache, Java 21, Spring
> WebFlux, Kubernetes, designed for billions of requests a day. Building it took
> time. Measuring it honestly took longer.
>
> **1. My sweep could only ever report success.** It offered a fixed request rate
> and asked "did it keep up?". That cannot measure capacity: once one pod
> sustains the rate, every step reports the same number and the model concludes
> perfect linear scaling — from a test that never loaded anything.
>
> **2. Every rerun looked better than the last.** The "unique" prompts in my
> corpus were a function of the worker ID and a counter, both of which reset. Run
> it twice against the same Redis and they are all cache hits. A 30% miss rate
> became a 30% hit rate.
>
> **3. The cache disabled itself under load.** 500 ms Redis timeout, 1.5 s queue.
> Lookups timed out, each timeout became an upstream call, that deepened the
> queue. Throughput collapsed rather than plateaued. The only visible symptom was
> the hit ratio quietly falling.
>
> **4. 16 careful tests killed one mutant.** Mutation testing flips `>` to `>=`;
> my tests passed values near the boundary, which both versions agree on. Seven
> known-answer tests pinning exact output killed nineteen.
>
> **5. Tracing never worked.** The endpoint pointed at Tempo's gRPC port while
> Spring Boot drives the HTTP exporter. Dependency present, sampling set,
> endpoint populated — and zero traces, for months.
>
> **6. The hardware wall was a config file I wrote.** I capped the VM at 3 CPUs
> during a memory squeeze, then spent six runs calling it a laptop limitation.
>
> **7. My production image could never run the headline feature.** The container
> was Alpine. ONNX Runtime needs glibc. Semantic caching — the thing the product
> is named for — would have failed on the first request in any deployed
> container. It hid because CI builds images with buildpacks (Ubuntu, glibc,
> fine), the model path is unset by default so the gateway starts on a lexical
> fallback, and the feature is off by default. Three layers of "works fine", one
> broken path.
>
> **8. A higher similarity threshold made the cache less safe.** Two prompts
> about *different* requirements scored 0.9987 — above every genuine paraphrase
> I tested. Raise the threshold and these are the only matches left.
>
> Final answer: one pod serves ≥271 req/s at a 69.6% hit rate. The arithmetic to
> 2 billion/day is in the repo, with the one coefficient I could not measure
> named rather than hidden.
>
> #SoftwareEngineering #Testing #Kubernetes #Java #Observability

---

## Option C — short

> Built a multi-tenant AI gateway with a two-tier semantic cache. Repeated
> prompts answer from Redis or pgvector instead of hitting the model again.
>
> **Measured:** ≥271 req/s per pod, 69.6% hit rate, Tier-1 hits at 2-5 ms
> against 40 ms upstream.
> **Calculated:** 488 pods for 2 billion requests/day — 1.39 billion upstream
> calls a day avoided.
> **Not proven:** that the fleet scales linearly. The coefficient that would
> settle it needs more hardware than a laptop, and the repo says so.
>
> The thing I did not expect: two prompts asking about *different* requirements
> scored 0.9987 similar — higher than any real paraphrase I tested. A semantic
> cache will answer one with the other, and raising the threshold makes it worse.
> Any prompt with an ID or a date in it has this problem.
>
> Java 21 · Spring WebFlux · Kubernetes · Redis · pgvector · Kafka · Prometheus ·
> Grafana · Loki · Tempo · React
>
> The hardest part was not building it. It was refusing to publish a number I had
> not earned.
>
> #Java #Kubernetes #SystemDesign #AI

---

## Why none of these claims billions were proven

Because it would not survive the first senior engineer who reads it.

What exists is **one pod measured** and **arithmetic** to a fleet. That is a
capacity plan, not a demonstrated capacity — and the gap is a specific, nameable
coefficient, not vagueness.

Claiming the stronger version invites one question — *"how did you test
billions?"* — with no good answer. Leading with the design target and then
naming the limit invites a better conversation: the USL, which coefficient
matters, and why a single-host test bed cannot produce it.

There is also a second caveat worth keeping straight, because it is easy to
blur: **the 271 rps was measured with Tier 2 switched off**, which is the
shipped default. With semantic caching actually serving, embedding inference
dominates and per-pod throughput falls by one to two orders of magnitude. The
fix is architectural — move inference off the request path onto the indexer that
already exists — not more gateway pods. If anyone asks "does the 488-pod number
include semantic caching?", the answer is no, and that is written down in
`scaling-model.md`.

**Before posting**
- Add the repo link.
- Attach the Grafana screenshots or the demo video.
- Check you are comfortable with the repo being public — it contains a dev-only
  Postgres password in `deploy/k8s/dev-infra.yaml` (clearly marked, local only).
- Option A is long for LinkedIn. If you want it shorter, cut "The stack" and
  "What I am taking away" — the capacity arithmetic and the 0.9987 finding are
  what will get engineers commenting.
