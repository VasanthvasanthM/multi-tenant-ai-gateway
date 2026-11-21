# B5d — Moving a customer to a new shard, in plain words

No commands. No jargon. One story with five customers, then the part everyone
asks about: how a request finds the new shard afterwards.

The commands are in
[`docs/runbooks/shard-migration.md`](../runbooks/shard-migration.md). Read this
first.

---

## Part 1 — The story

### You have 4 servers and 5 customers

```
Server 1, Server 2, Server 3, Server 4

Customers: acme, globex, stark, wayne, oscorp
```

You want to add **Server 5**. Who has to move?

### Step 1 — Who are my customers?

Ask the database: *"give me every customer name."*

```
acme
globex
stark
wayne
oscorp
```

Just a list of names. Nothing clever.

### Step 2 — Where is each one right now?

Take each name, put it through the calculator. The calculator says which server.

```
acme    -> Server 2
globex  -> Server 2
stark   -> Server 2
wayne   -> Server 1
oscorp  -> Server 3
```

### Step 3 — Where would each one be with 5 servers?

Same names. Same calculator. But now tell it there are **5** servers.

```
acme    -> Server 2
globex  -> Server 2
stark   -> Server 5      <- changed
wayne   -> Server 1
oscorp  -> Server 3
```

### Step 4 — Compare the two lists

| customer | with 4 servers | with 5 servers | what to do |
|---|---|---|---|
| acme | Server 2 | Server 2 | nothing |
| globex | Server 2 | Server 2 | nothing |
| **stark** | **Server 2** | **Server 5** | **move it** |
| wayne | Server 1 | Server 1 | nothing |
| oscorp | Server 3 | Server 3 | nothing |

**That comparison is the plan.** One customer moves, four do not. Written down,
the plan is one line:

```
stark   from Server 2   to Server 5
```

### Step 5 — Copy stark's data

```
Server 2                        Server 5
  stark: 120 billing rows   ->    (empty)
         60 audit rows
```

becomes

```
Server 2                        Server 5
  stark: 120 billing rows   ->    stark: 120 billing rows
         60 audit rows                   60 audit rows
```

Both servers now hold it. **Nothing has been deleted.**

### Step 6 — Check it arrived correctly

Count rows on both sides: 120 and 120, 60 and 60.

Then take a **fingerprint** of all the content on each side and compare. If one
single number changed during the copy, the fingerprints differ and you stop. A
count alone would not notice.

### Step 7 — Tell the system about Server 5

Change the configuration from 4 servers to 5, and restart.

### Step 8 — Wait a day, then delete the old copy

Leave stark's rows on Server 2 for a day. If anything is wrong you switch the
config back to 4 servers and the old data is still sitting there.

Once you are happy, delete from Server 2. Done.

### The whole thing in one sentence

> **Get the list of customers, ask the calculator where each one lives now and
> where it would live after, and the ones whose answer changed are the ones you
> copy.**

### What "the calculator" means

1. turn the name `"stark"` into a number
2. see which server owns that number
3. that is the server

The same name always gives the same number. So stark always lands on the same
server - until you add a server, which changes who owns which numbers. **That is
the only reason anybody ever moves.**

---

## Part 2 — How does a request find the new shard?

### It does not search. It calculates.

There is **no lookup table** anywhere saying "stark lives on Server 5". Nothing
is stored. On **every single request** the gateway runs:

```java
ShardId shard = router.route(tenantId);     // calculates it, right there
```

That is the same calculator the plan used. Same name in, same server out.

### So why does the answer change?

Because you changed **how many servers it knows about**.

```
BEFORE cutover
  config says:  4 servers
  request for stark  ->  calculator  ->  Server 2

AFTER cutover
  config says:  5 servers
  request for stark  ->  calculator  ->  Server 5
```

**The calculator did not change. Its input did.** You told it a fifth server
exists, so it stamps 150 more tags on the circle, and stark now bumps into a
Server-5 tag first.

### What the cutover actually is

The server list is read from configuration **when a gateway pod starts**. In
`ShardConfig` the ring is a `@Bean`, built once:

```java
ConsistentHashRing.of(clients.keySet(), properties.virtualNodes())
```

So the cutover is two things:

1. add Server 5 to the config
2. restart the gateway pods

From the moment a pod restarts, it calculates Server 5 for stark and looks
there. The data is already waiting, because you copied it in step 5.

### The neat part

There are hundreds of gateway pods. **None of them talk to each other about
this.** None asks a registry. None looks anything up.

Every pod independently runs the same calculation with the same inputs, so they
all reach the same answer. That is the whole reason the design uses a hash ring:
**it needs no coordination at all.**

### The dangerous part

Kubernetes restarts pods **one at a time**. So for a few minutes:

```
pods already restarted  ->  know 5 servers  ->  stark goes to Server 5   works
pods not yet restarted  ->  know 4 servers  ->  stark goes to Server 2   works
```

**Both work** - and they work *only because the old copy still exists.*

```
Server 2:  stark's data   <- old pods read this
Server 5:  stark's data   <- new pods read this
```

**This is exactly why cleanup is step 8 and not step 6.** Delete from Server 2
right after copying and:

```
pods already restarted  ->  Server 5  ->  finds data          works
pods not yet restarted  ->  Server 2  ->  FINDS NOTHING       broken
```

Those requests see an empty history. For the cache that is only a miss. For
billing it is a customer whose invoice briefly vanished.

### The wrinkle: writes during the window

Because reads land on either server during the window, **writes land on either
too**. A new billing row for stark might be written to Server 2 by a pod that
has not restarted yet - after you already copied.

That is the known gap, work item **A19**. The workable manual answer:

```
after every pod has restarted:
  copy stark again      (idempotent, so it only picks up the new rows)
  verify again
  then clean up
```

### The order, with the reason for each step

```
1. copy stark to Server 5        both servers hold the data
2. verify it matches             proof, before anything risky
3. add Server 5 to the config
4. rolling restart               MIXED WINDOW - both servers serve correctly
5. wait until every pod restarted
6. copy once more, verify        catch writes that landed on Server 2 meanwhile
7. delete from Server 2          only now is this safe
```

Steps 1, 3 and 7 are the only ones that change anything. **Everything else
exists so that step 7 can never be the step that loses data.**

---

**Previous:** [B5c — plain answers](B5c-plain-answers.md).
**Next:** [B6 — the LLD](B6-lld.md).
**Commands:** [`shard-migration.md`](../runbooks/shard-migration.md).
