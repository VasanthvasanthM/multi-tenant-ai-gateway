# Architecture Decision Records

One record per decision that would be expensive to reverse, or that a reader would
otherwise assume was an accident.

Each ADR states the context, the decision, what it costs, and what was rejected. An
ADR is **not** updated when the decision changes — a new ADR supersedes it, so the
reasoning at the time stays readable.

| ADR | Decision | Drives |
|---|---|---|
| [0001](0001-webflux-over-virtual-threads.md) | Spring WebFlux rather than virtual threads | Backpressure |
| [0002](0002-in-process-embeddings.md) | Embeddings computed in-process | Tier-2 latency budget |
| [0003](0003-kafka-beside-request-path.md) | Kafka beside the request path, never inside | SSE, latency |
| [0004](0004-no-pgbouncer.md) | No PgBouncer in front of R2DBC | Prepared statements |
| [0005](0005-token-bucket.md) | Token bucket rather than leaky bucket | Bursty clients |
| [0006](0006-shard-key-is-tenant-id.md) | Shard key is `tenantId`, consistent hash | No cross-shard ANN |
| [0007](0007-read-replicas-are-safe.md) | Read replicas acceptable for cache reads | Read scaling |
| [0008](0008-outbox-only-off-hot-path.md) | Transactional outbox off the hot path only | Latency vs durability |
| [0009](0009-orchestrated-saga.md) | Orchestrated saga, not choreography | Operability |
| [0010](0010-no-synchronous-inter-service-calls.md) | Zero synchronous inter-service calls on the hot path | Blast radius |
| [0011](0011-mock-provider-default.md) | `MockLlmProvider` default, Ollama off | Benchmark validity |
| [0012](0012-least-connections.md) | ingress-nginx least-connections, not round-robin | Latency spread |
| [0013](0013-usl-not-headline-rps.md) | Scaling proven by USL fit, not a headline RPS number | Honesty |

## Deviations from the source blueprint

Five places where the implementation departs from the document it was built from.
Each is recorded above:

1. Embedding source was unspecified -> in-process ([0002](0002-in-process-embeddings.md))
2. Kafka drawn inside the request path -> moved beside it ([0003](0003-kafka-beside-request-path.md))
3. PgBouncer in front of R2DBC -> removed ([0004](0004-no-pgbouncer.md))
4. "3 x 5,000 = 15,000 RPS" -> per-node curve plus explicit extrapolation ([0013](0013-usl-not-headline-rps.md))
5. Leaky bucket -> token bucket ([0005](0005-token-bucket.md))
