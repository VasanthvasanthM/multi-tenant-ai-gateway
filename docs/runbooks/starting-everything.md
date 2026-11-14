# Starting everything, and what lives where

Two halves: the commands to bring the whole system up from nothing, and an
explanation of what each datastore actually holds and how it is sharded,
replicated and partitioned.

---

# Part 1 — Bring it all up

Order matters. Each step depends on the one above it.

## 1. Docker Desktop

```bash
# Windows: launch Docker Desktop, then wait for the daemon
until docker info >/dev/null 2>&1; do sleep 5; done
docker info --format 'cpus={{.NCPU}} mem={{.MemTotal}}'
```

Expect **6 CPUs / ~4 GB** — set in `C:\Users\<you>\.wslconfig`. Fewer means the
VM was restarted with different limits, and the load numbers will not reproduce.

## 2. The cluster

```bash
export PATH="/c/Users/vasan/AppData/Local/Microsoft/WinGet/Packages/k3d.k3d_Microsoft.Winget.Source_8wekyb3d8bbwe:$PATH"

k3d cluster list                       # already there?
k3d cluster start ai-gateway           # existing cluster
# or, from nothing:
SKIP_RAM_CHECK=1 HTTP_PORT=18080 HTTPS_PORT=18443 ./scripts/cluster-up.sh

until kubectl get nodes >/dev/null 2>&1; do sleep 8; done
kubectl get nodes
```

`cluster-up.sh` also installs MetalLB and ingress-nginx. The custom ports exist
because 8080 is usually taken on this machine.

> **If `kubectl` hangs with `TLS handshake timeout`,** k3s is wedged — it has
> happened repeatedly on this host. Check `docker logs k3d-ai-gateway-server-0`:
> handler timeouts and "Network Policy Controller heartbeat missed", or logs that
> simply stop, mean a restart:
> ```bash
> k3d cluster stop ai-gateway && sleep 10 && k3d cluster start ai-gateway
> ```

## 3. Redis and PostgreSQL

Not in the Helm chart, deliberately — see `Chart.yaml`. In production these are
managed services or operator-owned StatefulSets with their own backup story, and
bundling them invites someone to run a chart-managed database in production.

```bash
kubectl apply -f deploy/k8s/dev-infra.yaml
kubectl rollout status deploy/postgres --timeout=240s
kubectl rollout status deploy/redis    --timeout=120s
```

This creates the `aigw-postgres` Secret, a `postgres` Deployment + Service
(pgvector image), and a `redis` Deployment + Service.

**pgvector's image even with Tier 2 off:** Flyway runs
`V1__semantic_cache.sql` on every startup, which contains
`CREATE EXTENSION vector`. Plain `postgres:17` fails the migration and the
gateway never reaches Ready.

## 4. The gateway

Needs the image on the node first:

```bash
docker build -f deploy/docker/Dockerfile.jvm --build-arg MODULE=gateway \
  -t ai-gateway-local/ai-gateway-gateway:0.1.0 .

k3d image import -c ai-gateway ai-gateway-local/ai-gateway-gateway:0.1.0 \
  redis:7.4-alpine pgvector/pgvector:0.8.7-pg17-trixie

helm upgrade --install aigw deploy/helm/ai-gateway \
  -f deploy/helm/ai-gateway/values-dev.yaml \
  --set global.imageRegistry=ai-gateway-local --set global.imageTag=0.1.0 \
  --set services.gateway.resources.requests.cpu=400m \
  --set services.gateway.resources.limits.cpu=1000m \
  --set services.gateway.env.GATEWAY_RATELIMIT_CAPACITY=2000000 \
  --set services.gateway.env.GATEWAY_RATELIMIT_REFILLPERSECOND=1000000 \
  --set monitoring.serviceMonitor.enabled=true \
  --timeout 8m

kubectl rollout status deploy/aigw-ai-gateway-gateway --timeout=420s
curl -H 'Host: ai-gateway.local' http://localhost:18080/actuator/health
```

Flags that are not optional in practice:

- **`monitoring.serviceMonitor.enabled=true`** — defaults to `false`, so without
  it Prometheus scrapes nothing of ours and every dashboard renders empty with
  no error anywhere.
- **the rate-limit overrides** — the default sustains 20 rps, so any load test
  above that measures the limiter rather than the gateway.
- **`embedding.enabled=true`** if you want **Tier 2** to actually serve. Without
  it the gateway logs `EMBEDDING FALLBACK ACTIVE`, runs a lexical hashing model,
  and Tier 2 returns nothing — while looking entirely healthy. Add:

  ```bash
    --set global.imageTag=0.2.0-glibc \
    --set embedding.enabled=true \
    --set services.gateway.env.GATEWAY_CACHE_TIER2ENABLED=true \
    --set services.gateway.env.GATEWAY_CACHE_SHADOWMODE=false
  ```

  Three things that bite here:

  **The image must be glibc.** ONNX Runtime cannot load on Alpine at all — not
  with `apk add libstdc++`, because the loader and the `__memcpy_chk` family are
  glibc-only. Tag `0.2.0-glibc` is built from the current `Dockerfile.jvm`.

  **The model must be on every node** that can schedule the pod:

  ```bash
  ./scripts/fetch-model.sh                      # ~90 MB, deliberately not in git
  for n in k3d-ai-gateway-server-0 k3d-ai-gateway-agent-0; do
    docker exec "$n" mkdir -p /opt/aigw-models
    docker cp models/all-MiniLM-L6-v2 "$n:/opt/aigw-models/"
  done
  ```

  **`shadow-mode` ships `true`**, so even with everything else right Tier 2 is
  queried, logged, and never served until you set it false. That is the correct
  default — measure the false-hit rate before trusting it — but it means a
  perfectly working Tier 2 reports zero hits until you ask for it.

  Verify rather than assume:

  ```bash
  kubectl logs deploy/aigw-ai-gateway-gateway | grep "embedding service ready"
  # want: model=all-MiniLM-L6-v2 dimension=384 threads=2
  # model=hashing-trigram-v1 means the fallback is active
  ```

> **`helm upgrade` failing with a field-manager conflict on `.spec.replicas`**
> means `kubectl scale` took ownership (the load sweep does this).
> `--take-ownership` does *not* fix it. Reinstall:
> `helm uninstall aigw && helm install aigw ...`

## 5. Observability

```bash
SKIP_RAM_CHECK=1 ./scripts/observability-up.sh --profile metrics   # Prometheus, Grafana, adapter
SKIP_RAM_CHECK=1 ./scripts/observability-up.sh --profile logs      # + Loki, Alloy
helm upgrade --install tempo grafana/tempo -n monitoring \
  -f deploy/k8s/observability/tempo-values.yaml                    # + Tempo
```

The script re-upgrades the whole metrics stack each time and has repeatedly
timed out on this host. If it does, install the component directly with `helm`
as shown for Tempo. Clear a stuck release first:

```bash
helm list -n monitoring          # look for pending-upgrade
helm rollback kps -n monitoring
```

Tracing is off by default. Turn it on *after* Tempo exists — pointing it at a
Tempo that is not there makes Micrometer log a failure on every span flush:

```bash
kubectl set env deploy/aigw-ai-gateway-gateway \
  MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo.monitoring.svc:4318/v1/traces \
  MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

**Port 4318 and the `/v1/traces` path, not 4317.** Spring Boot drives the OTLP
*HTTP* exporter; 4317 is gRPC and produces `Failed to export spans … timeout`
with everything else looking correctly configured.

## 6. Port-forwards and the UIs

```bash
kubectl port-forward -n monitoring svc/kps-grafana 13000:80 &
kubectl port-forward -n monitoring svc/kps-kube-prometheus-stack-alertmanager 19093:9093 &
kubectl port-forward svc/aigw-ai-gateway-gateway 18081:8080 &

cd dashboard && VITE_GATEWAY_URL=http://localhost:18081 \
  VITE_API_KEY=sk-local-dev-acme-00000000000000000000 npx vite --port 5173
```

| URL | What |
|---|---|
| http://localhost:13000 | Grafana — **sign in `admin`/`admin`**, Viewers get no Explore menu |
| http://localhost:19093 | Alertmanager |
| http://localhost:5173 | Tenant dashboard |
| http://localhost:18080 | Gateway via ingress (needs `Host: ai-gateway.local`) |

Port-forwards tunnel through the API server, so they die whenever it hiccups.
A blank page or 502 usually means the tunnel, not the app.

## 7. Alerting

One manual step, because the webhook is a credential — see
[`alerting-setup.md`](alerting-setup.md).

## Tear down

```bash
k3d cluster stop ai-gateway     # keeps everything
./scripts/cluster-down.sh       # deletes the cluster
```

---

# Part 2 — What is stored where

## PostgreSQL

Four concerns in one engine here; in production they would likely be separate
clusters.

### `prompt_cache` — Tier 2 semantic cache

| Column | Notes |
|---|---|
| `tenant_id` | partition key AND shard key |
| `cache_key` | SHA-256 of the normalised prompt |
| `embedding` | `vector(384)` — all-MiniLM-L6-v2 |
| `embedding_model` | which model produced it |
| `prompt_text`, `response` | |
| `created_at`, `expires_at` | TTL eviction |

**Partitioning:** `PARTITION BY HASH (tenant_id)` × 8. Every query carries
`tenant_id`, so the planner prunes to one partition — a search is never
cross-tenant.

**Index:** HNSW on `embedding` with `vector_ip_ops` — inner product. That only
equals cosine similarity for unit-length vectors, which is why embeddings are
L2-normalised on write. A non-normalised vector ranks wrongly rather than
failing.

### `tenant_usage_rollup` — what the dashboard reads

Hourly per-tenant aggregates: `request_count`, `cache_hit_count`,
`prompt_tokens`, `completion_tokens`, `upstream_tokens`.
**Written only by the `metering` service** from Kafka. With metering disabled it
stays empty and the dashboard shows zeros — the API returns 200 either way.

### Ledger and audit

Per-request usage rows and an append-only audit log, written by `metering` and
`audit`. Hash-partitioned × 4. `ON CONFLICT DO NOTHING` makes replay idempotent,
which is what makes Kafka redelivery safe: 18 duplicates produced zero
double-billing in testing.

### `outbox` — control plane

Solves the dual-write problem. The control plane writes its state change **and**
an outbox row in one transaction; `OutboxRelay` polls with
`FOR UPDATE SKIP LOCKED` and publishes. Write-then-publish loses events when the
publish fails; publish-then-write bills for work never recorded.

## Sharding and replication

**Shard key is `tenantId`.** One tenant's data lives on one shard, so a Tier-2
search is never cross-shard. [ADR-0006]

**Ring:** MurmurHash3 consistent hash, 150 virtual nodes per shard — virtual
nodes are what keep the distribution even and limit how much data moves when a
shard is added. `ShardedDatabase` routes every Tier-2 read and write.

**Replication:** reads prefer a replica; writes always go to the primary.
`ReplicaLagMonitor` probes `pg_last_xact_replay_timestamp()` every 30 s and
publishes `gateway_replica_lag_seconds`. Past a threshold, reads fail over to
the primary — stale cache reads are worse than slower ones. One alert fires when
the lag metric *stops being reported*, because a missing series and a healthy
replica otherwise look identical.

**Known gap:** a whale tenant still lands on one shard. Sub-sharding on
`tenant#bucket` is designed (`SubShard`) and not built. It is not free —
splitting by prompt hash breaks Tier 2, since similar prompts hash to unrelated
buckets, so whale reads must fan out across buckets.

## Redis

| Key | Holds |
|---|---|
| `{tenant:x}:prompt:<sha256>` | Tier 1 exact-match cache |
| `{tenant:x}:bucket` | token-bucket rate limiter state (Lua, atomic) |
| `apikey:<sha256>` | API key → tenant |
| `{tenant:x}:quota` | token budget, refreshed by the control plane |

**Hash tags** (`{tenant:x}`) force one tenant's keys onto one Cluster node, so a
multi-key Lua script is valid.

**Failure mode:** `fail-open: true` on the limiter, and a Tier-1 error counts as
a miss. That is deliberate — a cache outage should not take the product down —
but it has a sharp edge: under load, timeouts turn cheap hits into upstream
calls, which deepens the queue and causes more timeouts. Throughput collapses
rather than plateauing, and the only symptom is the hit ratio falling. See
`scaling-model.md` §3b.

## Kafka

Off by default (`infrastructure.kafka.enabled: false`); the gateway must serve
with no broker.

| Topic | Producer | Consumer |
|---|---|---|
| `gateway.usage.v1` | gateway | metering |
| `gateway.audit.v1` | gateway | audit |
| `gateway.cache-write.v1` | gateway | indexer (Tier-2 writes) |

**Partition key is `tenantId`**, so a tenant's events stay ordered relative to
each other.

**Consumer settings that matter**, all in `KafkaFactory`:
`enable.auto.commit=false` (offsets acknowledged after the write, so a crash
mid-processing cannot lose records), `auto.offset.reset=earliest` (a redeployed
service must not skip what accumulated while it was down),
`CooperativeStickyAssignor` (the eager default stops the whole group on every
membership change, turning a rolling deploy into a series of stalls).

**Producer:** `acks=all` with idempotence, `max.in.flight ≤ 5` — above 5,
idempotence stops guaranteeing ordering.

**Partition count caps consumers.** Consumption is partition-ordered, so a 33rd
metering pod against 32 partitions sits idle. Repartition *before* scaling the
Deployment.

Sizing for 2 billion requests/day — brokers, partitions, shards, replicas — is
in [`scaling-model.md` §4b](../architecture/scaling-model.md).
