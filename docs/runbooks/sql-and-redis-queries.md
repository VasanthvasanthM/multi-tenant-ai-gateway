# SQL and Redis — how to connect and what to run

How to get a shell on each datastore, the queries worth knowing, and the SQL the
application itself runs.

---

## Connecting

### PostgreSQL

```bash
# one-off query
kubectl exec deploy/postgres -- psql -U aigw -d aigw -c "SELECT 1;"

# interactive shell
kubectl exec -it deploy/postgres -- psql -U aigw -d aigw
```

Credentials come from the `aigw-postgres` Secret created by
`deploy/k8s/dev-infra.yaml` — user `aigw`, database `aigw`.

> **On Git Bash (Windows)**, prefix any command containing an absolute path
> inside the container with `MSYS_NO_PATHCONV=1`, or the shell rewrites
> `/var/lib/...` into `C:/Program Files/Git/var/lib/...` before kubectl sees it
> and the command fails with a confusing "No such file or directory".

Multi-line SQL through `kubectl exec` is fragile — the shell mangles `||`,
newlines and quotes. Keep statements on **one line**, or pipe a file:

```bash
kubectl exec -i deploy/postgres -- psql -U aigw -d aigw < myquery.sql
```

### Redis

```bash
kubectl exec deploy/redis -- redis-cli PING
kubectl exec -it deploy/redis -- redis-cli        # interactive
```

---

## The schema

```sql
\dt                                    -- list tables
\d+ prompt_cache                       -- one table, with partitions
\di                                    -- indexes
SELECT * FROM flyway_schema_history ORDER BY installed_rank;   -- migrations applied
```

| Table | Owner service | Holds |
|---|---|---|
| `prompt_cache` (+ `_p0`…`_p7`) | gateway | Tier-2 semantic cache |
| `usage_ledger` (+ partitions) | metering | one row per request |
| `tenant_usage_rollup` | metering | hourly aggregates — what the dashboard reads |
| `audit_log` (+ partitions) | audit | append-only audit trail |
| `tenants`, `api_keys` | control-plane | tenant and key lifecycle |
| `outbox`, `control_outbox` | control-plane | transactional outbox |
| `saga_instances` | control-plane | saga orchestration state |

**Only `prompt_cache` exists in a gateway-only deployment.** The others ship in
the migrations of their own services, so with `values-dev.yaml` (gateway only)
`\dt` shows `prompt_cache*` and `flyway_schema_history` and nothing else. That
is why the dashboard reads zeros — `tenant_usage_rollup` is not merely empty, it
is absent, and the API still returns 200.

---

## Tier 2 — the semantic cache

### What the application runs

Lookup (`Tier2SemanticCache`):

```sql
SELECT id, cache_key, response, model_id, prompt_tokens, completion_tokens,
       provider, created_at, prompt_text,
       -(embedding <#> CAST(:query AS vector)) AS similarity
  FROM prompt_cache
 WHERE tenant_id = :tenantId
   AND model_id = :modelId
   AND embedding_model = :embeddingModel
   AND expires_at > now()
 ORDER BY embedding <#> CAST(:query AS vector)
 LIMIT 1;
```

Three things in there are load-bearing:

- **`<#>` is negative inner product.** Smaller is closer, so `ORDER BY` ascending
  gives the nearest row — and the `-(…)` flips it back to a similarity for the
  threshold comparison.
- **`CAST(:query AS vector)`, not `:query::vector`.** Spring's named-parameter
  parser treats `::` as a parameter marker and mangles the statement.
- **`tenant_id` first in the WHERE.** It prunes to one hash partition, so a
  search is never cross-tenant.

### Inspecting it by hand

```sql
-- how much is cached, per tenant
SELECT tenant_id, count(*), min(created_at), max(expires_at)
  FROM prompt_cache GROUP BY tenant_id;

-- partition spread: should be roughly even
SELECT tableoid::regclass AS partition, count(*)
  FROM prompt_cache GROUP BY 1 ORDER BY 1;

-- expired but not yet evicted
SELECT count(*) FROM prompt_cache WHERE expires_at <= now();

-- is the HNSW index being used? expect "Index Scan using prompt_cache_embedding_hnsw"
EXPLAIN ANALYZE
SELECT id FROM prompt_cache
 WHERE tenant_id = 'acme'
 ORDER BY embedding <#> (SELECT embedding FROM prompt_cache LIMIT 1)
 LIMIT 1;

-- index sizes; the HNSW index must stay in RAM to be fast
SELECT indexrelname, pg_size_pretty(pg_relation_size(indexrelid))
  FROM pg_stat_user_indexes WHERE relname LIKE 'prompt_cache%';
```

> A **Seq Scan** in that EXPLAIN means the HNSW index is not being used —
> usually a mismatched operator class. The index is `vector_ip_ops`, so the
> query must use `<#>`. Using `<=>` (cosine) silently falls back to a scan.

---

## Usage and billing

```sql
-- what the dashboard reads
SELECT * FROM tenant_usage_rollup
 WHERE tenant_id = 'acme' ORDER BY period_hour DESC LIMIT 24;

-- hit ratio over the window
SELECT sum(cache_hit_count)::float / nullif(sum(request_count), 0) AS hit_ratio
  FROM tenant_usage_rollup
 WHERE tenant_id = 'acme' AND period_hour > now() - interval '24 hours';

-- does the rollup agree with the ledger? they should match
SELECT (SELECT sum(request_count) FROM tenant_usage_rollup WHERE tenant_id='acme') AS rollup,
       (SELECT count(*)            FROM usage_ledger       WHERE tenant_id='acme') AS ledger;
```

`nullif(…, 0)` matters: an idle window divides by zero, and a NULL reads as "no
data" rather than crashing the query.

### Seeding the dashboard for a demo

Only when nothing real has been aggregated. **Derive hits from requests** — a
naive `random()` for each column produces more hits than requests and a >100%
hit ratio:

```sql
INSERT INTO tenant_usage_rollup
  (tenant_id, period_hour, request_count, cache_hit_count,
   prompt_tokens, completion_tokens, upstream_tokens)
SELECT 'acme', period_hour, req, hits,
       (req*108)::bigint, (req*65)::bigint, ((req-hits)*173)::bigint
  FROM (SELECT date_trunc('hour', now()) - make_interval(hours => g) AS period_hour,
               (420 + random()*260)::bigint AS req
          FROM generate_series(0,47) AS g) base,
  LATERAL (SELECT (base.req * (0.58 + random()*0.22))::bigint AS hits) h
ON CONFLICT DO NOTHING;

-- remove it afterwards
DELETE FROM tenant_usage_rollup WHERE tenant_id = 'acme';
```

---

## Outbox and saga

```sql
-- backlog. A rising number means the relay is not keeping up or is dead.
SELECT count(*) FROM outbox WHERE published_at IS NULL;

-- stuck rows, near the MAX_ATTEMPTS = 10 cutoff
SELECT id, topic, attempts, last_error FROM outbox
 WHERE published_at IS NULL AND attempts > 3 ORDER BY attempts DESC LIMIT 10;

-- sagas that never finished
SELECT id, state, created_at FROM saga_instances
 WHERE state NOT IN ('COMPLETED','COMPENSATED') ORDER BY created_at LIMIT 10;
```

The relay claims work with `FOR UPDATE SKIP LOCKED`, which is what lets several
replicas poll the same table without blocking each other or double-publishing.

---

## Redis

```bash
kubectl exec deploy/redis -- redis-cli DBSIZE
kubectl exec deploy/redis -- redis-cli INFO memory  | grep -E "used_memory_human|maxmemory_human"
kubectl exec deploy/redis -- redis-cli INFO stats   | grep -E "keyspace_(hits|misses)|evicted_keys"
```

```bash
# SCAN, never KEYS - KEYS blocks the single-threaded server
kubectl exec deploy/redis -- redis-cli --scan --pattern '{tenant:acme}*' | head -20

# one cached entry, and its TTL
kubectl exec deploy/redis -- redis-cli GET '{tenant:acme}:prompt:<sha256>'
kubectl exec deploy/redis -- redis-cli TTL '{tenant:acme}:prompt:<sha256>'

# rate-limiter bucket
kubectl exec deploy/redis -- redis-cli HGETALL '{tenant:acme}:bucket'

# clear the cache between load runs
kubectl exec deploy/redis -- redis-cli FLUSHALL
```

`evicted_keys > 0` means the working set outgrew `maxmemory` — the hit rate
falls and upstream cost rises. That is a cost problem, not an outage, which is
the right direction to fail.

> **The braces are a hash tag**, not decoration: `{tenant:acme}` forces every key
> for that tenant onto one Cluster node, which is what makes the rate-limiter's
> multi-key Lua script legal.

---

## Migrations

Flyway runs on service startup; there is no manual migrate step.

```sql
SELECT version, description, success, installed_on
  FROM flyway_schema_history ORDER BY installed_rank;
```

`success = false` means a failed migration — the service will not start until
the row is repaired or removed.

Each service owns its own migrations:

| Path | Creates |
|---|---|
| `gateway/src/main/resources/db/migration/V1__semantic_cache.sql` | `prompt_cache`, HNSW index, `CREATE EXTENSION vector` |
| `metering/.../V1__metering.sql` | `usage_ledger`, `tenant_usage_rollup`, outbox |
| `audit/.../V1__audit.sql` | `audit_log` |
| `control-plane/.../V1__control_plane.sql` | `tenants`, `api_keys`, `control_outbox`, `saga_instances` |

Tests apply the real migration rather than a hand-written DDL — with
`baselineVersion("0")`, because Flyway's default of `1` marks V1 as already
applied and silently skips it, leaving a healthy-looking start with no tables.
