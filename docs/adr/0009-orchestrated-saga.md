# ADR-0009: Orchestrated saga, not choreography

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

Tenant provisioning spans several steps across services: create the tenant row, issue
an API key, initialise quota in Redis, seed the cache namespace, emit a tenant event.
Any step can fail, and the earlier ones must then be undone.

The two standard shapes are choreography — each service reacts to events and emits its
own — and orchestration, where one component drives the sequence and records progress.

## Decision

Orchestration. `SagaOrchestrator` drives `SagaStep`s; state lives in
`saga_instances`; compensations run **backwards** from the failing step.

## Rationale

The deciding argument is operational, not architectural.

When a tenant is stuck half-provisioned at 3am, the question is "which step failed, and
what has already been undone?" With orchestration that is one `SELECT` against an
explicit state machine. With choreography the answer has to be *reconstructed* from
event logs across four services, each holding one fragment of a sequence that is
written down nowhere.

Choreography's advantage is loose coupling: no component knows the whole flow. That is
genuinely valuable when flows are independent. It is a liability when the flow is a
*transaction* with compensations, because the compensation order is a property of the
whole sequence — and in choreography that ordering exists only as emergent behaviour.

A queryable state machine also makes the saga **testable as a unit**: the orchestrator
can be driven with stub steps and asserted to compensate in the right order.

## Consequences

**Accepted costs**

- The orchestrator knows every step, so adding one means changing it. Accepted: the
  sequence is business logic and belongs somewhere explicit.
- A component that can be a bottleneck. It is in the control plane, where volume is
  tenant lifecycle events rather than requests.
- Saga state is a table to migrate and retain.

**Benefits**

- One `SELECT` answers "what is stuck and why".
- Compensation order is explicit and reviewable.
- Unit-testable.
- `saga_instances` carries a partial index on unfinished sagas, so finding stuck work
  is cheap and the index does not grow with history.

## Alternatives rejected

- **Choreography** — diagnosis cost, and implicit compensation ordering.
- **Two-phase commit** — needs XA across PostgreSQL, Redis and Kafka. Redis and Kafka
  do not participate, and 2PC blocks on coordinator failure.
- **Best-effort with manual repair** — which is what happens by accident if no decision
  is made.
