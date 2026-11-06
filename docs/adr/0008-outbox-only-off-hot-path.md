# ADR-0008: Transactional outbox off the hot path only

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

The transactional outbox pattern makes a database write and an event publish atomic:
both land in one transaction, and a relay publishes the row afterwards. It removes the
"wrote the row, crashed before publishing" failure entirely.

The question is where to pay for it.

## Decision

Outbox in the **control plane** and in **metering**. Not on the gateway request path,
which publishes fire-and-forget.

## Rationale

The outbox costs a database write and roughly 3 ms. Applied to the gateway that is
~3 ms on **every request, including every cache hit**, plus a hard dependency on
PostgreSQL for a Tier-1 hit that otherwise touches only Redis. A cache hit budgeted at
under 2 ms would spend more time recording that it happened than serving it.

So the decision is made per data class, by asking what loss actually costs:

| Data | Loss costs | Mechanism |
|---|---|---|
| Usage event | a fraction of a cent, and metering is idempotent so the error is *under*-billing | fire-and-forget |
| Audit event | one line in a log | fire-and-forget |
| Cache-write event | one entry not indexed; next identical prompt re-caches it | fire-and-forget |
| Tenant provisioning | a tenant stuck half-created, needing manual repair | **outbox** |
| Key revocation | **a revoked credential still working** | **outbox** |
| Billing state change | incorrect invoice | **outbox** |

The bottom three are not recoverable by retrying the user's action, and one of them is
a security boundary. The top three are self-healing.

## Consequences

**Accepted costs**

- Two durability models in one system, so "is this event guaranteed?" has to be
  answered per topic. Documented at each publisher — `GatewayEventPublisher` carries an
  explicit comment that it is deliberately **not** outboxed, so that nobody later
  "fixes" it.
- The relay is a poller, so control-plane events have polling latency. Irrelevant for
  provisioning.

**Benefits**

- Cache hits stay fast and do not depend on PostgreSQL.
- Where durability genuinely matters, it is actually guaranteed rather than assumed.

## Implementation note

The relay claims work with:

```sql
SELECT ... WHERE published_at IS NULL ORDER BY id LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

`SKIP LOCKED` is what makes it **multi-replica safe with no leader election and no
distributed lock** — the database is already a consensus system. Without it the
replicas serialise behind one another and throughput equals a single replica plus lock
contention.

The unpublished index is **partial** (`WHERE published_at IS NULL`), so it stays the
size of the backlog rather than the size of history.
