# Adding or removing a shard

The full procedure, the commands, and the results of running it against two real
databases.

Re-sharding is the operation most likely to lose data quietly. The failure is
invisible: a tenant whose rows were left behind reads as a customer with no
history, not as an error. Everything below exists to make that impossible to do
by accident.

---

## The flow

```
 1. PLAN       compute which tenants move. No I/O, safe to run against prod config
 2. CHECK      assert the plan has the shape a hash ring guarantees
 3. PREPARE    create the new shard, run migrations on it
 4. COPY       per tenant, source -> target. Source keeps serving throughout
 5. VERIFY     row counts AND content checksums. Read-only. Gates the cutover
 6. CUTOVER    change the ring config, reload the gateways
 7. SOAK       watch for a day. Nothing has been deleted yet
 8. CLEANUP    delete from the source, per tenant, only once trusted
```

**Nothing is deleted before step 8.** An abandoned migration costs disk, never
data.

---

## Step 1 — plan

`ShardMigrationPlan` routes every tenant through both rings and keeps the ones
that disagree. It performs no I/O, so it is safe to run against production ring
configuration.

```java
ConsistentHashRing before = ConsistentHashRing.of(currentShards);
ConsistentHashRing after  = before.withShard(ShardId.of("shard-4"));

ShardMigrationPlan plan = ShardMigrationPlan.between(before, after, allTenants);
System.out.println(plan.summary());
```

```
shard migration plan
  before:  4 shard(s) [shard-0, shard-1, shard-2, shard-3]
  after:   5 shard(s) [shard-0, shard-1, shard-2, shard-3, shard-4]
  added:   [shard-4]
  tenants: 20000 total, 3981 move (19.9%), 16019 stay
  gains:   {shard-4=3981}
  losses:  {shard-1=1032, shard-0=1011, shard-2=976, shard-3=962}
  lateral: none (as a consistent hash ring guarantees)
```

Two things to read in that output:

- **`gains` has exactly one key.** Every move lands on the new shard. Nothing
  moves between two shards that already existed.
- **19.9%**, which is 1/5 for five shards. Compare `hash % N`, where changing
  the divisor would move about 80%.

### The plan is deterministic

Sorted by tenant, so two runs over the same set produce byte-identical output
even if the tenant query returns rows in a different order. A plan that changes
between runs cannot be diffed against the one that was approved.

---

## Step 2 — check the plan before trusting it

```java
plan.assertNoLateralMoves();
```

A consistent hash ring **cannot** move a tenant from one pre-existing shard to
another. If the plan contains such a move, the two rings were not built the same
way — different virtual-node count, different seed, or a shard id differing by
case or whitespace.

This matters because the symptom is not an error. It is a migration that looks
ordinary and touches multiples more data than intended:

```
honest change   (150 vnodes both sides):  3,981 tenants move
mismatched      (150 -> 64 vnodes):       9,000+ tenants move
```

That is measured in `ShardMigrationPlanTest.mismatchMovesFarMore`. Without the
guard, the mistake is only visible as "this is taking much longer than expected".

Also check the tenant list itself:

```bash
./scripts/shard-migrate.sh plan-check --tenants-file tenants.txt
#  ok  4 tenant(s), no duplicates, all safely quotable
```

It refuses an empty file (the difference between "migrated 400 tenants" and
"migrated 0 and reported success"), duplicates (a tenant copied twice), and any
character outside `[A-Za-z0-9._-]`, because those values are interpolated into
SQL.

---

## Steps 4–8 — the commands

```bash
export FROM='postgresql://aigw:PW@shard-a-host:5432/aigw'
export TO='postgresql://aigw:PW@shard-e-host:5432/aigw'

./scripts/shard-migrate.sh copy    --tenant acme --from "$FROM" --to "$TO"
./scripts/shard-migrate.sh verify  --tenant acme --from "$FROM" --to "$TO"

#  ... cutover: change the ring config, reload the gateways, soak for a day ...

./scripts/shard-migrate.sh cleanup --tenant acme --from "$FROM" --to "$TO" --confirm
```

One tenant at a time, so a problem affects one customer rather than everybody.

### What `copy` does

Streams `COPY ... TO STDOUT` from the source into a temp table on the target,
then `INSERT ... ON CONFLICT DO NOTHING`.

- **Streamed through a pipe**, not a temp file. At a few GiB per tenant a file
  needs disk the migration host may not have, and a half-written file looks
  exactly like a complete one.
- **`ON CONFLICT DO NOTHING`** makes a retry after a network drop safe: rows that
  already arrived are skipped rather than duplicated.

### What `verify` does — and why counts are not enough

Row counts **and** an md5 checksum over every row:

```sql
SELECT md5(string_agg(row_md5, '' ORDER BY row_md5))
  FROM (SELECT md5(t::text) AS row_md5 FROM usage_ledger t WHERE tenant_id = 'acme') s;
```

`ORDER BY` is essential — md5 of a concatenation depends on order, and two
databases have no reason to return rows in the same physical order.

Counts alone pass when the right **number** of **wrong** rows arrived: a
truncated TEXT column, a timestamp that lost its zone, a numeric that
round-tripped through a float. The checksum catches all of those. Proof below.

### `prompt_cache` is deliberately not copied

It is a cache. Dropping it costs one day of extra provider calls as it refills,
and copying 1,536 bytes of vector per row to avoid that is the wrong trade. The
new shard finds nothing, reports a miss, goes upstream, and stores the answer.

`--include-cache` overrides it if you want the hit ratio preserved across the
cutover.

---

## Verified against two real databases

Run on 2026-11-15 against two PostgreSQL databases, with `acme` (500 ledger +
300 audit rows) and `globex` (80 rows) on the source.

**Copy and verify:**

```
==> copying tenant 'acme'
      usage_ledger   source 500      target 500
      audit_log      source 300      target 300
 ok  copy finished - now run: verify

==> verifying tenant 'acme'
 ok  usage_ledger   500 rows, checksum a256bb4e05c1
 ok  audit_log      300 rows, checksum 23b1b479f3ac
 ok  verified - safe to cut over.
```

**Then one value was corrupted on the target**, leaving the row count identical:

```sql
UPDATE usage_ledger SET prompt_tokens = prompt_tokens + 1
 WHERE tenant_id='acme' AND request_id='req-250';
```

```
==> verifying tenant 'acme'
FAIL usage_ledger   checksum differs (500 rows each)
      source a256bb4e05c1fc105c139c8a33c9eb72
      target e2d6a0532096f5bfc43cd59f816548ed
 ok  audit_log      300 rows, checksum 23b1b479f3ac
FAIL do NOT cut over.
exit code 1
```

**500 rows on both sides.** A count-only check would have passed and the cutover
would have proceeded with one silently wrong billing row.

**Tenant isolation held** — only `acme` was copied:

```
shard_b:  usage_ledger | acme   | 500
          audit_log    | acme   | 300
shard_a:  usage_ledger | globex |  80     <- untouched throughout
```

---

## A retry does NOT repair corruption

This was found by testing, and it corrected the script's own advice.

`ON CONFLICT DO NOTHING` is what makes a retry safe after a network drop — and
it is exactly what makes a retry **useless** against a row that is already
present but wrong. The conflicting row is skipped, so the wrong value survives
any number of copies:

```
--- retry the copy over the corrupted target ---
warn audit_log: target already has 300 row(s), copy is idempotent, continuing
 ok  copy finished

==> verifying
FAIL usage_ledger   checksum differs (500 rows each)      <- still wrong
```

The script used to say *"re-run copy, then verify again"*, which would have left
an operator looping forever. It now prints the recovery that works:

```bash
./scripts/shard-migrate.sh reset-target --tenant acme --to "$TO" --confirm
./scripts/shard-migrate.sh copy         --tenant acme --from "$FROM" --to "$TO"
./scripts/shard-migrate.sh verify       --tenant acme --from "$FROM" --to "$TO"
```

Verified end to end:

```
==> emptying target for tenant 'acme'
      usage_ledger   deleted 500 row(s)
      audit_log      deleted 300 row(s)
 ok  target reset

      usage_ledger   source 500      target 500
 ok  usage_ledger   500 rows, checksum a256bb4e05c1     <- matches again
 ok  verified - safe to cut over.
```

`reset-target` is a separate command rather than a `--force` flag on `copy`,
because it deletes rows on the shard traffic is about to be pointed **at** — and
if the cutover has already happened, those rows are live.

---

## The guards, and what each one is for

| guard | prevents |
|---|---|
| `cleanup` requires `--confirm` | deleting the source by habit |
| `cleanup` **re-verifies** first | cleanup may be hours later; the only irreversible step must not trust a result from a previous shell |
| `reset-target` requires `--confirm` | deleting rows that may already be live |
| `copy` fails if the table is missing on the target | copying into a shard whose migrations were never run |
| `plan-check` rejects odd characters | SQL interpolation |
| `assertNoLateralMoves` | two rings built differently, moving multiples more data |
| `ON_ERROR_STOP=1` everywhere | a mid-script SQL error reporting success |
| `psql -X` | a developer's `~/.psqlrc` formatting breaking the parsing |

Verified:

```
$ shard-migrate.sh cleanup --tenant globex --from "$FROM"
FAIL cleanup deletes rows from the SOURCE. Re-run with --confirm once the
     cutover is trusted.
exit code 1
```

---

## The part that is still missing

**Writes arriving during the copy.** This script is the data-movement half, not
the traffic half. Between `copy` and `cutover`, new rows keep landing on the
source and are not copied.

Two standard solutions, neither implemented here:

- **Dual-write** — during the window, write to both shards. The switch is then
  safe because both are current.
- **Read from both** — during the window, query old and new and merge. Slower,
  but nothing can be missed.

A third option is viable for small tenants: pause that tenant's writes for the
seconds the copy takes. At per-tenant granularity this affects one customer, and
`429` with `Retry-After` is a response clients already handle.

**Until one of those exists, this procedure is correct for a tenant whose writes
can be briefly paused, and lossy for one whose cannot.** Copy, verify, cut over,
then copy again for the window — and verify again — is a workable manual
substitute.

---

## Testing it yourself

Two databases in one PostgreSQL instance are enough to exercise everything —
no second server needed:

```bash
kubectl exec deploy/postgres -- psql -U aigw -d postgres \
  -c "CREATE DATABASE shard_a;" -c "CREATE DATABASE shard_b;"

# create the schema on both, seed shard_a, then:
PW=$(kubectl get secret aigw-postgres -o jsonpath='{.data.password}' | base64 -d)
POD=$(kubectl get pods --no-headers | grep postgres | awk '{print $1}')
kubectl cp scripts/shard-migrate.sh "$POD:/tmp/shard-migrate.sh"

kubectl exec "$POD" -- bash -c "
  export FROM='postgresql://aigw:$PW@localhost:5432/shard_a'
  export TO='postgresql://aigw:$PW@localhost:5432/shard_b'
  /tmp/shard-migrate.sh copy   --tenant acme --from \"\$FROM\" --to \"\$TO\"
  /tmp/shard-migrate.sh verify --tenant acme --from \"\$FROM\" --to \"\$TO\""
```

The planner needs no database at all:

```bash
./mvnw -pl common test -Dtest=ShardMigrationPlanTest
# Tests run: 13, Failures: 0
```

---

## Removing a shard

The same procedure, with the plan reversed:

```java
ConsistentHashRing after = before.withoutShard(ShardId.of("shard-3"));
```

The plan then shows only that shard's tenants moving, spread over the survivors:

```
  removed: [shard-3]
  tenants: 20000 total, 5012 move (25.1%), 14988 stay
  gains:   {shard-0=1689, shard-1=1673, shard-2=1650}
  losses:  {shard-3=5012}
  lateral: none
```

`gains` spread across every survivor rather than piling onto one is what makes
shrinking a cluster safe — tested in
`ShardMigrationPlanTest.spreadsOverTheSurvivors`. Each moving tenant is then
copied, verified and cut over exactly as above, and only when every one is done
is the shard decommissioned.
