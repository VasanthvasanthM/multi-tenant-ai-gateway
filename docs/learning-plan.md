# Work items and learning plan

Two lists. **Part A** is work to do on the system. **Part B** is the walkthrough,
one topic at a time, so it can be picked up after a break without losing place.

Tick items as they are finished. Nothing here is lost if the session ends — this
file is the record.

---

## Part A — work items

| # | Item | Status |
|---|---|---|
| A1 | Push the work on a branch — PR #13 | **done** |
| A1b | Add a CI check that the runtime base is glibc (CI never built `Dockerfile.jvm`, so the Alpine bug was invisible to it) | **done** |
| A10 | **Merge PR #13** once CI is green — 5 commits, currently open | **next** |
| A2 | Make Tier 2 Helm-deployable (image, model volume, env, resources) so `helm upgrade` stops reverting it | **done** |
| A3 | Rewrite `linkedin-post.md` to be more compelling, with the full skills and tools list | **done** |
| A4 | Rotate the Slack webhook — it was pasted into chat and is compromised | **you** |
| A5 | Put Tempo's raised memory limit (384Mi -> 768Mi) into the chart, not just the live cluster | todo |
| A6 | Three failing Dependabot PRs, incl. Spring Boot 3.5.16 -> 4.1.2 | todo |
| A7 | Dev-only Postgres password in `deploy/k8s/dev-infra.yaml` — decide before the repo goes public | todo |
| A8 | Two usage-path bugs: endpoint returns 200/zeros when its table is absent; nothing rejects a >100% hit ratio | todo |
| A12 | Shadow-mode log printed only the stored prompt, so a false hit was indistinguishable from a correct match | **done** |
| A13 | latency_ms meant a different thing per tier; no meter for embedding cost | **done** |
| A14 | A full miss embeds the SAME prompt twice (lookup, then write-back) — carrying the vector forward would halve the dominant cost | todo |
| A15 | HLD known-gaps 2 and 3 are stale — mutation testing has run, and streaming now draws down budget via recordStreamed | todo |
| A16 | Measure the real average Redis entry size (`used_memory / DBSIZE`) — the whole Redis fleet is linear in that one assumption | todo |
| A17 | Run the k6 ladder against a 2000m pod on a real node — the production fleet rests on an assumed 1.66x scaling from 1 to 2 vCPU | todo |
| A18 | Shard migration: planner + copy/verify/reset/cleanup tooling, verified against two real databases | **done** |
| A19 | Shard migration did not handle writes arriving DURING the copy. **Both options built**: `dual-write` (tenant served normally, writes fan out to both shards, reads pinned to the source) and `pause-writes` (503 + Retry-After). Redis-backed, polled every 2s, with a TTL and a staleness bound | **done** |
| A20 | Tier-2 length guard used String.length() not code points — the 1,000 limit was ~500 for emoji and non-BMP text | **done** |
| A21 | **max-in-flight 256 is 5x past the measured saturation point** - written 2026-11-06 in the first commit, eight days before any measurement existed, and never changed since. Little's Law on the measured 271.3 rps / 198 ms gives ~54 in flight, and the campaign recorded collapse at 90 VUs (347 rps -> 55 rps). **Now 64**, the highest level proven healthy, with the derivation in the comment | **done** |
| A22 | **Embedding queue-capacity 128 contradicts its own javadoc** - the comment derives against 256/4 workers (384 ms, rejected as far beyond the 15 ms budget) but shipped 128, which is 192 ms at 4 workers and 384 ms at the 2 workers a real pod has. **Now derived** from the worker count, 2 slots each | **done** |
| A23 | `maxInFlight` javadoc said "concurrent upstream calls" but the gate wraps the whole pipeline - at a 0.696 hit ratio ~70% of slots are held by requests that never reach a provider | **done** |
| A24 | **Streaming bypassed admission control** - `stream()` now goes through the same gate as `execute()`, sharing one counter, so the longest-lived requests can be shed too | **done** |
| A25 | The bulkhead's 64 had no derivation, and became **unreachable** once max-in-flight dropped to 64 - a limit that cannot fire. **Now derived** as maxInFlight/2 | **done** |
| A26 | `noSystemOut` caught `ShardMigrationCli` for real - but only in a full reactor build, since ArchUnit scans class directories and not jars. Rule now exempts `*Cli`, whose stdout is its output contract | **done** |
| A27 | **`ReplicaLagMonitor.start()` was never called** - the gauge was registered in the constructor so it read a healthy 0.0 forever, and the replica-health predicate answered "never behind". ADR-0007's monitoring condition was reporting perfect health without querying a database. Now `@PostConstruct` | **done** |
| A28 | **metering and audit do not route by shard** - each holds one `DatabaseClient`, so in a multi-shard deployment every tenant's `usage_ledger` row lands on one database while the gateway *reads* usage through the ring. Also why dual-write cannot cover those tables. Needs `ShardedDatabase` lifted into a shared module | todo |
| A29 | **The dashboard login form did not exist** - session endpoints and startSession/endSession were built and tested with no UI calling them. **Sign-in screen built**: key exchanged for an HttpOnly cookie, never stored; sign-out; 401 on a believed session drops back to the form; the build-time key still skips it for local dev | **done** |
| A30 | A dashboard test fails about one run in ten - it waits for two query round trips plus a re-render against a 5s default. Timeout raised to 20s; the underlying slowness (jsdom environment is ~65% of suite time) is untouched | **done** |
| A31 | **The outbox relay has no producers.** `OutboxRelay` is correctly wired and polls `control_outbox` every second, but nothing in `src/main` ever inserts a row - the control plane has no HTTP API and its saga has no steps, so no tenant is ever created. Distinct from A27: this one runs, it just has nothing to publish | todo |
| A32 | The lateral-move refusal tells the operator to "check --virtual-nodes", but `run()` builds BOTH rings from the same `--virtual-nodes`, so the CLI cannot produce a lateral move at all - the check guards a ring built wrongly in code. The real CLI hazard is the opposite and undetected: a vnode count that differs from the gateway's produces two self-consistent rings and a plausible, wrong plan | todo |
| A9 | Instrument child spans (Tier 1, Tier 2, embedding, upstream) — every trace is currently a single span | todo |
| A11 | README status section is stale — says no cluster run, no load campaign, PITest never run; all three have happened | todo |

---

> **Problem log:** every bug found during development, how it was found, and why
> it was invisible, is in [`problems-and-solutions.md`](problems-and-solutions.md).
> Add to it as new ones appear.

## Part B — the walkthrough

Worked through in this order. Each is a section in its own file under
`docs/learning/`, written to be read standalone.

| # | Topic | Status |
|---|---|---|
| B1 | **Overview** — what the product is, who it is for, what it replaces | **done** → [B1](learning/B1-overview.md) |
| B2 | **The idea** — where it came from and why this problem is worth solving | **done** → [B2](learning/B2-the-idea.md) |
| B3 | **Problems it solves** — the concrete ones, with the numbers | **done** → [B3](learning/B3-problems-solved.md) |
| B4 | **The request flow** — one request end to end, every decision it passes | **done** → [B4](learning/B4-request-flow.md) |
| B4a | **Why a broken service does not break a request** — the three walls, and what you lose | **done** → [B4a](learning/B4a-failure-behaviour.md) |
| B5 | **HLD** — services, boundaries, what talks to what and why | **done** → [B5](learning/B5-hld.md) |
| B5a | **Every number explained** — derivations, sharding, replication, Kafka, MetalLB, providers, scenarios in full | **done** → [B5a](learning/B5a-numbers-explained.md) |
| B5b | **Real servers and mechanisms** — fleet recalculated on m7i/r7g, the 2 KiB derivation, HNSW, replication, MetalLB/ingress config, providers, the ring worked through | **done** → [B5b](learning/B5b-real-servers-and-mechanisms.md) |
| B5c | **Plain answers** — the node arithmetic, where 381 GiB comes from, what replication actually exists, the three background services, the ring and what happens when a shard is added or removed | **done** → [B5c](learning/B5c-plain-answers.md) |
| B5d | **Shard migration in plain words** — the five-customer story, and how a request finds the new shard after the cutover | **done** → [B5d](learning/B5d-shard-migration-in-plain-words.md) |
| B5e | **Shard operations, worked** - add/remove with real planner output, the 80% vs 20% derivation, and dual-write end to end | **done** -> [B5e](learning/B5e-shard-operations-worked.md) |
| B6 | **LLD** — the classes and data structures that carry the design | **done** → [B6](learning/B6-lld.md) |
| B6a | **LLD, one idea at a time** — all eight rewritten in plain style | **done** → folded into [B6](learning/B6-lld.md) |
| B6-steps | **LLD step by step** — the teaching version, all eight ideas | **done** → [B6-steps](learning/B6-steps.md) |
| B6b | **Which architecture did we use?** — the patterns and styles by name | **done** -> [B6b](learning/B6b-architecture-used.md) |
| B6b1 | **Each pattern: where and why** - all five groups: Caching, Messaging, Resilience, Data, Code-level | **done** -> [B6b1](learning/B6b1-patterns-where-and-why.md) |
| B6c0 | **What each service does** - one customer followed through, in plain words | **done** -> [B6c0](learning/B6c0-what-each-service-does.md) |
| B6c1 | **Metering explained** - the one-bill problem, where token counts come from, why two tables | **done** -> [B6c1](learning/B6c1-metering-explained.md) |
| B6c | **Which APIs did we use?** — the endpoints we expose, and the ones we call | **done** -> [B6c](learning/B6c-apis.md) |
| B7 | **Redis** — the seven key shapes, hash tags, why auth is the one thing that fails closed, and the 2 KiB assumption | **done** -> [B7](learning/B7-redis.md) |
| B8 | **Kafka** — five topics, tenant partitioning, the four consumer settings and the disaster each prevents, what gets dropped | **done** -> [B8](learning/B8-kafka.md) |
| B9 | **PostgreSQL** — sharding ring, replicas, partitioning, pgvector and HNSW | **next** |
| B10 | **Capacity estimation** — from one measured pod to 2 billion requests/day | todo |
| B11 | **Start and stop** — every command, in order, with what breaks | todo |
| B12 | **Testing with k3d and k6** — how the load campaign works and how to run it | todo |
| B13 | **Challenges and how they were solved** — the nine load tests, the bugs, the dead ends | todo |

---

## How to use this

Ask for a topic by number (*"do B4"*) or just say *"next"* to continue in order.

Each topic gets written to `docs/learning/` as a permanent file, so this is
building a book about the system as we go, not a conversation that disappears.
