# ADR-0002: Embeddings computed in-process

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author
- **Deviation from blueprint:** the blueprint left the embedding source unspecified

## Context

Tier 2 needs a vector for every prompt that misses Tier 1, on the request path, within
a **15 ms** budget. The options are a hosted embedding API, a sidecar inference
service, or in-process inference.

## Decision

In-process: ONNX Runtime via DJL, `all-MiniLM-L6-v2`, 384 dimensions, mean pooling,
L2-normalised.

## Rationale

A hosted embedding API costs 50-300 ms including TLS and queueing. Against a 15 ms
budget that is not a tuning problem — it would make **Tier 2 slower than the upstream
call it exists to avoid**, which inverts the purpose of the feature. It would also add
a hard external dependency to the cache path and a second per-request bill.

A sidecar removes the internet but keeps a network hop, a second process to deploy and
scale, and a serialisation cost on every call.

In-process inference measures ~3 ms. MiniLM is small enough (~87 MB) to sit beside the
JVM, and 384 dimensions keeps the HNSW index compact.

## Consequences

**Accepted costs**

- Image size and memory. Mitigated by stripping non-`linux-x64` ONNX natives:
  **130 MB -> 7 MB**.
- Native memory lives **outside** the Java heap, so `MaxRAMPercentage=70` has to leave
  room for it. This is why `values-full.yaml` raises the gateway to 640 Mi when Tier 2
  is enabled; at 512 Mi the result is an OOMKill with no Java stack trace.
- DJL's `Predictor` is not thread-safe, so a predictor **pool** is required. A shared
  instance corrupts output under concurrency while passing single-threaded tests.
- BERT needs three inputs, so `includeTokenTypes=true` is mandatory.
- Changing the model changes every stored vector. A dimension change is a migration.

**Benefits**

- ~3 ms, no network, no external dependency on the cache path, no per-request cost.
- Deterministic: the same text always yields the same vector, which is what makes the
  cache reproducible at all.

## Alternatives rejected

- **Hosted embedding API** — 50-300 ms against a 15 ms budget.
- **Sidecar** — a hop plus an operational component for no latency gain.
- **No semantic tier, exact match only** — gives up the paraphrase traffic, which is
  where the remaining cache value is. See the unit economics in
  [../architecture/scaling-model.md](../architecture/scaling-model.md).

## Note on the fallback

Where no model file is present, `HashingEmbeddingModel` keeps the service running with
deterministic trigram feature hashing. It is **not semantic**, and
`EmbeddingService.isSemanticallyMeaningful()` returns `false` so Tier 2 refuses to
serve rather than returning confident nonsense. A fallback that silently degrades
correctness is worse than one that announces it.
