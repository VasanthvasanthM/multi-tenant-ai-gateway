# LinkedIn post — drafts

**Option A is the one to post.** It opens on the billions-per-day capacity claim
and then earns it, which is what stops the claim reading as noise.

Options B and C are alternatives for different audiences. The skills and tools
inventory at the bottom is verified against the repo — nothing in it is
aspirational.

**Last updated 2026-11-17.** Verified at that date: 9 Maven modules, 14 ADRs,
11 CI jobs, 17 problem-log entries, 26 walkthrough documents. The fleet figures
match [`B10`](learning/B10-capacity-estimation.md), which recalculated them with
Tier 2 included; the provenance of every number is in
[`B10a`](learning/B10a-where-every-number-came-from.md).

---

## Option A — POST THIS ONE

~280 words. LinkedIn truncates after about 210 characters, so the first two
lines have to carry the whole post; everything after them is for the people who
tapped "see more". One finding, told properly, beats four told briefly.

> **I built an AI gateway meant to serve billions of requests a day.**
>
> **Then I measured it, and the most interesting thing I found was that one of
> my own assumptions was backwards.**
>
> The idea is simple: most LLM traffic is the same handful of questions asked
> over and over. So cache the answers — exact matches in Redis, and *similar*
> questions matched by meaning in pgvector.
>
> That second tier is where it got interesting.
>
> My load test generated prompts like **"Explain requirement 7"** and
> **"…requirement 8"**. Different questions. The embedding model scored them
> **0.9987 similar** — higher than any genuine rewording I tested, which topped
> out at 0.91.
>
> So the cache would answer "requirement 8" with the response for
> "requirement 7". Confidently.
>
> Here is the part that inverts everything: **raising the similarity threshold
> makes it worse.** Above 0.99, these near-duplicates are the *only* thing that
> still matches, while real paraphrases get rejected.
>
> "A higher threshold is a safer cache" is false for any prompt built from a
> template with an ID, order number or date dropped into it — which is a huge
> share of real API traffic. The identifier *is* the question, and cosine
> distance cannot see it.
>
> One measured pod → **271 rps**, 69.6% hit rate. Scaled honestly, two billion
> requests a day is **87 nodes** — and ~1.39 billion model calls that never
> happen.
>
> Java 21 · WebFlux · Redis · PostgreSQL + pgvector · Kafka · Kubernetes ·
> ONNX
>
> Nine load tests. My own tooling rejected eight of them. **Building it was the
> easy half.**
>
> Repo in the comments 👇
>
> #Java #SystemDesign #DistributedSystems #Kubernetes #AI

---

## Option A-long — the full version

Keep this for a blog post, a README, or interview preparation. Too long for a
feed, and every section in it is a real finding.

### Option A-long, in full

> **I built an AI gateway designed to serve billions of requests a day.**
>
> **Then I spent nine load tests proving how much of that I could actually
> back up — and the honest answer turned out to be the most interesting part.**
>
> ---
>
> **The problem**
>
> Every call to an LLM costs money, and a huge share of production traffic is
> the same handful of questions asked over and over. A support bot answering
> "how do I reset my password?" five thousand times a day pays five thousand
> times for one answer.
>
> **What I built**
>
> A multi-tenant gateway that sits in front of any OpenAI-compatible provider
> and answers repeated prompts from cache instead of paying the model again.
>
> → **Tier 1** — exact SHA-256 match in Redis. Measured at **2–5 ms**, against
> a 40 ms upstream call.
> → **Tier 2** — semantic match in PostgreSQL + pgvector, so "how do I cancel
> my subscription?" is answered by "steps to cancel my subscription".
>
> Multi-tenant throughout: every cache key, rate-limit bucket and database
> shard is scoped by tenant, so one customer's traffic can never see or starve
> another's.
>
> ---
>
> **The capacity arithmetic**
>
> One pod, measured on a laptop: **≥271 requests/second** at a **69.6% cache
> hit rate**, p50 198 ms.
>
> Two billion requests a day with a 4× daily peak is **92,593 rps at peak**.
> On named instances that is **87 nodes, ~1,314 vCPU**:
>
> • **488 gateway pods** on 39 × m7i.4xlarge
> • **242 vCPU of embedding** on 19 × c7i.4xlarge — *its own fleet, and the
>   reason why is below*
> • **8 Redis nodes** (4 primaries + 4 replicas, r7g.4xlarge)
> • **4 PostgreSQL shards** → 12 instances with replicas (r7g.8xlarge)
> • **9 Kafka brokers**
>
> …and roughly **1.39 billion upstream model calls a day that never happen.**
>
> Every number in the repo is labelled **MEASURED**, **DERIVED** or
> **ASSUMED**. There is exactly one measured input — 271.3 rps — and nine
> assumptions resting on it. Writing those labels down is the difference
> between a capacity plan and a guess with a table.
>
> **The measurement that forced an architecture change**
>
> The headline number was measured with semantic caching **switched off**. So I
> switched it on, gave the pod **twice** the CPU, and measured again:
>
> → Tier 2 off, 1 core: **271 rps**
> → Tier 2 on, 2 cores: **4–8 rps**
>
> Twice the hardware, roughly **forty times less throughput.** And pure CPU
> arithmetic says that should be impossible — embedding at peak needs ~169
> cores, not 23,000.
>
> The gap was not CPU consumption. It was a feedback loop: embedding saturates
> the core → the Redis client is starved → Tier-1 lookups exceed their 500 ms
> timeout → **every timeout becomes a full upstream call** → more load. And the
> worst consequence was not throughput: when CPU is that tight, the rate
> limiter fails open. **A tenant-isolation failure reached through a cache
> feature.**
>
> So embedding moved off the request path into its own fleet. That one decision
> is the difference between **730 vCPU and 23,000**.
>
> **What I still cannot prove**
>
> The Universal Scalability Law has a coefficient — kappa — for the cost
> instances impose on each other. Near zero, the fleet scales linearly and the
> arithmetic above holds. **I could not measure it.** At three pods my laptop's
> load generator saturated before the gateway did. The repo says so, and names
> the coefficient it could not pin down.
>
> ---
>
> **The finding I did not expect**
>
> Semantic caching has a failure mode I have not seen written down.
>
> My load corpus generated prompts like *"Explain requirement 7 of the
> onboarding process"* and *"…requirement 8…"*. **Different questions.** The
> embedding model scored them **0.9987 similar** — higher than any genuine
> paraphrase I tested, which ranged 0.51 to 0.91.
>
> So the cache would answer "requirement 8" with the response for
> "requirement 7". Confidently. And here is the part that inverts the
> intuition: **raising the similarity threshold makes it worse.** Above 0.99,
> these near-duplicates are the *only* thing that still matches, while real
> rewordings get rejected.
>
> "A higher threshold is a safer cache" is backwards for any prompt built from
> a template with an ID, order number, date or customer name substituted in —
> which is a large share of real API traffic. Cosine distance on a sentence
> embedding cannot see a short identifier buried in long boilerplate, and that
> identifier is the entire question.
>
> The fix is not a number. It is comparing the lexical diff before trusting the
> vector: if two prompts differ only in a numeral or an unknown token, that
> difference *is* the question — refuse the match.
>
> This is why the cache ships with shadow mode on by default: query Tier 2, log
> what it would have served, serve nothing, and read the false-hit rate before
> trusting it in production.
>
> ---
>
> **The protection that could never engage**
>
> The gateway sheds load at a configured concurrency limit. It was set to
> **256**, written on day one. Eight days later I measured the pod: **271 rps
> at 198 ms**, which by Little's Law is ~54 requests in flight — and the load
> campaign recorded the gateway *collapsing* at 90.
>
> So the limit sat five times past the point the pod was already finished. **It
> could never fire.** Every test passed, because a limit that is too high is
> indistinguishable from a correct one until the day it is reached.
>
> Then fixing it broke something else, which is the part I would not have
> predicted: lowering the gateway limit to 64 made the per-provider bulkhead of
> 64 **unreachable**. A limit that cannot trigger still passes every test it
> has, and its metric reads zero forever — which is also exactly what a
> *working* bulkhead looks like.
>
> **When you tighten one limit, check every limit that was sized against the
> old one.** Two constants encoding one relationship is a bug waiting for
> whoever changes either.
>
> ---
>
> **Four components that worked perfectly and were never called**
>
> A saga orchestrator with nothing implementing its step interface. A replica
> lag monitor whose `start()` was invoked only by its own test. A dashboard
> login built on both the server and the client with no form between them. An
> outbox relay polling correctly every second with nothing ever writing a row.
>
> All four unit-tested and green. **The question a unit test never asks is
> "does anything in production invoke this?"**
>
> The lag monitor was the worst, because it registers its gauge in the
> constructor — deliberately, so that a missing metric series could not hide a
> dead probe. Nothing called `start()`. So the gauge existed, read a healthy
> **0.0 forever**, and the replica health check answered "never behind" for
> every shard — while an architecture decision record rested its entire safety
> argument on that monitoring existing. **The mitigation against silent failure
> was the thing that silenced it.**
>
> ---
>
> **A bug that hid behind three layers of "works fine"**
>
> My production image could never have run the headline feature.
>
> The container was Alpine. ONNX Runtime needs glibc. Semantic caching would
> have failed on the first request in any deployed container — and nothing ever
> said so, because CI builds images with buildpacks (Ubuntu, glibc, fine), the
> model path is unset by default so the gateway starts on a lexical fallback,
> and the feature is off by default. Three independent defaults, each masking
> it. Unit tests never caught it: they run on the host JVM, which is glibc.
>
> The lesson I keep relearning: **test the artefact you ship, not the one your
> tests happen to run in.**
>
> ---
>
> **The stack**
>
> **Backend** — Java 21, Spring Boot 3.5, Spring WebFlux, Project Reactor,
> Netty, 9 Maven modules
> **Data** — Redis 7 (Lua token bucket, hash-tagged keys), PostgreSQL 17 +
> pgvector 0.8 (HNSW, inner-product), Flyway
> **Messaging** — Apache Kafka — transactional outbox, orchestrated saga,
> idempotent consumers
> **ML** — ONNX Runtime + DJL, all-MiniLM-L6-v2 sentence embeddings, 384-dim
> **Platform** — Kubernetes, Helm, k3d/k3s, MetalLB, ingress-nginx, HPA on RPS,
> Terraform (EKS), Docker
> **Observability** — Prometheus, Grafana, Loki, Tempo, Alloy, Alertmanager →
> Slack, Micrometer, OpenTelemetry
> **Quality** — JUnit 5, Testcontainers (real Postgres, pgvector, Redis and
> Kafka brokers), JaCoCo (80% line / 70% branch gates), **PIT mutation
> testing**, ArchUnit architecture rules, SonarCloud, Trivy, Spotless, PMD
> **CI/CD** — GitHub Actions (11 jobs) and Jenkins
> **Frontend** — React 19, TypeScript, Vite, Recharts, Vitest
> **Design** — 14 ADRs, HLD + LLD, consistent-hash sharding (MurmurHash3, 150
> vnodes) with **online tenant migration — dual-write, verify by checksum, and
> a cutover that loses no writes**, Universal Scalability Law modelling, k6
> load testing
>
> ---
>
> **What I am taking away**
>
> Nine load tests. My own tooling rejected eight of them, and every rejection
> taught me something: the cache that switched itself off under load; the test
> corpus that regenerated identical "unique" prompts so every rerun looked
> better than the last; the hardware wall that turned out to be a config file I
> had written myself.
>
> Seventeen problems are written up in the repo with how each was found. **One
> of them actually crashed.** Everything else returned a plausible wrong
> answer, silently skipped work, or reported success — which is what failure
> looks like in a distributed system, and why the toolkit here is known-answer
> tests, mutation testing and architecture rules rather than smoke tests.
>
> And the review technique that found most of them was not a tool:
>
> **You cannot explain why a line is correct without checking whether it is.**
>
> Building the system was the easy half. Measuring it honestly was the work.
>
> Repo in the comments. 👇
>
> #Java #Kubernetes #SpringBoot #SystemDesign #DistributedSystems
> #Observability #AI #SoftwareEngineering #Microservices #DevOps

---

## Option B — the bug-hunting angle

> Nine load tests. Eight rejected by my own tooling. Here is what each one
> taught me about measuring a distributed system.
>
> I built a multi-tenant AI gateway — two-tier semantic cache, Java 21, Spring
> WebFlux, Kubernetes — designed for billions of requests a day. Building it
> took time. Measuring it honestly took longer.
>
> **1. My sweep could only ever report success.** It offered a fixed request
> rate and asked "did it keep up?". That cannot measure capacity: once one pod
> sustains the rate, every step reports the same number and the model concludes
> perfect linear scalability — from a test that never loaded anything.
>
> **2. Every rerun looked better than the last.** The "unique" prompts in my
> corpus were a function of the worker ID and a counter, both of which reset.
> Run it twice against the same Redis and they are all cache hits. A 30% miss
> rate became a 30% hit rate.
>
> **3. The cache disabled itself under load.** 500 ms Redis timeout, 1.5 s
> queue. Lookups timed out, each timeout became an upstream call, that deepened
> the queue. Throughput collapsed rather than plateaued, and the only visible
> symptom was the hit ratio quietly falling.
>
> **4. 16 careful tests killed one mutant.** Mutation testing flips `>` to
> `>=`; my tests passed values near the boundary, which both versions agree on.
> Seven known-answer tests pinning exact output killed nineteen.
>
> **5. Tracing never worked.** The endpoint pointed at Tempo's gRPC port while
> Spring Boot drives the HTTP exporter. Dependency present, sampling set,
> endpoint populated — and zero traces, for months.
>
> **6. The hardware wall was a config file I wrote.** I capped the VM at 3 CPUs
> during a memory squeeze, then spent six runs calling it a laptop limitation.
>
> **7. My production image could never run the headline feature.** Alpine is
> musl; ONNX Runtime needs glibc. The semantic cache would have failed on the
> first request in any deployed container — hidden behind three defaults that
> each looked like "works fine".
>
> **8. A higher similarity threshold made the cache less safe.** Two prompts
> about *different* requirements scored 0.9987 — above every genuine paraphrase
> I tested. Raise the threshold and those are the only matches left.
>
> Final answer: one pod serves ≥271 req/s at a 69.6% hit rate. The arithmetic
> to 2 billion/day is in the repo, with the one coefficient I could not measure
> named rather than hidden.
>
> #SoftwareEngineering #Testing #Kubernetes #Java #Observability

---

## Option C — short

> Built a multi-tenant AI gateway with a two-tier semantic cache. Repeated
> prompts answer from Redis or pgvector instead of hitting the model again.
>
> **Measured:** ≥271 req/s per pod, 69.6% hit rate, Tier-1 hits at 2–5 ms
> against 40 ms upstream.
> **Calculated:** 488 pods for 2 billion requests/day — 1.39 billion upstream
> calls a day avoided.
> **Not proven:** that the fleet scales linearly. The coefficient that would
> settle it needs more hardware than a laptop, and the repo says so.
>
> The thing I did not expect: two prompts asking about *different* requirements
> scored 0.9987 similar — higher than any real paraphrase I tested. A semantic
> cache answers one with the other, and raising the threshold makes it worse.
> Any prompt with an ID or a date in it has this problem.
>
> Java 21 · Spring WebFlux · Kubernetes · Helm · Redis · PostgreSQL + pgvector ·
> Kafka · ONNX · Prometheus · Grafana · Loki · Tempo · Terraform · React
>
> The hardest part was not building it. It was refusing to publish a number I
> had not earned.
>
> #Java #Kubernetes #SystemDesign #AI

---

## Posting notes

**Why none of these says billions were *proven***

What exists is **one pod measured** and **arithmetic** to a fleet — a capacity
plan, not a demonstrated capacity. Option A leads with the design target, which
is the honest framing of an architecture claim, and then names the limit. That
invites the good conversation (the USL, which coefficient matters, why a
single-host test bed cannot produce it) instead of the bad question
(*"so how did you test billions?"*) that has no answer.

**One caveat to keep straight if anyone asks:** the 271 rps was measured with
**Tier 2 switched off**, which is the shipped default. With semantic caching
actually serving, embedding inference dominates and per-pod throughput falls by
one to two orders of magnitude. The fix is architectural — move inference off
the request path onto the indexer that already exists — not more gateway pods.
If asked "does the 488-pod number include semantic caching?", the answer is no,
and `scaling-model.md` says so.

**Before posting**
- Put the repo link in the **first comment**, not the post body — LinkedIn
  suppresses reach on posts with external links.
- Attach the Grafana screenshots. The cache-economics dashboard and the
  Tempo/Loki views carry this better than any paragraph.
- Option A is long. If you want it tighter, cut the Alpine/glibc section — the
  capacity arithmetic and the 0.9987 finding are what will draw comments.
- Check you are comfortable with the repo being public: it contains a dev-only
  Postgres password in `deploy/k8s/dev-infra.yaml` (clearly marked, local only).

**Verified inventory** — everything in the stack list above is in the repo:
8 Maven modules, 14 ADRs, 11 CI jobs, JaCoCo gates at 0.80 line / 0.70 branch,
PIT mutation threshold 70, Terraform under `terraform/aws/`, a `Jenkinsfile`
alongside GitHub Actions.
