# B9 — PostgreSQL

Four services, three kinds of splitting, one vector index, and the gaps worth
saying out loud.

---

## What is stored

Each service owns its own tables. No service reads another's.

```
gateway        prompt_cache           the semantic cache (answers + vectors)

metering       usage_ledger           one row per request, for billing
               tenant_usage_rollup    hourly totals, for the dashboard
               outbox                 its own outbox

audit          audit_log              who asked what, permanently

control-plane  tenants                customer records
               api_keys               keys, hashed
               saga_instances         multi-step operation state
               control_outbox         messages waiting to be published
```

---

## Three different kinds of "split", often confused

```
SHARDING       different MACHINES, different customers   the consistent-hash ring
PARTITIONING   different TABLES, one machine             PARTITION BY ...
REPLICATION    the SAME data on another machine          primary + read replicas
```

Sharding and partitioning both key on `tenant_id` here - the same idea at two
scales. Replication is a different axis entirely.

---

# Partitioning

Three tables are secretly many tables:

```
prompt_cache   ->  8 tables   PARTITION BY HASH (tenant_id)
usage_ledger   ->  4 tables   PARTITION BY HASH (tenant_id)
audit_log      ->  quarterly  PARTITION BY RANGE (occurred_at)
```

## The picture

Imagine a shop keeping every receipt in **one giant box** - 100 million of them.
A customer calls, and you dig through all of them. Every time.

Now use 8 boxes, and follow a rule when filing: **take the customer's name, run
it through a formula, and the answer is the box number.**

```
"acme"     -> box 3        (always; the formula is deterministic)
"globex"   -> box 6
"initech"  -> box 1
```

When Acme calls, run the same formula, open **box 3 only**, leave seven shut.
The search just got 8x smaller, and nothing clever happened - things were filed
properly in the first place. That formula is a **hash**.

## You still write one query

```sql
SELECT * FROM prompt_cache WHERE tenant_id = 'acme';
```

Postgres reads the `WHERE`, runs the hash, and opens `prompt_cache_p3` alone.
That is **partition pruning**, and it is free.

```
without partitioning:  search 100 million vectors
with:                  search ~12 million
```

The table comment states the purpose directly:

> *"Hash-partitioned by tenant so every ANN search is confined to a single
> partition."*

## The other rule: by time

`audit_log` splits by **when**, not by whom:

```sql
CREATE TABLE audit_log_2026q4 PARTITION OF audit_log
    FOR VALUES FROM ('2026-10-01') TO ('2027-01-01');
CREATE TABLE audit_log_2027q1 PARTITION OF audit_log
    FOR VALUES FROM ('2027-01-01') TO ('2027-04-01');
```

Because of how an audit log is read **and deleted**:

> *"Audit is queried as 'everything in this window' far more often than
> 'everything for one tenant', and time partitioning turns retention into
> dropping an old partition rather than deleting millions of rows. On an
> append-only table a mass DELETE leaves bloat that autovacuum then chases
> indefinitely."*

```
one giant table:
    DELETE FROM audit_log WHERE occurred_at < '2019-04-01';
    -> reads millions of rows, marks each deleted, runs for hours,
       then leaves bloat autovacuum chases afterwards

partitioned by quarter:
    DROP TABLE audit_log_2019q1;
    -> instant
```

**Turning a year's retention into one instant statement is the single best
reason to range-partition by time.**

> **The rule: split by whatever you search and delete by.**
> Cache: *"what does this customer have?"* -> split by customer.
> Audit: *"what happened then?"* and *"delete before then"* -> split by time.

## The safety net

```sql
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;
```

> *"Catch-all, so an event outside the provisioned windows is never rejected.
> Without it a clock skew or a late backfill fails the insert outright and the
> record is lost rather than merely misfiled. **Losing audit records to a
> partitioning detail would be indefensible.**"*

## The constraint partitioning forces

```sql
-- tenant_id must be part of the primary key: a partitioned table's unique
-- constraints have to include the partition key, because uniqueness can only
-- be enforced within a partition.
PRIMARY KEY (tenant_id, id)
```

To guarantee an id is unique across 8 tables, Postgres would have to check all
8 on every insert - exactly the work partitioning exists to avoid. So it
refuses. **The partition key must appear in every unique constraint.** Not a
workaround; a consequence.

---

# The vector search

## What a vector is

The embedding model reads a sentence and outputs **384 numbers**:

```
"how do I reset my password"   ->  [0.21, -0.08, 0.44, ...]
"I forgot my password"         ->  [0.19, -0.11, 0.41, ...]
"what is the capital of Peru"  ->  [-0.52, 0.77, -0.13, ...]
```

The first two came out close; the third is nowhere near either. **The model
turns meaning into a position.** Similar meaning, nearby position.

```sql
embedding vector(384) NOT NULL
```

In two dimensions it would be a scatter plot, and "find something similar" is
just "find the nearest dot". 384 dimensions is the same idea with more
directions - unpicturable, identical maths.

## Why an index is needed

Nearest means measuring the distance to **every** dot:

```
12 million rows in this partition  ->  12 million comparisons, every search
```

## What HNSW does

**H**ierarchical **N**avigable **S**mall **W**orld. Terrible name, simple idea:
it builds links between nearby dots, and a search *walks* toward the answer.

```
start somewhere
    -> hop to a closer neighbour
    -> hop again
    -> stop when no neighbour is closer
```

Perhaps 50 hops instead of 12 million comparisons. Like finding a house by going
to the right district, then street, then door - rather than visiting every house.

```sql
CREATE INDEX prompt_cache_embedding_hnsw
    ON prompt_cache
    USING hnsw (embedding vector_ip_ops)
    WITH (m = 16, ef_construction = 64);
```

`m = 16` - links kept per dot. More links, better paths, more memory.
`ef_construction = 64` - how hard it looks for good neighbours while building.

Both are pgvector's defaults, *"a reasonable recall/build-time balance"*.

## It is approximate, and that is fine HERE

HNSW can take a wrong turn and miss the true nearest dot.

```
HNSW misses  ->  cache miss  ->  call the AI  ->  correct answer, you just paid
```

**A miss costs money. It never produces a wrong answer.**

> The same property that makes read replicas safe here. A cache is forgiving in
> one specific way: being incomplete makes it less useful, never wrong. Build a
> bank ledger on approximate search and you are insane; build a cache on it and
> you are fine.

## Inner product, not cosine

```sql
<=>   cosine distance   - expensive
<#>   inner product     - cheap
```

> *"The vectors are stored L2-normalised, and for unit vectors inner product and
> cosine give identical ranking while inner product skips the magnitude
> division."*

Normalised means every vector has length exactly 1 - all the dots sit on a
sphere. Once that holds, the cheap measure ranks identically to the expensive
one, so the speed is free.

**And that is why `Embedding` refuses a non-unit vector at construction.** One
decision, enforced twice: once in Java, once in SQL.

## The sign trap

> *"pgvector's `<#>` returns the **NEGATIVE** inner product, so that smaller
> means closer and it behaves like a distance. Cosine similarity is therefore
> `-(a <#> b)`. **Getting this sign wrong silently returns the LEAST similar
> rows**, which looks like a bad model rather than a bad query."*

Flip one minus sign and the cache confidently returns the most unrelated answer
it can find - no error, no crash - and you would spend a week blaming the
embedding model.

## Four indexes, four jobs

```sql
prompt_cache_embedding_hnsw   (embedding vector_ip_ops)                the vector search
prompt_cache_lookup           (embedding_model, model_id, expires_at)  the filters
prompt_cache_expiry           (expires_at)                             the TTL sweep
prompt_cache_key              (tenant_id, cache_key)                   exact lookup / promotion
```

The second one's column order is reasoned:

> *"Ordered with `embedding_model` first because it is the most selective after
> partition pruning has already narrowed to one tenant."*

Comparing vectors from two different models is meaningless, so every query
filters on it - and it eliminates the most rows.

## Two columns that exist for the indexer

```sql
hit_count     BIGINT NOT NULL DEFAULT 0,
last_hit_at   TIMESTAMPTZ,
```

> *"Lets the indexer evict by unpopularity rather than purely by age, and makes
> 'which prompts actually repeat' answerable."*

Without them, eviction can only ask *"what is oldest?"*. With them it can ask
*"what is old AND unused?"* - keeping a popular 23-hour-old answer and dropping
an unused 2-hour-old one.

> And this is why the whale bucket fix mattered: hit counts were landing on the
> wrong shard for sub-sharded tenants, so most of a whale's rows showed zero
> hits - and the eviction ordering reads exactly that column.

---

# Replication

A replica holds a **full copy** of its primary, continuously updated.

**PostgreSQL does the copying.** Streaming replication is a server feature, not
code in this repo.

## What our code does is choose

```java
forWrite(tenant)  ->  always the primary
forRead(tenant)   ->  a replica, if one exists and is healthy
```

Round-robin between replicas, skipping any that lags past
`gateway.sharding.max-replica-lag`. The full argument for why reading a stale
replica is safe on this data - *lag produces a cache MISS, never a wrong answer*
- is in the Data group of [B6b1](B6b1-patterns-where-and-why.md), along with the
monitor that never started until 2026-11-16.

## Honest status

```yaml
replicas: 1        # the Postgres Deployment
```

**No replica is configured anywhere** - no `replicaUrls` in any values file. The
routing, the round-robin and the lag monitor exist and are tested; the
deployment runs one Postgres pod.

Say it accurately: *the code would read from replicas, and nothing is deployed
for it to use.*

---

# What the schema does not do

## 1. The audit partitions run out in April 2027

Two quarters are provisioned, by hand. After that every event lands in the
default partition - nothing breaks, and the entire benefit quietly evaporates:
`DROP TABLE` retention stops working and window queries scan everything.

**Nothing creates the next partition.** No `pg_partman`, no scheduled job, no
alert. The production answer is automated partition creation; what exists is two
hand-written quarters and a safety net.

## 2. metering and audit do not route by shard

```
prompt_cache   gateway   ->  routes through ShardedDatabase   yes
usage_ledger   metering  ->  one plain DatabaseClient         no
audit_log      audit     ->  one plain DatabaseClient         no
```

In a multi-shard deployment metering would write every tenant's rows to **one**
database while the gateway *reads* usage through the ring - reader and writer
looking at different machines. Fine today with one database; wrong the day
sharding is switched on. That is **A28**, and it is also why dual-write cannot
protect those tables.

## 3. The partition counts are guesses

```
prompt_cache   8
usage_ledger   4
```

Nothing records why. And changing the count later means rewriting every row into
new partitions - it is not a knob you turn.

Unlike `max-in-flight`, this one cannot be checked against a measurement,
because there isn't one. Worth saying rather than implying it was derived.

---

**Previous:** [B8 — Kafka](B8-kafka.md).
**Next:** B10 — capacity estimation.
