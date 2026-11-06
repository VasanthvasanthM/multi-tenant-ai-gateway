# ADR-0003: Kafka beside the request path, never inside it

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author
- **Deviation from blueprint:** the blueprint drew the usage event as an inline step

## Context

Every request produces telemetry: a usage record for billing, an audit entry, and a
cache-write event for the indexer. The blueprint placed the Kafka publish inside the
request flow, before the response.

## Decision

Events are published **fire-and-forget after the response has been produced**. The
request path never awaits a broker acknowledgement.

## Rationale

An awaited publish adds roughly **3 ms** to every request, including every cache hit.
A Tier-1 hit is budgeted at under 2 ms, so the telemetry write would be the single most
expensive part of the fastest path.

It also breaks streaming. An SSE response starts emitting tokens before the final
usage figures exist, so "publish, then respond" cannot be expressed for a streamed
request without buffering the whole stream — which destroys the reason for streaming.

The asymmetry that settles it: **a dropped usage event costs a fraction of a cent. 3 ms
on every request costs the latency budget the product is sold on.**

## Consequences

**Accepted costs**

- Usage events can be lost if a pod dies between responding and publishing. Accepted,
  and bounded: metering is idempotent, so the failure mode is *under*-billing a few
  requests, never double-billing.
- Billing is eventually consistent. The ledger is not a real-time balance.

**Benefits**

- Cache hits stay in single-digit milliseconds.
- Streaming works.
- Kafka being down degrades telemetry, not availability.

## Where this does NOT apply

Tenant provisioning, key revocation and billing state transitions **cannot** be lost.
Those use a transactional outbox in the control plane, off the hot path, where the
extra latency is irrelevant. See [0008](0008-outbox-only-off-hot-path.md).

## Alternatives rejected

- **Inline publish** — latency and SSE, as above.
- **Outbox on the gateway** — correct durability, +3 ms and a database write on every
  request including cache hits. The hot path would then depend on PostgreSQL even for a
  Tier-1 hit.
- **Local disk buffer** — makes pods stateful to protect data worth less than the
  complexity.
