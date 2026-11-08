# Multi-Tenant AI Gateway & Semantic Caching Middleware

A horizontally scalable AI gateway that puts a two-tier semantic cache in front of LLM
providers, enforces per-tenant isolation and budgets, and is built entirely on free and
open-source software.

**Status: in development.** All five services are implemented and verified against real
Redis, real pgvector and a real embedding model; CI builds, scans and publishes five
container images, and the consistent-hash ring now routes Tier-2 reads and writes.
Not finished: no cluster run or load campaign has happened yet, the PITest mutation run
has never been executed so no mutation score exists, and four of the nine modules have no
unit tests at all. See [Roadmap](#roadmap) for the phase-level state and
[docs/architecture/HLD.md](docs/architecture/HLD.md#13-known-gaps) for the gaps stated
plainly.

---

## Why this exists

LLM calls are slow and metered. A large share of production prompts are near-duplicates of
prompts already answered. This gateway intercepts them:

| Path | Latency target | Cost |
|---|---|---|
| Tier 1 — Redis exact hash | < 2 ms | free |
| Tier 2 — pgvector cosine similarity | < 15 ms | free |
| Miss — upstream LLM provider | 0.5–30 s | metered |

Everything else in the design follows from protecting those two numbers.

---

## Documentation

| Document | What it answers |
|---|---|
| [docs/architecture/HLD.md](docs/architecture/HLD.md) | What the system is, how a request flows, how it scales, what it does when things break. C4 Levels 1-2. |
| [docs/architecture/LLD.md](docs/architecture/LLD.md) | The threading model, the algorithms, the schema, and where a plausible alternative is wrong. C4 Level 3. |
| [docs/architecture/scaling-model.md](docs/architecture/scaling-model.md) | The billions-of-users argument: USL model, unit economics, what breaks first. Every number labelled measured or calculated. |
| [docs/adr/](docs/adr/) | 13 decision records - why WebFlux over virtual threads, why in-process embeddings, why no PgBouncer. |
| [docs/api/openapi.yaml](docs/api/openapi.yaml) | The HTTP contract. Validated as OpenAPI 3.1 in CI and cross-checked against the Java error taxonomy. |
| [PROGRESS.md](PROGRESS.md) | Task-level state and 40 recorded gotchas. |

---

## Architecture

```
                   MetalLB  (L4 load balancer, free, bare metal)
                              |
                   ingress-nginx  (L7, least-conn)
                              |
            +-----------------+-----------------+
            |                 |                 |
      gateway pod 1     gateway pod 2     gateway pod N      <- HPA on RPS & p99
            |                 |                 |
            +--------+--------+--------+--------+
                     |                 |
              Redis (Tier 1)    PostgreSQL + pgvector (Tier 2)
                     |                 |
                     +--- Kafka -------+----> indexer / metering / audit
                          (beside the request path, never inside it)
```

### Services

| Service | Responsibility | Scales with | If it dies |
|---|---|---|---|
| `gateway` | Data plane: auth, rate limit, 2-tier cache, provider fan-out, SSE streaming | request RPS | total outage |
| `control-plane` | Tenants, API keys, quotas, thresholds. Saga orchestrator + outbox producer | admin ops/day | no new tenants; traffic unaffected |
| `indexer` | Every write to the vector store, TTL eviction, index maintenance, shadow-mode analysis | cache misses/sec | hit rate drops; answers stay correct |
| `metering` | Token aggregation, budget drawdown, invoice lines | events/sec | billing lags |
| `audit` | Append-only compliance log | events/sec | compliance gap |

Five services because each has a **different scaling axis and a different blast radius** —
the only justification for a service boundary that survives scrutiny. Shared code lives in
two libraries (`common`, `embedding`), not in a shared service.

**The hot path makes no synchronous call to any other service.** Key and quota changes reach
the gateway asynchronously via Kafka into local Redis, so a cache hit touches Redis and
nothing else. gRPC is reserved for genuine request/response admin operations such as
`PurgeTenantCache`.

---

## Stack

Everything below is free and open source.

| Layer | Technology |
|---|---|
| Language / runtime | Java 21 |
| Framework | Spring Boot 3.5 · WebFlux · Project Reactor · Netty |
| Build | Maven 3.9 (wrapper included — no local install needed) |
| L4 load balancer | MetalLB |
| L7 ingress | ingress-nginx |
| Orchestration | Kubernetes via k3d (k3s in Docker) · Helm 3 |
| Infrastructure as code | Terraform — local target applied, AWS target validated |
| Tier 1 cache | Redis 7 · Lettuce reactive · Lua for atomic rate limiting |
| Tier 2 cache | PostgreSQL 17 · pgvector 0.8 · HNSW · R2DBC |
| Embeddings | ONNX Runtime + DJL · all-MiniLM-L6-v2 (384-d, int8, in-process) |
| Messaging | Apache Kafka (KRaft) · reactor-kafka |
| Resilience | Resilience4j — circuit breaker, bulkhead, retry, time limiter |
| Observability | Micrometer · Prometheus · Grafana · Loki · Tempo · OpenTelemetry |
| Testing | JUnit 5 · StepVerifier · Testcontainers · WireMock · ArchUnit · PITest |
| Load testing | k6 (cpuset-pinned) + Universal Scalability Law fit |
| Code quality | SonarQube CE · JaCoCo · SpotBugs · PMD · Spotless |
| Security | Trivy · OWASP Dependency-Check · Gitleaks · checkov |
| CI/CD | GitHub Actions *and* Jenkins (same stages, thin config, logic in Maven) |
| Dashboard | React · TypeScript · Vite |

---

## Scaling design

The target is a system whose design does not *block* billion-user scale, and which can prove
that claim from a laptop. Three mechanisms:

**Consistent-hash sharding ring.** Shard key is `tenantId`, with ~150 virtual nodes per
physical shard so adding a shard remaps only K/N keys. This works unusually well here: a
semantic cache lookup is *always* scoped to one tenant, so there is never a cross-shard
approximate-nearest-neighbour search — the thing that normally kills sharded vector stores.
Whale tenants are sub-sharded as `tenantId:bucket(promptHash)`.

**Read replicas.** Writes to the primary, vector searches to replicas. Replica lag is
*harmless here*: a stale replica produces a cache **miss**, not a wrong answer, so the system
degrades to a slightly lower hit rate and never to incorrect output. That permits far more
aggressive read fan-out than a normal OLTP system allows.

**Transactional outbox + saga.** Tenant provisioning spans Postgres, Redis and Kafka, which
share no transaction. The outbox makes the event publish atomic with the database write; an
orchestrated saga compensates when a later step fails. Deliberately **not** applied to gateway
telemetry, where a dropped event is acceptable but 3 ms is not.

### How the claim gets proven

Not with a big number — with a scaling law. Throughput is measured at 1…6 pods and fitted to
the Universal Scalability Law:

```
X(N) = λN / (1 + σ(N−1) + κN(N−1))
```

σ ≈ 0 and κ ≈ 0 is measured evidence of linear scalability, and makes extrapolation
defensible arithmetic rather than a guess. A non-zero κ names the exact node count where
throughput peaks — which is the interesting result either way. Published alongside: a
lock-contention flame graph, per-core unit economics, and a "what breaks first" table.

---

## Hardware reality

Developed on a 4-core / 7.2 GB laptop. The full stack does not fit in RAM at once, so it ships
as profiles. **Never run two at the same time.**

| Profile | Contents | ~RAM |
|---|---|---|
| `dev` | Compose: 1 gateway + Redis + Postgres | 1.1 GB |
| `scale` | k3d: 1→6 gateway pods, Redis, Postgres, mock provider | 2.8 GB |
| `full` | k3d: all 5 services + Redis + Postgres + Kafka | 2.6 GB |
| `obs` | k3d: 1 gateway + Prometheus/Grafana/Loki/Tempo | 2.9 GB |
| `quality` | SonarQube alone, stack stopped | 2.5 GB |

Benchmarks pin k6 to cores 6–7 and the system under test to cores 0–5. Every published number
states that configuration, because a load generator sharing 4 cores with its target otherwise
measures itself.

---

## Upstream providers

A pluggable `LlmProvider` SPI with three implementations:

- **`MockLlmProvider`** — deterministic, with configurable latency and failure injection.
  **All load tests and CI run against this**, because it is the only way to measure this
  gateway rather than someone else's API.
- **`OpenAiCompatibleProvider`** — one adapter, configurable base URL. Groq, OpenRouter,
  Together, Google AI Studio and llama.cpp all speak this format, so switching provider is
  configuration, not code.
- **`OllamaProvider`** — implemented and documented, **off by default**: on a 4-core CPU with
  no CUDA GPU it yields ~8–15 tok/s and would both ruin demos and distort benchmarks.

---

## Quick start

Requires JDK 21 and Docker. Everything else is downloaded by the wrapper.

```bash
./mvnw -B verify                    # build + unit + integration tests
./mvnw -B verify -Pquality          # + SpotBugs, PMD, Spotless
./mvnw -B verify -Pmutation         # + PITest mutation score
./mvnw -B verify -Psecurity         # + OWASP Dependency-Check
```

---

## Repository layout

```
common/          shared domain: cache keys, shard ring, error taxonomy
embedding/       in-process ONNX embedding (library, not a service)
gateway/         data plane service
control-plane/   admin service: tenants, keys, quotas, saga, outbox
indexer/         vector-store writes, eviction, index maintenance
metering/        token aggregation and billing
audit/           append-only compliance log
coverage-report/ aggregated JaCoCo across all modules (feeds SonarQube)
contracts/       gRPC protobuf definitions and Kafka event schemas
dashboard/       React + TypeScript tenant dashboard
deploy/          Helm chart, Compose files, raw k8s manifests
terraform/       local/ (applied) and aws/ (validated + Infracost)
loadtest/        k6 scripts and the USL analysis
docs/            HLD, LLD, ADRs, C4 diagrams, runbooks
config/          PMD ruleset, SpotBugs filter, OWASP suppressions
```

---

## Roadmap

| Phase | Deliverable | Status |
|---|---|---|
| P0 | Maven multi-module scaffold, quality gates, 10 modules | ✅ done |
| P1 | HLD, LLD, C4 diagrams, 13 ADRs, OpenAPI contract | ✅ done |
| P2 | Gateway core: router, auth filter, error taxonomy | ✅ done, verified live |
| P3 | Tier-1 Redis cache + Lua token-bucket rate limiter | ✅ done, verified vs real Redis |
| P4 | Embedding service + Tier-2 pgvector + shadow mode | ✅ done, verified vs real pgvector |
| P5 | Resilience4j, provider SPI, fallback chain, SSE | ✅ done, breaker verified live |
| P6 | Kafka telemetry, outbox, saga, metering, audit | ✅ done, idempotency verified |
| P7 | Consistent-hash shard ring + read-replica routing | ✅ wired into Tier-2 routing; whale sub-sharding still designed only |
| P8 | k3d + MetalLB + ingress-nginx + Helm + HPA | ✅ written and rendered; **no cluster run yet** |
| P9 | Terraform local and AWS targets | ✅ both validate; AWS is **never applied** by design, checkov 176/0 |
| P10 | Observability stack and dashboards | ✅ written; no cluster run yet |
| P11 | Test suite to gate, PITest, ArchUnit, SonarQube | 🟡 SpotBugs/PMD/ArchUnit/SonarCloud clean; **gateway 89.5% line / 87.5% branch**, clears the gate; Tier-2 IT runs against real pgvector; **PITest wired into CI but never yet executed — no mutation score exists** |
| P12 | k6 load campaign + USL fit + performance report | 🟡 tooling done, USL fitter verified (13 tests); **campaign not run - needs a cluster** |
| P13 | GitHub Actions and Jenkins pipelines | ✅ done, 5 images published; **CI was silently dead for runs #21–#24** — a `secrets` reference in an `if:` made GitHub refuse the whole workflow (0 jobs, 0s, no log). Fixed, and guarded by `scripts/check-workflows.py` |
| P14 | React + TypeScript tenant dashboard | ✅ 37 tests, bundle budgeted in CI; API key in the bundle is a stated gap |

---

## Deviations from the original specification

The source blueprint was revised in five places. Each has an ADR explaining the reasoning:

1. **Embedding source was unspecified** — a remote embedding API would cost 50–300 ms against
   a 15 ms budget, so inference is in-process.
2. **Kafka was drawn inside the request path** — it adds broker latency, breaks SSE streaming,
   and needs a reply channel to a possibly-dead connection. Moved beside the path.
3. **PgBouncer in front of R2DBC** — conflicts with R2DBC's own pool and breaks server-side
   prepared statements. Removed.
4. **"3 replicas × 5,000 RPS = 15,000 RPS"** — three containers share four physical cores, so
   this is not measurable locally. Replaced with a per-node curve plus an explicit USL
   extrapolation.
5. **Leaky bucket rate limiting** — replaced with a token bucket, which permits the bursts
   real API clients produce.

---

## Licence

MIT.
