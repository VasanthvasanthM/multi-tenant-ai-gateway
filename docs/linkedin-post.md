# LinkedIn post — draft

Three versions. **Option A** is the recommended one. All avoid claiming we
*proved* billions, because we did not — and the honest version is the stronger
signal to the engineers you want reading this. See the note at the bottom.

---

## Option A — the measurement angle (recommended)

> I built a multi-tenant AI gateway that cuts LLM costs with a two-tier cache.
>
> Then I spent nine load tests failing to prove it scales — and that turned out
> to be the most valuable part.
>
> **What it does**
>
> It sits in front of any OpenAI-compatible provider. When a prompt repeats, it
> answers from cache instead of paying for the model again.
>
> • Tier 1 — exact SHA-256 match in Redis
> • Tier 2 — semantic match in PostgreSQL + pgvector, so "how do I reset my
> password" also answers "password reset steps?"
>
> **What I measured**
>
> One pod: **≥271 requests/second** at a **69.6% cache hit rate**, p50 198 ms.
>
> At 2 billion requests/day that works out to 488 gateway pods, 9 Redis nodes,
> 8 PostgreSQL shards and 9 Kafka brokers — and roughly 1.39 billion upstream
> calls a day that never happen.
>
> **What I could not prove**
>
> The Universal Scalability Law has a coefficient, kappa, for the cost pods
> impose on each other. Measure it as near zero and the fleet scales linearly.
> I could not measure it — at three pods my laptop's load generator became the
> bottleneck before the gateway did.
>
> So the capacity table in the repo says exactly that: a plan conditional on
> linear scaling, with the one unmeasured coefficient named. If kappa turned out
> to be 0.01, the table is wrong by two orders of magnitude.
>
> I could have published "handles billions of requests". The number would have
> been the same. It just would not have been true yet.
>
> **The stack**
>
> Java 21, Spring WebFlux, Kubernetes + Helm, Redis, PostgreSQL + pgvector,
> Kafka, Prometheus, Grafana, Loki, Tempo, Alertmanager, Terraform, GitHub
> Actions. React + TypeScript dashboard.
>
> **What I am taking away**
>
> The eight rejected load tests each had a cause worth knowing. My favourite:
> the cache switched itself off under load. A 500 ms Redis timeout against a
> 1.5 s request queue meant lookups timed out, every timeout became an upstream
> call, the extra work deepened the queue — and throughput collapsed instead of
> plateauing. The only visible symptom was the hit ratio quietly falling.
>
> Measuring something honestly is harder than building it.
>
> #Java #Kubernetes #SpringBoot #SystemDesign #Observability #AI

---

## Option B — the bug-hunting angle

> Nine load tests. Eight rejected by my own tooling. Here is what each one
> taught me about measuring a distributed system.
>
> I built a multi-tenant AI gateway — two-tier semantic cache, Java 21, Spring
> WebFlux, Kubernetes. Building it took time. Measuring it honestly took longer.
>
> **1. My sweep could only ever report success.** It offered a fixed request
> rate and asked "did it keep up?". That cannot measure capacity: once one pod
> sustains the rate, every step reports the same number and the model concludes
> perfect linear scaling — from a test that never loaded anything.
>
> **2. Every run looked better than the last.** The "unique" prompts in my test
> corpus were a function of the worker ID and a counter, both of which reset.
> Run it twice against the same Redis and they are all cache hits. A 30% miss
> rate became a 30% hit rate, and every rerun improved.
>
> **3. The cache disabled itself under load.** 500 ms Redis timeout, 1.5 s
> queue. Lookups timed out, each timeout became an upstream call, that deepened
> the queue. Throughput collapsed rather than plateaued.
>
> **4. 16 careful tests killed one mutant.** Mutation testing flips `>` to
> `>=`; my tests passed values near the boundary, which both versions agree on.
> Seven known-answer tests pinning exact output killed nineteen.
>
> **5. Tracing never worked.** The endpoint pointed at Tempo's gRPC port while
> Spring Boot uses the HTTP exporter. Dependency present, sampling set, endpoint
> populated — and zero traces, for months.
>
> **6. The hardware wall was a config file I wrote.** I capped the VM at 3 CPUs
> during a memory squeeze, then spent six runs calling it a laptop limitation.
>
> Final answer: one pod serves ≥271 req/s at a 69.6% hit rate. The fleet
> arithmetic to 2 billion/day is in the repo — with the one coefficient I could
> not measure named rather than hidden.
>
> #SoftwareEngineering #Testing #Kubernetes #Java #Observability

---

## Option C — short

> Built a multi-tenant AI gateway with a two-tier semantic cache. Repeated
> prompts answer from Redis or pgvector instead of hitting the model again.
>
> Measured: **≥271 req/s per pod, 69.6% cache hit rate.**
> Calculated: 488 pods for 2 billion requests/day — 1.39 billion upstream calls
> a day avoided.
> Not proven: that the fleet scales linearly. The coefficient that would settle
> it needs more hardware than a laptop, and the repo says so.
>
> Java 21 · Spring WebFlux · Kubernetes · Redis · pgvector · Kafka · Prometheus
> · Grafana · Loki · Tempo · React
>
> The hardest part was not building it. It was refusing to publish a number I
> had not earned.
>
> #Java #Kubernetes #SystemDesign #AI

---

## Why none of these says "handles billions of requests"

Because it would not survive the first senior engineer who reads it.

What exists is: **one pod measured**, and **arithmetic** to a fleet. That is a
capacity *plan*, not a demonstrated capacity — and the gap is a specific,
nameable coefficient, not vagueness.

Claiming the stronger version invites one question — *"how did you test
billions?"* — with no good answer. The honest version invites a better
conversation: the USL, which coefficient matters, and why a single-host test bed
cannot produce it. Engineers who have run capacity tests recognise the
difference immediately, and they are the audience worth attracting.

**Before posting**
- Add the repo link.
- Attach the Grafana screenshots or the demo video.
- Check you are comfortable with the repo being public — it contains a dev-only
  Postgres password in `deploy/k8s/dev-infra.yaml` (clearly marked, local only).
