# B5e — Shard operations, worked with real output

Everything here was produced by running the real code on 2026-11-16, not by
estimating. Adding a shard, removing one, and moving a tenant's data without
pausing anybody.

Companion to [B5d](B5d-shard-migration-in-plain-words.md), which tells the story;
this one shows the output.

---

# Part 1 — Adding a shard

## The setup

```
before:  shard-0, shard-1, shard-2, shard-3
after:   shard-0, shard-1, shard-2, shard-3, shard-4
```

## Who moves — the real routing table

Ten customers, routed through both rings:

```
tenant        before     after      moved?
acme          shard-1    shard-1    no
globex        shard-1    shard-1    no
initech       shard-2    shard-2    no
umbrella      shard-0    shard-0    no
wayne         shard-0    shard-0    no
stark         shard-1    shard-4    YES
hooli         shard-3    shard-3    no
pied-piper    shard-1    shard-1    no
soylent       shard-1    shard-1    no
tyrell        shard-1    shard-1    no
```

**One customer moves.** The algorithm is two questions each:

```java
ShardId from = before.route(tenant);
ShardId to   = after.route(tenant);
if (!from.equals(to)) { moves.add(new Move(tenant, from, to)); }
```

### We never compute arcs

The physical picture is right - a new virtual node takes over the arc behind it,
stealing those keys from whoever owned it. But the code never works that out. It
asks the ring, which already does that internally (`ceilingEntry`), and compares
two answers.

**The arcs decide it; the code reads the result.** Far harder to get wrong, and
it costs two hash lookups per tenant.

## The percentage, and why ten customers is misleading

The planner's own summary:

```
tenants: 10 total, 1 move (10.0%), 9 stay
gains:   {shard-4=1}
losses:  {shard-1=1}
lateral: none (as a consistent hash ring guarantees)
```

10% - but that is a ten-customer sample. The same two rings, ten different groups
of ten:

```
group 1         2 / 10  = 20%
group 2         5 / 10  = 50%
group 3         2 / 10  = 20%
group 4         3 / 10  = 30%
group 5         4 / 10  = 40%
group 6         2 / 10  = 20%
group 7         2 / 10  = 20%
group 8         3 / 10  = 30%
group 9         2 / 10  = 20%
group 10        1 / 10  = 10%
```

Nothing changed except which names were sampled. Now grow the sample:

```
      10 customers:       2 moved = 20.00%
     100 customers:      26 moved = 26.00%
   1,000 customers:     215 moved = 21.50%
  10,000 customers:   2,006 moved = 20.06%
 100,000 customers:  19,866 moved = 19.87%
```

It settles on **20%**, which is what the theory says: the new shard must end up
with its fair share, `1/5`, and that share comes from the other four.

```
4 shards -> 5:   1/5 = 20% move
9 shards -> 10:  1/10 = 10% move
```

**Adding a shard gets cheaper the more shards you already have.**

> You never migrate a percentage, you migrate a **list**. The percentage is a
> sanity check: ~20% for a fifth shard is right. 80% would mean modulo was being
> used somewhere, and you would stop.

## Why not `hash % N`

```
key stays iff   k mod N == k mod (N+1)
gcd(N, N+1) = 1, so the pattern repeats every N(N+1)
both remainders equal r requires r < N
by CRT exactly one k per r in [0, N(N+1)), namely k = r

stays = N / (N(N+1)) = 1/(N+1)
moves = N/(N+1)
```

For N=4 that is **80%**, confirmed empirically at 80.01% over 200,000 keys.

```
modulo  4 -> 5:   80.0% move        ring:  20.0%
modulo  8 -> 9:   88.9% move        ring:  11.1%
modulo  9 -> 10:  90.0% move        ring:  10.0%
```

Exact inverses. The modulo version *"cannot be done online"* because almost
nothing is in the right place until the whole migration finishes.

---

# Part 2 — Removing a shard

Same tool, same comparison. Removing `shard-3`, 2,000 customers:

```
before:  4 shard(s) [shard-0, shard-1, shard-2, shard-3]
after:   3 shard(s) [shard-0, shard-1, shard-2]
removed: [shard-3]
tenants: 2000 total, 502 move (25.1%), 1498 stay
gains:   {shard-1=171, shard-0=169, shard-2=162}
losses:  {shard-3=502}
lateral: none
```

Two things worth noticing.

**Every mover comes from the same place.** The source set is exactly
`{shard-3}`. Only customers living there move; the other 1,498 are untouched.
The mirror image of adding, where every mover goes *to* one place.

**They spread across all survivors, not onto one:**

```
shard-0  169 tenants
shard-1  171 tenants
shard-2  162 tenants
```

Roughly equal thirds. Each of shard-3's vnodes disappears and whoever sat in that
arc walks forward to the next mark, which belongs to whichever shard is there.
Pinned by the test `spreadsOverTheSurvivors` - removal dumping all 502 onto one
shard would kill it.

**25% because shard-3 held a fair share of four.** Removing one of N shards moves
`1/N`.

## The safety check covers both directions

```java
if (!added.contains(move.to()) && !removed.contains(move.from())) {
    lateral.add(move);   // must never happen
}
```

A move is legal if it goes **to** a shard just added, or comes **from** a shard
just removed. Anything else - `shard-1 -> shard-2` when neither changed - means
the two rings were built differently: a typo in a shard id, or mismatched
`--virtual-nodes`. The CLI **exits 2** rather than printing a plan of pointless
copies, which stops `cli ... > plan.tsv && migrate-all ...` dead.

## The operational difference

| | adding | removing |
|---|---|---|
| target | one, the new shard | **many** - each mover has its own |
| command | one copy per tenant | `shard-migrate-all.sh`, which reads each row's own from/to |
| if you cut over early | tenants read an empty new shard; old data still exists | tenants read a survivor that has nothing yet |
| after cleanup | source still there until you delete it | **the machine goes away** - anything uncopied is gone |

Removal is the direction with no second chance. Verify, wait, then delete.

---

# Part 3 — Dual-write: migrating without pausing anyone

## The problem, with one concrete row

Acme moves from shard-1 to shard-4.

```
10:00   copy starts - reading Acme's rows out of shard-1
10:05   Acme writes a new row
10:10   copy finishes
10:12   cutover - the ring now sends Acme to shard-4
```

The 10:05 row was written to shard-1 **after the copy had read past it**. It is
not on shard-4, and from 10:12 nothing reads shard-1. The row still exists on
disk and the application can never see it again.

## With dual-write

```
09:59   dual-write ON:   acme  ->  shard-1 -> shard-4
10:00   copy starts
10:05   Acme writes  ->  shard-1 AND shard-4
10:10   copy finishes
10:12   cutover
10:13   dual-write OFF
```

The row reached shard-4 as it happened. **Acme noticed nothing** - no 503, no
pause, no retry.

## How it is built - three pieces

### 1. State in Redis

```bash
redis-cli HSET   gateway:migrating  acme  "dual:shard-1:shard-4"
redis-cli EXPIRE gateway:migrating  900
```

```
gateway:migrating          (whole key expires)
    acme    ->  "dual:shard-1:shard-4"
    globex  ->  "pause"
```

### 2. Every pod polls it

```java
Flux.interval(REFRESH_INTERVAL)        // 2 seconds
        .concatMap(tick -> refreshOnce())
```

Held in memory, so the request path costs a map lookup, not a Redis round trip.
That 2 s is why the runbook says `sleep 3` before copying.

### 3. Writes fan out

```java
Flux.fromIterable(database.forWriteBucketAll(tenant, key))
        .concatMap(client -> client.sql(INSERT_SQL)...)
```

```java
MigrationState state = migrationState.apply(tenantId);
if (state == null || !state.isDual()) {
    return List.of(forWriteBucket(tenantId, discriminator));   // normal: one
}
return List.of(clientFor(state.from()).primary(),              // migrating: two
               clientFor(state.to()).primary());
```

`concatMap`, so the **source is written first**: if the target is unreachable the
row is still durable on the shard currently serving reads.

## The hard part: reads

During the cutover, pods restart one at a time:

```
pod A (restarted)   has the NEW ring  ->  thinks acme is on shard-4
pod B (not yet)     has the OLD ring  ->  thinks acme is on shard-1
```

A read hitting pod A at 10:05, mid-copy, would ask shard-4 and **not find the
row**. For the cache that is a miss; for usage data it is a wrong answer - and
only on some pods, which is the hardest kind of bug to reproduce.

```java
private ShardId shardForRead(TenantId tenantId) {
    MigrationState state = migrationState.apply(tenantId);
    if (state != null && state.isDual()) {
        return state.from();          // ignore the ring entirely
    }
    return router.route(tenantId);
}
```

**Reads are pinned to the source for the whole window.** The source has the
historical rows *and* every row written since, so it is the only shard guaranteed
complete until the copy finishes.

Two consequences worth stating:

- **no merging.** Querying two shards and combining results is never needed,
  because one shard is always complete.
- **the rolling restart is safe for free.** Pods disagree about the ring; they do
  not disagree about this, because it comes from Redis rather than local config.

## Why the stored value carries both shard ids

The ring has no memory. It answers only *"where does acme belong now?"*:

```
before cutover:   route("acme")  ->  shard-1
after cutover:    route("acme")  ->  shard-4
```

A pod that knew only "acme is dual-writing" could derive the target but **never
the source** - and the source is the half that matters, because reads come from
it and the first write lands there.

## Why one hash, not one key per tenant

Separate keys would need `SCAN`, which walks a keyspace holding **millions of
cache entries** - every 2 seconds, on every pod. One `HGETALL` of a usually-empty
hash is constant and tiny.

## Why the TTL is on the whole key

Redis hashes have no per-field expiry, and the key-level behaviour is what you
want anyway: **one expiry ends every migration at once.** If your terminal dies,
15 minutes later nobody is paused and nobody is dual-writing. The `EXPIRE` is
re-issued on every set, so starting a second tenant extends the clock for
everyone rather than inheriting the remains of the first one's.

## Why a plain string, not JSON

```
dual:shard-1:shard-4
```

Readable in a terminal at 3am, typeable from memory if the script is unavailable,
and parsed by one `split(":", 3)` that cannot half-succeed. JSON would add a
schema and a new failure mode where valid-but-wrong-shaped input does something
unexpected.

A value nobody can parse is **counted and skipped**, never fatal:

```java
malformedEntries.increment();
log.warn("ignoring unparseable migration state for tenant {}: '{}'", ...);
```

So `duel:shard-1:shard-4` silently does nothing for that tenant, but
`gateway.migration.malformed_entries` rises and the log names the bad value.

## Who reads this data

**Writer: one operator's shell. Readers: every gateway pod.**

```
MigrationRegistry      polls it, holds it in memory
MigrationPauseFilter   asks "is this tenant PAUSED?"
ShardedDatabase        asks "where do writes go, where do reads come from?"
```

One request from Acme:

```
1. auth filter       ->  this is tenant "acme"
2. pause filter      ->  state is "dual", not "pause"  ->  let it through
3. ... pipeline, cache lookup ...
4. forRead(acme)     ->  dual  ->  read shard-1 (the source)
5. forWriteAll(acme) ->  dual  ->  write shard-1 AND shard-4
```

Each part of the value has exactly one consumer:

| stored | used by | ignored by |
|---|---|---|
| field name `acme` | both, to match the tenant | - |
| `pause` | **only the filter** | the database never fans out for a pause |
| `dual:from:to` | **only `ShardedDatabase`** | the filter lets dual-writers through |

The two modes never both act on one request: one stops traffic at the door, the
other changes where it lands.

Plus two readers off the request path: `migration-status` for the operator, and
the `gateway.migration.active_tenants` gauge for a dashboard.

## Choosing the TTL

900 s is a default for a small tenant, not a law - `--pause-ttl` overrides it,
and **it should scale with the data volume**, because the copy does.

If it expires mid-copy, both modes fail the same silent way: writes quietly go to
one shard again and nothing says so. But the two modes are not symmetric:

| | too long | too short |
|---|---|---|
| **DUAL** | writes go to both a while longer - harmless, slight cost | **the window reopens, silently** |
| **PAUSE** | the customer is refused longer than needed - bad | **the window reopens, silently** |

**For dual-write, err generously.** For pause, keep it tight but still longer
than the copy.

Pick it by timing a copy of your biggest tenant, not by guessing. Copy throughput
has never been measured on this project and depends on row size, network and
disk.

Do not confuse it with the other timer:

```
900s  TTL         Redis forgets the migration     <- scales with data volume
 60s  staleness   a pod ignores state it cannot refresh from Redis
```

The 60 s bound is about Redis being **unreachable** - 30 failed polls - and has
nothing to do with tenant size.

## Two things made impossible

```java
if (from.equals(to)) {
    throw new IllegalArgumentException("DUAL from and to must differ");
}
```

`dual:shard-1:shard-1` would quietly degrade to an ordinary single write while
the operator believed both shards were current. Blocked in the record and in the
script.

```java
if (bucketCountFor(tenantId) > 1) {
    throw new IllegalStateException("dual-write is not supported for sub-sharded tenant...");
}
```

A whale's data spans several buckets and the planner does not emit a per-bucket
source/target mapping. Writing to the wrong bucket pair and calling it replicated
is worse than refusing.

## The honest limit

Dual-write only covers writes that route through `ShardedDatabase` - today that
is `prompt_cache`, written by the gateway.

`usage_ledger` and `audit_log` are written by **metering** and **audit**, which
each hold one plain `DatabaseClient` and do not route by shard at all. They
cannot dual-write because they cannot shard-write. That is work item **A28**, and
it implies a larger latent bug: in a multi-shard deployment those services would
write every tenant's rows to one database while the gateway *reads* usage through
the ring.

```
dual-write    ->  keeps the cache warm through a migration, no downtime
pause-writes  ->  still required to protect the billing tables
```

---

**Previous:** [B5d — shard migration in plain words](B5d-shard-migration-in-plain-words.md).
**Runbook:** [shard-migration.md](../runbooks/shard-migration.md).
