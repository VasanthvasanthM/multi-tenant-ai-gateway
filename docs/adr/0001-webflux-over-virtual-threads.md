# ADR-0001: Spring WebFlux rather than virtual threads

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

The workload is almost entirely I/O: a request waits on Redis, then possibly on
PostgreSQL, then possibly on an LLM provider for anywhere between 200 ms and 30 s.
Java 21 offers two credible models for this.

Virtual threads (Project Loom) are the simpler one. Blocking code stays blocking,
`Thread.sleep` is cheap, stack traces stay readable, and the whole reactive learning
curve disappears. For most I/O-bound services in 2026 it is the correct default, and
choosing Reactor instead needs a reason.

## Decision

Spring WebFlux, Project Reactor and Netty.

## Rationale

**Backpressure.** It is the one thing virtual threads do not give you.

An LLM gateway is a *fan-in* to a constrained resource. Upstream providers have hard
rate limits and finite concurrency; so does the connection pool. With virtual threads,
100,000 concurrent requests cheerfully create 100,000 virtual threads, all of which
queue on the provider. The queue is unbounded, latency grows without limit, and the
process eventually dies of heap exhaustion holding requests nobody is waiting for any
more. The cheapness of a virtual thread is exactly what removes the natural brake.

Reactor propagates demand through the chain. A bounded `Bulkhead` or a limited
`flatMap` concurrency does not merely queue excess work — it refuses it, and the
refusal travels back to the client as a `503 Overloaded` *while the system is still
healthy*. Shedding load early is the difference between a degraded service and an
outage.

Streaming reinforces the choice: SSE token streams are naturally
`Flux<ServerSentEvent>`, with cancellation propagating upstream when a client
disconnects. On a blocking model, an abandoned stream keeps generating — and keeps
billing.

## Consequences

**Accepted costs**

- No blocking calls anywhere, and the rule needs mechanical enforcement, not
  discipline. `ArchitectureRulesTest` forbids `block()`, `Thread.sleep` and
  `new Thread`; it caught a real `.block()` on the hot path in `ProviderRegistry`.
- Genuinely blocking work must be explicitly offloaded. ONNX inference runs on
  `Schedulers.boundedElastic()`.
- Debugging is harder. Stack traces are not call histories.
- Smaller pool of engineers who are comfortable in it.

**Benefits**

- Load shedding under overload rather than unbounded queueing.
- Streaming and cancellation are native.
- Low thread count: a 512 Mi container serves a real workload.

## Alternatives rejected

- **Virtual threads + Spring MVC** — rejected for the absence of backpressure, not
  for performance. Faster to write, and it fails worse under the exact conditions this
  system exists to survive.
- **Virtual threads + a manual semaphore** — reconstructs backpressure by hand at
  every boundary, which is the part Reactor already does correctly.
