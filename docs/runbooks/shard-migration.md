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

## The formula, in three lines

Everything below rests on this. It is all the routing logic there is.

```
1.  position = MurmurHash3.hash64(name, seed = 0x9747b28c)
2.  ring      = for each shard, for i in 0..149:  hash64(shardId + "#" + i, seed)
3.  owner     = the first ring position >= the tenant's position
                (and if there is none, wrap to the first position of all)
```

Line 1 turns a name into a number. Line 2 stamps each shard onto the ring 150
times. Line 3 is the lookup, and in code it is one call:

```java
ring.ceilingEntry(position)                  // the first mark at or after here
  ?: ring.firstEntry()                       // ... or wrap around
```

A tenant **moves** when lines 1 and 2 are run against two different shard lists
and line 3 gives two different answers. That is the whole plan.

---

## The commands, start to finish

### 1. Get the tenant list

The authoritative list is the control plane's `tenants` table:

```bash
kubectl exec deploy/postgres -- psql -U aigw -d aigw -t -A   -c "SELECT tenant_id FROM tenants WHERE status = 'active' ORDER BY 1;"   > tenants.txt
```

If the control plane is not deployed, derive it from whatever holds per-tenant
rows — and use `DISTINCT`, because a repeated tenant would be copied twice:

```bash
kubectl exec deploy/postgres -- psql -U aigw -d aigw -t -A   -c "SELECT DISTINCT tenant_id FROM usage_ledger
      UNION SELECT DISTINCT tenant_id FROM audit_log ORDER BY 1;"   > tenants.txt
```

`-t` drops the header, `-A` drops the column padding. Without both you get
`tenant_id` and a row of dashes as if they were tenants.

### 2. Compute the plan

```bash
java -cp common/target/classes   io.vasanth.gateway.common.shard.ShardMigrationCli   --before shard-0,shard-1,shard-2,shard-3   --after  shard-0,shard-1,shard-2,shard-3,shard-4   --tsv < tenants.txt > plan.tsv
```

The summary goes to **stderr** and the plan to **stdout**, so `> plan.tsv`
captures exactly the lines the driver parses while you still see the verdict:

```
shard migration plan
  before:  4 shard(s) [shard-0, shard-1, shard-2, shard-3]
  after:   5 shard(s) [shard-0, shard-1, shard-2, shard-3, shard-4]
  added:   [shard-4]
  tenants: 7 total, 1 move (14.3%), 6 stay
  gains:   {shard-4=1}
  losses:  {shard-1=1}
  lateral: none (as a consistent hash ring guarantees)
```

```bash
$ cat plan.tsv
stark	shard-1	shard-4
```

**Read `gains` before anything else.** One key means every move lands on the new
shard. More than one key on an *add* means something is wrong.

> **Small samples lie.** With 7 tenants the percentage is noise — a removal of
> `shard-3` reported `0 move` simply because none of those 7 happened to live
> there. The same removal over 2,000 tenants gives the real shape:
>
> ```
> tenants: 2000 total, 513 move (25.7%), 1487 stay
> gains:   {shard-1=189, shard-0=174, shard-2=150}
> losses:  {shard-3=513}
> ```
>
> 25.7% is about 1/4, and the orphans spread across all three survivors rather
> than piling onto one.

### 3. Write the shard map

```bash
cat > shards.tsv <<'EOF'
shard-0	postgresql://aigw:PW@shard-0-host:5432/aigw
shard-1	postgresql://aigw:PW@shard-1-host:5432/aigw
shard-4	postgresql://aigw:PW@shard-4-host:5432/aigw
EOF
chmod 600 shards.tsv
```

A separate file, so a connection string with a password never appears on a
command line and reaches shell history.

### 4. Drive it

```bash
./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase copy
./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase verify

#  --- cut over: change the ring config, reload the gateways, soak for a day ---

./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase cleanup --confirm
```

Add `--limit 5` to the first copy run to rehearse on five tenants before
committing to thousands.

### One honest note about the lateral-move guard

`ShardMigrationCli` applies `--virtual-nodes` to **both** rings, so it cannot
produce a mismatch — it prevents the error rather than detecting it. The guard
still earns its place for the case it was written for: rings built from two
different config files, or by two services whose defaults have drifted. Running
the CLI with a mismatch is not expressible, which is the right design for a
tool, and `ShardMigrationPlanTest.catchesMismatchedVirtualNodes` covers the
case the tool cannot reach.

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

## Step 3b — the bridge from plan to execution

This step was missing in the first version of this runbook, and its absence was
the real gap: the planner said *which* tenants move, the script moved *one*
tenant, and nothing joined them. An operator would read thousands of moves off a
screen and type commands by hand — which is precisely how a tenant gets skipped,
because there is then no record of what was attempted to diff against what was
planned.

`ShardMigrationPlan.asTsv()` emits one line per move:

```
stark<TAB>shard-a<TAB>shard-b
oscorp<TAB>shard-a<TAB>shard-b
```

Tab-separated, because a tenant id cannot contain a tab (`plan-check` restricts
them to `[A-Za-z0-9._-]`), so there is no quoting to get wrong and
`while read -r tenant from to` parses it with no tooling.

It emits **nothing for unmoved tenants**. A file listing every tenant invites a
driver to loop over all of them.

Connection strings live in a second file, so passwords never reach shell
history:

```
shard-a<TAB>postgresql://aigw:pw@shard-a-host:5432/aigw
shard-b<TAB>postgresql://aigw:pw@shard-b-host:5432/aigw
```

Then `scripts/shard-migrate-all.sh` drives the whole plan:

```bash
./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase copy
./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase verify
#  ... cutover, soak ...
./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase cleanup --confirm
```

### Phases are separate runs, deliberately

Copy **every** tenant, then verify **every** tenant, then cut over, then clean
up. Running copy-verify-cleanup per tenant would delete the first tenant's
source rows before the last tenant had even been copied — so a failure halfway
leaves some tenants migrated, some not, and some with no source to go back to.

### It validates the whole plan before touching anything

A missing connection string discovered at tenant 2,000 of 3,904 leaves a
half-migrated system. So every shard the plan references is resolved first:

```
$ ./shard-migrate-all.sh --plan bad.tsv --shards shards.tsv --phase copy
FAIL shard(s) in the plan have no URL in shards.tsv: shard-z
exit code 1
```

Nothing was copied.

### Verified end to end, four tenants on one shard

`stark`, `oscorp`, `acme` and `wayne` on `shard_a`, with a plan moving the first
two:

```
==> 2 shard(s) known
 ok  plan validated: 2 move(s), every shard resolvable
==> phase: copy

[1/2] stark  shard-a -> shard-b
            usage_ledger   source 120      target 120
            audit_log      source 60       target 60
[2/2] oscorp  shard-a -> shard-b
            usage_ledger   source 120      target 120
            audit_log      source 60       target 60

 ok  copy: 2 tenant(s) in 2s
      next: --phase verify (every tenant, before any cutover)
```

Afterwards, only the planned tenants had moved:

```
shard_a:  acme | 120      wayne | 120      <- untouched, not in the plan
shard_b:  oscorp | 120    stark | 120
```

### It stops on the first failure, and names it

One value was corrupted for `stark` — the **first** tenant in the plan:

```
[1/2] stark  shard-a -> shard-b
      FAIL usage_ledger   checksum differs (120 rows each)
      do NOT cut over. To recover:
        reset-target --tenant stark ...
        copy         --tenant stark ...
        verify       --tenant stark ...

FAIL stopped at tenant 'stark' (1 of 2). 0 completed. Fix this tenant, then
     re-run the same phase - completed tenants are skipped safely because copy
     is idempotent.
exit code 1
```

**It never reached `oscorp`.** That matters: continuing past a failure would end
with an unknown subset of tenants in an unknown state. Stopping with the tenant
named means fixing one thing and re-running the same phase, and because `copy`
is idempotent the tenants already done are no-ops.

Recovery and completion, verified:

```
--- recover stark ---        reset-target, then copy
--- re-run verify phase ---  ok  verify: 2 tenant(s) in 2s
--- cleanup phase ---        ok  cleanup: 2 tenant(s) in 3s
```

Final state, with the source cleaned:

```
shard_a:  acme | 120      wayne | 120
shard_b:  oscorp | 120    stark | 120
```

And `cleanup` refuses to run across a whole plan without consent:

```
FAIL cleanup deletes source rows for EVERY tenant in the plan. Re-run with --confirm.
```

---

## Steps 4–8 — the per-tenant commands

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
