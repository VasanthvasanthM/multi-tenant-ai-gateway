# B7 — Redis

What it holds, why the keys are shaped the way they are, and what happens when
it is not there.

---

## What is in there — seven kinds of key

```
{tenant:acme}:c1:9f86d081...     a cached answer           Tier 1
{tenant:acme}:rl                 rate-limit bucket
{tenant:acme}:quota              this tenant's token limit
{tenant:acme}:budget:2026-11     tokens spent this period

apikey:5e884898...               which tenant owns this key
dash:session:7f3b...             a logged-in dashboard user
gateway:migrating                who is mid-migration
```

Four per-tenant, three global. **That split is the most interesting thing about
the design.**

---

## The curly braces

```java
public String redisHashTag() {
    return "{tenant:" + value + "}";
}
```

Redis Cluster spreads keys across nodes by hashing the key name - **but if the
name contains braces, only what is inside them is hashed.** So all four of
Acme's keys hash on `tenant:acme` and land on the same node.

### Why it is not optional

The rate limiter is a Lua script, and:

> *"A script may only touch keys in one slot."*

Redis Cluster refuses a script that spans nodes. Without the hash tag, the rate
limiter **stops working the day you move to Cluster** - and works perfectly on a
single node, so nothing would warn you.

### The bonus

Everything about one tenant is on one machine: one network hop reaches their
cache entry, bucket, quota and budget.

---

## Why three keys are NOT tenant-scoped

Because **you do not know the tenant yet**.

```
Authorization: Bearer sk-acme-7f3b...
        |  hash it, look up apikey:<hash>
        v
   NOW you know it is Acme
```

The lookup cannot live in Acme's slot, because finding out it is Acme is what
the lookup is for.

> *"Unlike the `apikey:` index, which is global because a lookup starts from a
> key digest and not from a tenant."*

Same for a session cookie - an opaque id until resolved. And
`gateway:migrating` is one small hash the whole fleet reads.

---

## API keys are stored hashed

```
apikey:5e884898...        SHA-256 of the key, never the key
```

A Redis dump yields hashes, which cannot be used to authenticate.

### Why a plain digest and not bcrypt

> *"A slow KDF like bcrypt exists to defeat dictionary attacks on guessable
> input, and would add milliseconds to a 2 ms budget for no security gain
> against 256 bits of randomness."*

bcrypt makes **guessing** slow. An API key is 30+ random characters - there is
nothing to guess. Paying 50 ms per request would buy nothing and cost everything.

> **The right amount of security is decided by what the attacker can actually
> do, not by what sounds strongest.**

### And the comparison is constant-time

The bootstrap map uses `MessageDigest.isEqual`, not `String.equals`:

> *"String comparison short-circuits on the first differing byte, and the timing
> difference is measurable across enough requests - it lets an attacker recover a
> valid hash one byte at a time."*

```java
// Every entry is compared, with no early exit, so that total time does not reveal
// how many entries were examined before a match.
```

**A secret can be read by timing how long "wrong" takes to say.**

---

## What happens when Redis dies - seven different answers

| key | Redis down -> | what you lose |
|---|---|---|
| `{tenant}:c1:...` Tier 1 | **miss** -> Tier 2 or the AI | speed and money, nothing else |
| `{tenant}:rl` | **allow** (fail-open) | abuse protection, temporarily |
| `{tenant}:quota` | **allow** (fail-open) | the ceiling - **not** the billing |
| `{tenant}:budget` | **allow** (fail-open) | same |
| `gateway:migrating` | state drops after 60 s | a migration's protection |
| `dash:session:...` | cannot log in | the dashboard, for humans |
| **`apikey:...`** | **DENY** | **everything** |

### Six fail open. One fails closed.

```java
.onErrorResume(error -> {
    // Authentication fails closed, unconditionally. Unlike the rate limiter,
    // which trades enforcement for availability, there is no acceptable
    // reading of "Redis is down so let everyone in".
    log.error("api key lookup failed, denying request: {}", error.toString());
    return Mono.empty();
});
```

> **Fail-open is a default, not a principle.** It applies to things that
> *check*. It cannot apply to the thing that decides *who you are*.

Note the level: `log.error`, not `log.warn`. The rate limiter warns because the
gateway is still serving. This is an outage and says so.

### What it looks like from outside

```
every request   ->  401
the gateway     ->  healthy, fast, serving nobody
dashboards      ->  green
the logs        ->  "api key lookup failed, denying request"
```

**A working gateway with no customers.** That one ERROR line is the only thing
that says otherwise, which is why it names the cause rather than saying
"unauthorized".

### The rule behind all seven rows

**Does this key have a safe default?**

```
cache entry gone  ->  ask the AI again. Correct answer, costs money.
auth unknown      ->  there is no safe guess.
```

Six have one. One does not.

---

## Everything expires

```
{tenant}:c1:...       1 hour      Tier-1 entry
{tenant}:rl           1 hour      idle tenants cost no memory
{tenant}:budget:...   the period  monthly counter
dash:session:...      8 hours     a working day
gateway:migrating     900 s       a forgotten migration heals itself
```

**Nothing lives forever.** Not tidiness - the memory plan. A store where things
only accumulate falls over eventually, at the worst moment.

## No persistence

```yaml
--save ""           # no snapshots
--appendonly no     # no write-ahead log
```

> *"Tier 1 is a cache: losing it costs a slower request."*

A restart empties it, the next requests miss, it refills. You pay money for an
hour and **nothing is ever incorrect**. Persistence would put disk writes on the
hot path to protect data that is by definition reconstructible.

## Eviction

```
maxmemory          96mb
maxmemory-policy   allkeys-lru
```

Covered in full under **Caching** in
[B6b1](B6b1-patterns-where-and-why.md). The short version: `noeviction` would
refuse new writes and turn the same loss of cache value into a stream of errors.

### The number to watch

```bash
kubectl exec deploy/redis -- redis-cli INFO stats | grep evicted_keys
```

`evicted_keys > 0` means the working set outgrew memory: hit rate falls, provider
bill rises. **A cost problem, not an outage** - the right direction to fail, and
invisible unless someone looks, which is why it is on the dashboard.

---

## The assumption the whole fleet rests on

```
2x10^8 distinct prompts/day  x  2 KiB per entry (ASSUMED)  =  ~381 GiB
381 GiB  /  48 GiB usable per node                         =  9 primaries (+9 replicas)
```

The plan's own note says **`(ASSUMED)`**. Nobody has measured the real entry
size, and the fleet is linear in it:

```
really 1 KiB  ->  5 nodes
really 4 KiB  ->  18 nodes
```

Half the Redis bill, or double it, resting on a guess.

### The fix is one division

```bash
redis-cli INFO memory | grep used_memory:
redis-cli DBSIZE
#   used_memory / DBSIZE = the real mean entry size
```

That is **A16**, still open - the highest-value measurement left in the capacity
plan, because it is cheap and moves a hardware number by 2x.

> Same family as the `max-in-flight: 256` finding: a number with no measurement
> behind it, propagating into a conclusion that looks precise.

## One honest note on the 96 MiB

A **laptop** number, so Redis cannot eat the node. The capacity plan assumes
381 GiB across 9 nodes. **The policy is production-shaped; the limit is not, and
is not meant to be.**

---

**Previous:** [B6c — the APIs](B6c-apis.md).
**Next:** B8 — Kafka.
