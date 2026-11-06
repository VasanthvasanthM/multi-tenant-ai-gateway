# ADR-0004: No PgBouncer in front of R2DBC

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author
- **Deviation from blueprint:** the blueprint placed PgBouncer in front of the database

## Context

PostgreSQL connections are expensive, and the standard answer is PgBouncer in
transaction-pooling mode. The blueprint included it.

## Decision

No PgBouncer. `r2dbc-pool` is the only pool.

## Rationale

**It solves a problem R2DBC does not have.** PgBouncer exists because thread-per-request
applications open a connection per concurrent request. A reactive application
multiplexes many in-flight queries over a small pool by construction — the pool is
already small.

**Transaction pooling breaks server-side prepared statements.** PgBouncer in
transaction mode may hand a client a different backend per transaction, and a prepared
statement created on one backend does not exist on another. The failure is intermittent
`prepared statement "S_1" does not exist` under load, which is close to the worst
diagnostic shape available: it passes in development and fails in production, randomly.

**Two pools interact badly.** `r2dbc-pool` holding 20 connections against a PgBouncer
holding 20 backends gives neither the behaviour you configured, and connection
lifecycle bugs land in the gap between them.

**And it is another hop.** Latency on every query plus a process to run and monitor.

## Consequences

**Accepted costs**

- Connection count scales with pod count. At 100 pods x 10 connections = 1,000
  connections, a real pooler becomes necessary again — and the right answer at that
  point is **sharding**, which the architecture already plans for, not a pooler in
  front of one primary.
- No cross-pod connection sharing.

**Benefits**

- Server-side prepared statements work, which is a measurable gain on the repeated
  parameterised queries this system runs.
- One pool, one set of semantics, one place to tune.
- One fewer component.

## Revisit when

Total connections across all gateway pods approach the PostgreSQL `max_connections`
ceiling. The first response is sharding ([0006](0006-shard-key-is-tenant-id.md)) and
read replicas ([0007](0007-read-replicas-are-safe.md)). A pooler is the answer only if
both are exhausted — and then in **session** mode, or with prepared statements disabled
deliberately rather than by accident.
