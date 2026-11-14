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
| A9 | Instrument child spans (Tier 1, Tier 2, embedding, upstream) — every trace is currently a single span | todo |

---

## Part B — the walkthrough

Worked through in this order. Each is a section in its own file under
`docs/learning/`, written to be read standalone.

| # | Topic | Status |
|---|---|---|
| B1 | **Overview** — what the product is, who it is for, what it replaces | todo |
| B2 | **The idea** — where it came from and why this problem is worth solving | todo |
| B3 | **Problems it solves** — the concrete ones, with the numbers | todo |
| B4 | **The request flow** — one request end to end, every decision it passes | todo |
| B5 | **HLD** — services, boundaries, what talks to what and why | todo |
| B6 | **LLD** — the classes and data structures that carry the design | todo |
| B7 | **Redis** — what it holds, the hash tags, the token bucket, fail-open | todo |
| B8 | **Kafka** — topics, partitioning, consumer settings, the outbox pattern | todo |
| B9 | **PostgreSQL** — sharding ring, replicas, partitioning, pgvector and HNSW | todo |
| B10 | **Capacity estimation** — from one measured pod to 2 billion requests/day | todo |
| B11 | **Start and stop** — every command, in order, with what breaks | todo |
| B12 | **Testing with k3d and k6** — how the load campaign works and how to run it | todo |
| B13 | **Challenges and how they were solved** — the nine load tests, the bugs, the dead ends | todo |

---

## How to use this

Ask for a topic by number (*"do B4"*) or just say *"next"* to continue in order.

Each topic gets written to `docs/learning/` as a permanent file, so this is
building a book about the system as we go, not a conversation that disappears.
