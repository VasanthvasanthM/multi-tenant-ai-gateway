# ADR-0010: Zero synchronous inter-service calls on the hot path

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

The gateway needs data that the control plane owns: which API keys are valid, which
tenant they belong to, and what that tenant's quota is. The obvious implementation is
an HTTP or gRPC call from gateway to control plane, cached locally.

## Decision

The gateway makes **no synchronous call to any other service** while serving a request.
Key and quota changes propagate over Kafka (`gateway.tenant.v1`) into local Redis; the
gateway reads only Redis and PostgreSQL.

## Rationale

**Availability multiplies.** A synchronous dependency means the data plane's
availability is the product of its own and the control plane's. Two services at 99.9%
chained give 99.8%, and that is before considering that a *slow* dependency is worse
than a dead one — a control plane at p99 = 2 s would put 2 s on the gateway's p99 even
with a cache, on every cache miss.

**It changes what a control-plane deploy means.** With a synchronous dependency, a
rolling restart of the control plane is a data-plane event: connection resets, retries,
latency. Without one, the control plane can be down for an hour and the gateway will
not notice. That is what makes the control plane safe to iterate on.

**The data suits propagation.** API keys and quotas change rarely — key issuance,
revocation, a plan change — and are read on every single request. That is precisely the
shape that wants push-based replication rather than request-time lookup.

## Consequences

**Accepted costs**

- **Propagation delay on revocation.** This is the real cost and it is a security
  consideration: a revoked key stays valid until the event arrives. Bounded by Kafka
  lag (sub-second normally) and mitigated by a direct cache invalidation path for
  revocation specifically. It must be *measured*, not assumed.
- Eventual consistency for quota changes: a tenant can briefly use an old limit.
- Redis becomes load-bearing for authentication. A Redis failure is a data-plane
  failure.
- Key state is duplicated — authoritative in PostgreSQL, replicated in Redis — so the
  two can disagree, and a reconciliation path is needed.

**Benefits**

- Data-plane availability is independent of the control plane.
- Authentication is a local Redis read, not a network call.
- The control plane can be deployed, restarted or broken without user impact.

## Enforcement

There is no HTTP client for internal services in the gateway module at all — the
absence is the enforcement. The layering ArchUnit rule pins `web -> pipeline ->
cache/provider`, so a new outbound dependency would have to be added deliberately and
visibly.
