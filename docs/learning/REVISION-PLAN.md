# Revision plan — second pass

The first pass (B1–B13, 30 documents) was **discovery**: working out what the
system does and finding what was wrong with it.

This pass is **consolidation**, ordered by how the questions actually arrive. An
interview does not start at B1; it starts with *"so what did you build?"* and
narrows from there.

One topic at a time. Say **next** to move on, or name a number to jump.

---

## Part 1 — The story (the first five minutes)

| # | Topic | The question it answers |
|---|---|---|
| **R1** | The 60-second pitch | *"Tell me about this project"* |
| **R2** | One request, end to end | *"Walk me through what happens"* |
| **R3** | Five services, and why they are separate | *"Why microservices?"* |

## Part 2 — The mechanisms

| # | Topic | The question it answers |
|---|---|---|
| **R4** | Tier 1: Redis, keys, rate limiting | *"How does the cache work?"* |
| **R5** | Tier 2: embeddings, pgvector, HNSW | *"How do you match similar questions?"* |
| **R6** | Kafka: topics, consumers, the outbox | *"How do the services talk?"* |
| **R7** | PostgreSQL: sharding, partitioning, replicas | *"How does this scale past one database?"* |
| **R8** | Resilience: six mechanisms, six failures | *"What happens when the vendor is down?"* |

## Part 3 — The numbers

| # | Topic | The question it answers |
|---|---|---|
| **R9** | One measurement, and everything built on it | *"How do you know it scales?"* |
| **R10** | Measured vs derived vs assumed | *"Which of those numbers do you trust?"* |

## Part 4 — The stories that land

| # | Topic | The question it answers |
|---|---|---|
| **R11** | Four bugs worth telling | *"Tell me about a hard problem"* |
| **R12** | What is still wrong with it | *"What would you do differently?"* |

---

## How to use this

Each topic is a **spoken answer**, not a document — short enough to say out
loud, with the one number or one sentence that makes it land.

The depth is already written down. Each revision topic points at the full
treatment if you want to go back:

```
R1, R2   -> B1, B4
R3       -> B6c0, B5
R4       -> B7, B6b1 (Caching, Data)
R5       -> B9, B5b
R6       -> B8
R7       -> B9, B5e
R8       -> B6b1 (Resilience)
R9, R10  -> B10, B10a
R11, R12 -> B13, problems-and-solutions.md
```
