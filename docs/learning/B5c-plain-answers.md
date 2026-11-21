# B5c — Plain answers to six questions

Simple English. Short sentences. Including one correction to B5b.

---

## 1. `294 × 2 vCPU ÷ 12.8 = 46 nodes`

Three steps. Here they are one at a time.

### Step 1 — how much CPU do all the pods need?

```
294 pods × 2 vCPU each = 588 vCPU
```

Each pod is allowed 2 vCPU. There are 294 of them. So we need 588 vCPU in total.

### Step 2 — how much CPU can one server actually give us?

A server has 16 vCPU. **But we cannot use all 16.**

Kubernetes itself runs things on every server:

| what | roughly |
|---|---|
| kubelet (the Kubernetes agent) | 0.5 vCPU |
| the container runtime | 0.3 vCPU |
| log shipper, metrics agent, network plugin (DaemonSets) | 1.5 vCPU |
| the operating system | 0.9 vCPU |
| **total overhead** | **~3.2 vCPU** |

```
16 vCPU − 3.2 overhead = 12.8 vCPU left for our pods
```

That is where `12.8` comes from. It is **80% of 16**. The rule of thumb is that
you lose about 20% of a node to the platform.

### Step 3 — how many servers?

```
588 vCPU needed ÷ 12.8 vCPU per server = 45.9  →  46 servers
```

You cannot buy 45.9 servers, so round up to 46.

### Why we check memory too

A pod needs CPU **and** memory. We must size for whichever runs out first.

```
Memory: 294 pods × 2 GiB = 588 GiB needed
        588 GiB ÷ 56 GiB usable per node = 10.5 → 11 nodes
```

So: **CPU needs 46 nodes, memory needs 11 nodes.** We buy **46**, because that
satisfies both. CPU is the binding constraint, and the memory is just along for
the ride.

---

## 2. Where 381 GiB comes from

It is one multiplication followed by two divisions. The divisions are the part
that looks like magic.

### The multiplication

```
200,000,000 entries  ×  2 KiB each  =  400,000,000 KiB
```

### Converting KiB to GiB

Computer sizes go up in steps of **1024**, not 1000.

```
400,000,000 KiB  ÷ 1024  =  390,625 MiB
     390,625 MiB ÷ 1024  =  381.47 GiB
```

So **381 GiB**. That is all it is — the two divisions by 1024 are what turn
"400 million kilobytes" into "381 gigabytes".

### Where the two inputs came from

**200,000,000 entries:**

```
2,000,000,000 requests per day
× 10% that are NEW questions (assumed)
= 200,000,000 things to store
```

The other 90% are repeats. A repeat does not store anything new — it finds an
entry that already exists. That is the whole point of a cache.

**2 KiB per entry:** this is a guess, and B5b takes it apart. The real example
measures 136 bytes. 2 KiB assumes the average answer is about 1,900 characters.

**And it is easy to check for real:**

```bash
kubectl exec deploy/redis -- redis-cli INFO memory | grep used_memory:
kubectl exec deploy/redis -- redis-cli DBSIZE
# used_memory ÷ DBSIZE = the true average size
```

If the real average is 1 KiB, the whole Redis fleet halves. Logged as work item
A16.

---

## 3. WAL streaming — I was wrong, and here is what is actually true

### The correction

I said "WAL streams to both replicas → they replay within milliseconds" as if it
were something built here. **It is not. I did not write that logic, and nothing
in this repository does.**

I checked:

```bash
grep -r "wal_level|primary_conninfo|hot_standby|pg_basebackup" .
# nothing
```

**There is no replication configuration anywhere in this project.** And the
deployment that actually runs has **one single PostgreSQL pod** —
`deploy/k8s/dev-infra.yaml`, `replicas: 1`. No primary. No replicas. Just one
database.

### Why there is no logic to write

**WAL streaming is a PostgreSQL feature, not application code.** You do not
program it; you configure the database server and it does it itself.

- `WAL` = Write-Ahead Log. PostgreSQL writes every change to this log file
  *before* changing the data. It does this anyway, for crash recovery.
- Replication means: a second PostgreSQL connects to the first and asks "send me
  your WAL as you write it." It then replays those changes onto its own copy.
- That is set up with server settings (`wal_level`, `primary_conninfo`) and
  usually by an operator like CloudNativePG, or by a managed service like AWS
  RDS where you tick a box.

So **no Java code is involved on either side.** The application just connects to
whichever server it was told to.

### What this project DOES contain

Two pieces of real code that *assume* replicas exist:

**`ShardedDatabase`** — decides which database connection to use:

> *"Picks the DatabaseClient for a tenant, and for reads prefers a replica.
> Writes go to the primary because a write to a replica simply fails."*

It round-robins across a shard's replicas using an atomic counter. The comment
explains why not random: *"with two replicas, random selection gives an uneven
split often enough to look like a replica problem."*

**`ReplicaLagMonitor`** — measures how far behind a replica is:

```sql
EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp()))
```

It runs every 30 seconds and publishes a gauge. It has a nice detail: that
function returns `null` on a primary, and a null would make the metric
**disappear** from Prometheus — and a missing metric cannot trigger an alert. So
it handles that case deliberately.

### So what is the honest status?

| thing | status |
|---|---|
| the routing code (reads → replica, writes → primary) | **written and tested** |
| the lag monitoring | **written and tested** |
| actual replicas running anywhere | **none** |
| replication configuration | **does not exist in this repo** |
| 4 shards × 3 instances | **a capacity plan on paper** |

The code is *ready* for replicas. There have never been any. That is a normal
state for a project at this stage, but calling it "how it works now" was wrong
of me — it is how it would work.

---

## 4. The three background services, in simple words

### control-plane — the admin desk

**What it does:** creates customers, issues API keys, cancels API keys, sets
spending limits.

**Think of it as:** the front desk of a hotel. It checks people in, gives out
room keys, and cancels keys when someone leaves.

**Why it is separate:** this work happens rarely. You create a customer once,
ever. The gateway handles 92,000 requests a second. Putting them together would
mean running 294 copies of a front desk that serves one person a week.

**If it stops:** you cannot create or cancel keys. **Everyone already using the
system carries on perfectly**, because the gateway reads keys from Redis, not
from the control plane. It never even talks to it.

**Only 3 copies** — and those 3 are only so it survives one of them dying, not
for speed.

### metering — the meter reader

**What it does:** counts what every customer used, so you can bill them.

**Think of it as:** the electricity meter on a house. It does not supply the
electricity — it just records how much was used.

**How it works:**

```
every request → a little note posted to Kafka
                        ↓
                    metering reads the notes
                        ↓
        ┌───────────────┴────────────────┐
        ▼                                ▼
  usage_ledger                   tenant_usage_rollup
  one row per request            hourly totals per customer
  (the proof for an invoice)     (what the dashboard shows)
```

**Why two tables:** the ledger is the evidence — "here is every single request
we are charging you for." But a dashboard asking "how many requests this month?"
against 6 billion rows would take minutes. So the rollup stores one row per
customer per hour. A month becomes 720 rows instead of 180 million.

**If it stops:** requests are fine. The notes **wait safely in Kafka**. When
metering comes back it reads them all and catches up. **Nothing is lost** — the
dashboard is just out of date for a while.

The only real deadline: Kafka keeps notes for 24 hours. Down longer than that and
the unread ones are deleted.

### indexer — the librarian

**What it does:** files new answers into the semantic cache (Tier 2), and throws
out old ones.

**Think of it as:** a librarian putting returned books back on the right shelf.
The shelving takes time, so you do not make the customer at the desk wait while
it happens.

**How it works:**

```
gateway gets a fresh answer from the model
      ↓
posts a note to Kafka: "file this answer, here is its vector"
      ↓
replies to the customer immediately
      ↓
indexer picks up the note later and does the filing
```

**Why the gateway does not do the filing itself:** writing into the vector index
is slow. The index is a graph, and adding something means finding its neighbours
and rewiring links. Doing that inside a request would make every customer wait
for library admin.

**A nice detail:** the vector travels inside the note, so the indexer never has
to recompute it. The code says exactly that — recomputing *"would spend the same
6 ms of CPU twice for an identical result."*

**It also takes out the rubbish.** Cached answers expire after 24 hours, but
PostgreSQL does not delete them by itself. The indexer's `EvictionJob` does.

**If it stops — and this is the dangerous one:** nothing breaks. No error. No
alert. But:

- no new answers get filed, so the cache slowly stops being useful
- the hit rate drifts down, and every lost percent is real money
- nothing gets thrown out, so storage grows forever — and that storage **must fit
  in memory**

It is the worst kind of failure: completely silent, and only visible on a graph
someone has to be looking at.

---

## 5. The ring, with small numbers you can check by eye

Forget the huge numbers for a moment. Imagine a circle marked **0 to 100**.

We have 3 servers: **A**, **B**, **C**. We stick name tags on the circle. Each
server gets a few tags, in random spots:

```
position:   15     32     48     61     79     94
tag:         A      B      A      C      B      C
```

Six tags on the circle.

### The rule

**Every customer stands at a spot on the circle. They walk forwards until they
bump into a tag. That tag's server holds their data.**

That is the whole rule.

### Two customers

**`acme`** - their name gives the number **25**.

```
         acme
          |
0 ... 15 .. 25 .. 32 ... 48 ... 61 ... 79 ... 94 ... 100
      [A]        [B]     [A]    [C]    [B]    [C]
                  ^
            walks forward, first tag is B
```

acme -> **Server B**.

**`stark`** - their name gives **55**.

```
                        stark
                          |
0 ... 15 ... 32 ... 48 .. 55 .. 61 ... 79 ... 94 ... 100
      [A]    [B]    [A]        [C]    [B]    [C]
                               ^
                     walks forward, first tag is C
```

stark -> **Server C**.

### Now add Server D

Its tags land at **20, 58, 88**:

```
position:   15   20   32   48   58   61   79   88   94
tag:         A    D    B    A    D    C    B    D    C
                 NEW            NEW            NEW
```

**acme is still at 25:**

```
         acme
          |
0 ... 15 .. 20 .. 25 .. 32 ...
      [A]   [D]        [B]
                        ^
            walks forward, still hits B first
```

D's new tag at 20 is **behind** acme. Walking forwards, acme never reaches it.
**acme stays on Server B.**

**stark is still at 55:**

```
                   stark
                     |
0 ... 48 ... 55 ... 58 ... 61 ...
      [A]          [D]    [C]
                    ^
         walks forward, now hits D first - not C
```

D's new tag at 58 landed **right in front of** stark, before C's tag at 61.
**stark moves from Server C to Server D.**

### That is the entire mechanism

A customer moves **only if a new tag lands in the gap directly in front of
them.**

- acme's gap in front was 25 -> 32. No new tag there. **Stays.**
- stark's gap in front was 55 -> 61. A new tag at 58. **Moves.**

Nothing was recalculated. Nobody was reassigned. The only change is which tag
you bump into first.

### So where is the "range"?

Server B's range is: *20 to 32*, and *61 to 79* - the gaps behind its tags.

**Nobody writes that down.** There is no table saying "B owns 20-32". You never
need one, because you only ever ask: *"walking forward from here, what is the
first tag?"*

In code that question is one line, a binary search over a sorted map:

```java
ring.ceilingEntry(position)      // "the first tag at or after this point"
ring.firstEntry()                // and if you run off the end, wrap around
```

**That is exactly why adding a server is cheap.** If ranges were written down as
fixed boundaries, adding D would mean rewriting every boundary for A, B and C.
Because they are not written down, adding D's three tags only affects whoever
stood in those three gaps.

### How the plan finds who moves

Ask the question **twice** per customer:

```
acme:   old circle -> B     new circle -> B     same      -> stays
stark:  old circle -> C     new circle -> D     different -> MOVES
```

Two lookups, compare the answers. 20,000 customers in milliseconds.

### What is different in the real system

Three things are bigger. Nothing else changes.

| the example above | the real thing |
|---|---|
| circle marked 0-100 | circle marked with enormous numbers |
| 2-3 tags per server | **150 tags per server** |
| 3 servers -> 4 | 4 shards -> 5 |

**Why 150 tags and not 3:** with only a few tags the gaps are wildly uneven by
luck - one server might own 47% of the circle and another 8%. With 150 each, the
lucky-big and unlucky-small gaps cancel out and everyone gets roughly a fair
share.

**Why the numbers are huge and sometimes negative:** the "number from a name" is
produced by MurmurHash3, which returns a 64-bit signed integer - anywhere from
about -9.2 quintillion to +9.2 quintillion. It behaves exactly like 0-100,
because the only operations are *sort them* and *find the next one up*.

### The same thing with the real numbers

Measured from the actual code, 4 shards becoming 5:

```
tenant "stark"
  hash position      -2,124,025,449,439,872,896
  BEFORE: next mark  -2,066,582,333,654,699,326  owned by shard-1
  AFTER:  next mark  -2,084,277,268,055,720,695  owned by shard-4
  verdict: MOVES
```

Read the three numbers in order and it is the same picture as stark at 55:

```
stark sits at        -2,124,025,449,439,872,896
new shard-4 mark at  -2,084,277,268,055,720,695   <- landed IN BETWEEN
old shard-1 mark at  -2,066,582,333,654,699,326
```

```
tenant "acme"
  hash position      -4,485,103,038,650,495,960
  BEFORE: next mark  -4,464,756,254,765,614,657  owned by shard-1
  AFTER:  next mark  -4,464,756,254,765,614,657  owned by shard-1
  verdict: stays
```

Identical marks before and after. No new mark landed in acme's gap.

### And the result over 20,000 tenants

```
20,000 tenants:  3,904 move (19.5%),  16,096 stay
all 3,904 moved TO the new shard
none moved between two existing shards
```

**19.5% is about 1/5**, because there are now 5 shards and the new one's tags
cover about a fifth of the circle.

Compare the method most people reach for first, `number % 5`: that changes the
answer for roughly **80%** of customers, because dividing by 5 instead of 4
gives a different remainder for nearly everyone. The circle-and-tags trick is
the entire reason it is 20% and not 80%.

---

## 6. What happens when you add or remove a shard

### Adding a 5th shard

150 new marks are placed on the clock. They land in random gaps that currently
belong to the four existing servers.

**A customer only moves if a new mark landed between them and their old mark.**

```
BEFORE:   ... acme ....... [mark: s3] ...     acme → s3

AFTER:    ... acme .. [mark: s4] .. [mark: s3] ...     acme → s4
                         ↑ new mark got in the way
```

How many move:

```
new marks:        150
total marks now:  750  (5 servers × 150)
share newly owned by server 5:  150 ÷ 750 = 20%
customers who move:             about 20%
and ALL of them move TO the new server
```

**The key point: nothing moves between two existing servers.** Server 1's
customers do not go to server 2. Only the new server gains, and it takes a fair
share from everyone.

Compare:

```
with the ring:      20% of customers move   →  move ~153 GiB
with hash % N:      80% of customers move   →  move ~610 GiB
```

That is the difference between a planned afternoon and an outage.

### Removing a shard

The opposite, and just as contained. Server 3's 150 marks are deleted. Anyone who
pointed at one of those marks now walks a bit further clockwise and finds the
next mark — which belongs to whichever server happens to be next.

```
customers who move:  only server 3's customers (about 25%)
where they go:       spread across the remaining 3 servers, roughly evenly
everyone else:       does not move at all
```

Nobody who was on server 0, 1 or 2 is affected.

### The part that actually matters in practice

When a customer moves to a new shard, **their data does not move with them.**
Their cached answers are still sitting on the old shard. The new shard has
nothing for them.

**For a cache, this is fine.** The new shard looks, finds nothing, reports a
miss, the request goes to the model, gets a correct answer, and stores it on the
new shard. The customer sees a slower request and a correct answer. Within a
day, everything has re-cached naturally.

**So re-sharding the cache costs money for a day, not correctness.** This is the
same argument as replica lag: on cached data, "we cannot find it" is a miss, not
a wrong answer.

**But it is NOT fine for the other tables.** `usage_ledger` and `audit_log` are
permanent records. If a customer moves shard, their old invoices and audit trail
are on the old server. There you need a real data migration — copy the rows,
verify, then switch.

**And that migration does not exist in this project.** Nothing copies data
between shards. So today, adding a shard would silently split a customer's
billing history across two servers. For the cache it would just work; for the
ledger it would be a bug.

That belongs on the gaps list, and it is a good example of why "the ring makes
re-sharding cheap" is only true for the data you are allowed to lose.

---

## 7. The indexer, even simpler

**The slow thing is saving an answer into the semantic cache.**

Picture the vector index as a huge **map of connections**. Every stored answer is
joined by lines to its 16 nearest neighbours. To add a new answer you must:

1. work out which existing answers it is nearest to
2. draw lines to them
3. **update those neighbours' lines too** - the newcomer might now be closer than
   one of their old links

That is a lot of fiddling, and it takes time.

**So the gateway does not do it.** If it did that filing while the customer
waited, every new question would be slower - the customer would be paying for
library admin.

Instead:

```
1. gateway gets a fresh answer from the model
2. gateway posts a note to Kafka: "please save this answer"
3. gateway replies to the customer IMMEDIATELY
4. later, the indexer picks up the note and does the slow filing
```

The customer never waits for step 4.

**Why the vector is inside the note.** To store an answer you need its 384
numbers. The gateway **already worked them out** a moment earlier, while it was
searching the cache. So it puts them in the note.

If the note held only the text, the indexer would have to work out the same 384
numbers all over again - **6 ms of CPU for an identical result.** Pointless. So
they travel along.

---

## 8. Where 750 comes from

Servers multiplied by marks. Nothing more.

```
BEFORE:  4 servers x 150 marks each  =  600 marks on the clock
AFTER:   5 servers x 150 marks each  =  750 marks on the clock
```

The new server owns 150 of those 750:

```
150 / 750 = 20%
```

So it takes 20% of the clock, and therefore about 20% of customers.

The same arithmetic works at any size. With N servers, each one owns
`150 / (N x 150)` = **1/N of the ring**. 5 servers, 1/5, 20%.

---

## 9. How you would actually move the data

Two halves, and the second one is the honest part.

### For the cache: you do not move it

```
1. change the ring config: 4 shards -> 5
2. reload the gateways
3. done
```

Tenants who moved now point at the new shard, which holds nothing for them. Every
lookup is a miss, goes to the model, and stores the answer on the new shard.
**Within about a day everything has re-cached itself.**

Cost: one day of extra model bills for ~20% of tenants. Correctness: untouched,
because a miss is never a wrong answer.

The old rows sit on the old shard until their 24-hour TTL expires, then eviction
deletes them. **No migration needed at all.**

### For the permanent records: you must move it properly

`usage_ledger` and `audit_log` are invoices and audit trails. You cannot shrug
and re-create those.

The standard approach is five steps:

```
1. ADD the new shard to the ring, but keep routing to the OLD layout
2. COPY the moving tenants' rows: old shard -> new shard, tenant by tenant
3. VERIFY row counts and checksums match on both sides
4. SWITCH the ring config - reads and writes now go to the new shard
5. DELETE the copied rows from the old shard, once the switch is trusted
```

**The hard part is between steps 2 and 4**, because new rows keep arriving while
you are copying. Two usual fixes:

- **Dual-write:** during the window, write to both shards. Switching is then safe
  because both are current.
- **Read from both:** during the window, query old and new and merge. Slower, but
  nothing can be missed.

Either way, **tenant by tenant** rather than all at once - so a problem affects
one customer instead of everybody.

### What this project actually has

**None of it.** Nothing copies rows between shards. There is no migration script,
no dual-write mode, no verification step.

So adding a shard today would mean:

| data | what happens |
|---|---|
| `prompt_cache` (the cache) | **fine.** Misses for a day, then re-cached |
| `usage_ledger` | **broken.** Old invoices stay on the old shard, new ones go to the new shard. A tenant's billing history splits in two, and nothing joins it |
| `audit_log` | **broken** the same way |

Logged as work item A18.

It is also why **"the ring makes re-sharding cheap" only holds for the cache.**
That happens to be most of the data here - but not the data you would get sued
over.

---

**Previous:** [B5b — real servers and mechanisms](B5b-real-servers-and-mechanisms.md).
**Next:** [B6 — the LLD](B6-lld.md).
