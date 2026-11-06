# Documentation

Design documentation for the Multi-Tenant AI Gateway.

## Start here

| Document | What it answers |
|---|---|
| [architecture/HLD.md](architecture/HLD.md) | What the system is, how it is split, how a request flows, how it scales, what it does when things break. C4 Levels 1 and 2. |
| [architecture/LLD.md](architecture/LLD.md) | How it is actually built: the threading model, the algorithms, the schema, and the places a plausible-looking alternative is wrong. C4 Level 3. |
| [architecture/scaling-model.md](architecture/scaling-model.md) | The "billions of users" argument — the USL model, the unit economics, and what breaks first. Labels every number as measured or calculated. |
| [adr/](adr/) | Thirteen decision records. Why WebFlux, why in-process embeddings, why no PgBouncer, and ten more. |
| [api/openapi.yaml](api/openapi.yaml) | The data-plane HTTP contract. Validated as OpenAPI 3.1 in CI, and cross-checked against the Java error taxonomy. |

## Reading order, by question

**"What does this thing do?"**
→ [HLD §1–§4](architecture/HLD.md)

**"Would it really scale?"**
→ [scaling-model.md](architecture/scaling-model.md), then
[ADR-0006](adr/0006-shard-key-is-tenant-id.md) (sharding) and
[ADR-0013](adr/0013-usl-not-headline-rps.md) (why there is no headline RPS figure)

**"Why is it built this way and not the obvious way?"**
→ [adr/README.md](adr/README.md). Every entry states what was rejected.

**"How do I call it?"**
→ [api/openapi.yaml](api/openapi.yaml)

**"What is actually finished?"**
→ [HLD §12–§13](architecture/HLD.md) for quality and known gaps, and
[`../PROGRESS.md`](../PROGRESS.md) for the task-level state and 40 recorded gotchas.

## A note on what these documents claim

Design documents drift from code, and a document that describes intentions as though
they were implementation is worse than no document. These try to avoid that in three
specific ways:

1. **Code excerpts are copied from the repository**, not written for the prose.
2. **Measured numbers are marked.** `scaling-model.md` tags every figure (M) measured
   or (C) calculated, and there is currently **no measured throughput figure anywhere**
   because no load campaign has been run.
3. **Gaps are listed.** [HLD §13](architecture/HLD.md) names eight, including the two
   that matter most: the consistent-hash ring is built but **not wired into routing**,
   and coverage is **51.7% against an 80% gate**.

The OpenAPI spec is the one part that is mechanically checked: CI validates it against
the 3.1 schema and asserts that its error-code enum and documented HTTP statuses match
what `GatewayError` actually emits, so that particular drift fails the build rather
than misleading a reader.
