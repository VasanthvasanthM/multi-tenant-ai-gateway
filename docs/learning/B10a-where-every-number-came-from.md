# B10a — Where every number came from

A companion to [B10](B10-capacity-estimation.md), written because the right
question to ask of any capacity plan is *"who measured that?"*

Three categories, and the label matters more than the value:

```
MEASURED   a tool recorded it, and the run is written down
DERIVED    arithmetic on something measured
ASSUMED    somebody guessed, including me
```

---

## MEASURED — the one real input

### 271.3 rps, 0.696 hit ratio, 198 ms p50

```
loadtest/k6/capacity.js                   the k6 script
loadtest/scripts/capacity-sweep.sh        the ladder driver
docs/architecture/scaling-model.md §3b    the recorded run
```

| Pods | X(N) | p50 | p95 | Pod CPU | Throttled |
|---:|---:|---:|---:|---:|---:|
| 1 | **271.3 rps** | 198 ms | 476 ms | 89.6% | **92.9%** |
| 2 | 288.4 rps | 187 ms | 402 ms | 56.3% | 61.0% |
| 3 | 229.1 rps | 310 ms | 1,154 ms | 54.5% | 31.0% |

**It is a lower bound**, not a ceiling:

> *"Step 1 peaked at the top rung of its ladder: the pod was still gaining
> throughput when the ladder ran out, and it was throttled in 92.9% of periods,
> so it was genuinely CPU bound at its own limit."*

### How the hit ratio was captured

The gateway declares the tier on a response header; k6 tallies it.

```javascript
const tier = response.headers['X-Cache-Tier'] || '';
if (tier === 'tier1_exact') {
  servedByTier1.add(1);
  hitRate.add(true);
}
```

Not inferred - **reported by the server and counted by the client.** Three ladder
steps agreed (0.696 / 0.694 / 0.699), which is why the run was accepted at all.

### How CPU was measured - not `kubectl top`

```bash
kubectl exec <pod> -- sh -c 'cat /sys/fs/cgroup/cpu.stat'
```

```
usage_usec      CPU microseconds consumed
nr_periods      scheduling periods elapsed
nr_throttled    periods in which the pod was throttled
```

Raw cumulative kernel counters, delta-ed across the window. `kubectl top` was
rejected for a measured reason:

> *"It reports a decaying average on the metrics-server scrape cycle, so read
> after a run it samples the idle period afterwards."*

It reported 51-76% for pods throttled in 46-72% of periods.

And a Windows trap worth knowing:

> *"Git Bash on Windows rewrites anything that looks like an absolute POSIX path
> ... `/sys/fs/cgroup/cpu.stat` was arriving in the container as
> `C:/Program Files/Git/sys/fs/cgroup/cpu.stat`. The read failed silently and the
> CPU column simply read `n/a` for a whole sweep."*

Hence `MSYS_NO_PATHCONV=1` in the sweep script.

---

## What the measurement did NOT include

This is the most important section in this file.

```
Tier 1       ON
Tier 2       OFF    <- no embedding ran at all
Kafka        OFF    <- no event publishing at all
provider     mock, 40 ms configured latency
```

From `values-dev.yaml`:

```yaml
GATEWAY_CACHE_TIER2ENABLED: "false"
control-plane: enabled: false
metering:      enabled: false
indexer:       enabled: false
audit:         enabled: false
```

and `KAFKA_ENABLED` defaults to `false`.

So **two** per-request costs are absent from 271.3 rps:

| missing cost | status |
|---|---|
| embedding (Tier 2) | measured separately, and catastrophic: 4-8 rps on 2 cores |
| Kafka publishing | **never measured** - two JSON serialisations and two sends per request, on the request thread |

Publishing is fire-and-forget, so nothing *waits* for it - but the CPU is spent
on the same core that produced the 271.3.

> **The honest statement: 271.3 rps is the throughput of a gateway doing Tier-1
> caching and nothing else.** Every pod count in B10 inherits that caveat.

---

## The laptop numbers - no command is recorded

The repo states `7.4 GB total RAM, ~460 MB free while Docker Desktop runs` and
`4 physical cores / 8 logical CPUs`, but **does not record how that was
obtained**. It was read off the machine.

And the WSL figures are what was **set**, not measured:

```ini
memory=4GB
processors=6
```

To check it yourself:

```powershell
systeminfo | findstr /C:"Total Physical Memory" /C:"Available Physical Memory"
Get-CimInstance Win32_Processor | Select NumberOfCores, NumberOfLogicalProcessors
```

```bash
free -h      # inside WSL: what the VM actually got
nproc
```

"Leave 2 CPUs for the load generator" is a **decision**, not a measurement: 8
logical CPUs, 6 to WSL, 2 for k6, so the generator does not compete with the
thing it measures.

---

## DERIVED - the op rates

**These are arithmetic done in conversation on 2026-11-17. They are not measured
and were not in the repo before this file.** Counted from the code:

### Redis, per request

```java
RedisApiKeyRegistry:80   opsForValue().get(...)   1 READ    the api key
TokenBudgetLedger:140    opsForValue().get(...)   1 READ    the quota
Tier1Cache:90            opsForValue().get(...)   1 READ    the lookup
TokenBucketRateLimiter   execute(script)          1 WRITE   spends a token
TokenBudgetLedger:121    execute(script)          1 WRITE   records usage
Tier1Cache:132/144       opsForValue().set(...)   1 WRITE   on a miss or promotion
```

```
3 reads  x 92,593            = 277,779 /s
2 writes x 92,593            = 185,186 /s
+ promotions (Tier-2 hits)   =  11,259 /s
                               ---------
writes                         196,445 /s
total                          474,224 /s
```

**Two caveats on my own arithmetic:**

- the API key is **one Redis GET per request with no local cache** - itself worth
  questioning against a 2 ms budget
- the token bucket script reads *and* writes inside one round trip; counting it
  as one write understates Redis's internal work

**Writes are 41% of Redis traffic**, which surprises most people. The cause is
that a rate limiter is not a read-only check - it spends a token on **every**
request.

### PostgreSQL, per request

```
usage_ledger insert    1 per request       ->  92,593 /s    metering
audit_log insert       1 per request       ->  92,593 /s    audit
vector search          1 per Tier-1 miss   ->  28,148 /s
Tier-2 insert          1 per full miss     ->  16,889 /s    indexer
hit_count update       1 per Tier-2 hit    ->  11,259 /s
                                              ---------
                                              241,483 /s    ~77% writes
```

**Postgres is write-heavy here, not a cache.** The two ledgers dominate - and
they are exactly the tables metering and audit own, which is why A28 (those
services not routing by shard) matters as much as it does.

The multipliers:

```
Tier-1 miss  30.4%           = 1 - 0.696            MEASURED
Tier-2 hit   40% of misses                          ASSUMED - never measured
full miss    the remainder
```

**The 40% is mine and has no evidence behind it.** Measuring the Tier-2 hit rate
would change the last three lines and the whole business case.

---

## ASSUMED - the per-entry sizes

Stated in the plan with the word in capitals:

```
2e8 x 2 KiB/entry (ASSUMED)       = ~381 GiB Redis working set
2e8 x 4 KiB/row (ASSUMED)         = ~763 GiB Postgres data
2e8 x 2.3 KiB/row HNSW (ASSUMED)  = ~439 GiB index, must stay in RAM
```

Where each plausibly comes from:

| | reasoning |
|---|---|
| 2 KiB | a prompt + a response + JSON overhead. Guessed |
| 4 KiB | the same, plus a 384-float vector (1.5 KiB) and the columns |
| 2.3 KiB | `384 x 4 bytes` = 1,536 real bytes, plus HNSW's m=16 links per node |

Only the last is semi-derivable, and only half of it.

### How much it matters

```
at 2 KiB/entry:  2e8 x 2 KiB = 381 GiB  ->  4 Redis primaries
at 4 KiB/entry:  2e8 x 4 KiB = 763 GiB  ->  8 Redis primaries
```

> *"The entire Redis fleet is linear in this one guess, and it is measurable in
> about a minute against real traffic."*

```bash
kubectl exec deploy/redis -- redis-cli INFO memory | grep used_memory:
kubectl exec deploy/redis -- redis-cli DBSIZE
# used_memory / DBSIZE = the real average
```

**One minute of work would replace a guess that doubles or halves eight
servers.** That is A16, still open.

### And the 10% distinct rate

```
2e9 requests/day x 10% distinct (ASSUMED) = 2e8 distinct prompts/day
```

**This multiplies every storage number above.** At 20% distinct, Redis, Postgres
data and the HNSW index all double, and Postgres goes from 12 instances to 24.
Nobody has checked it.

---

## Bandwidth

### Kafka's 208 MiB/s - in the repo, and derived

```
2e9 x (1 usage + 1 audit + 0.304 cache-write) = 4.6e9 events/day
4.6e9 / 86,400 x 4 peak                       = ~213k events/s peak
213k x 1 KiB/message (ASSUMED)                = 208 MiB/s ingress
208 x 3 (RF=3)                                = 625 MiB/s written
208 x 86,400 x 3                              = ~51 TiB at 24 h retention
```

The `2.304` is `1 + 1 + 0.304`: **every request makes two events, and a Tier-1
miss makes a third.**

Worth noticing: at 208 MiB/s of telemetry against ~181 MiB/s of customer
traffic, **Kafka moves more data than the customers do.**

### Client bandwidth - mine, and the weakest number here

```
92,593 rps x 2 KiB (1 KiB in + 1 KiB out, MY assumption) = 180.8 MiB/s = 1.5 Gbit/s
gateway -> AI: only the 16,889 full misses x 2 KiB       =  33.0 MiB/s
```

Not in the repo, pure illustration, and **wrong for any real workload** - a
response is usually several times larger than its prompt. The useful part is the
ratio: the cache cuts vendor egress by ~82%, not just vendor cost.

---

## The summary worth remembering

```
MEASURED   271.3 rps, 0.696 hit ratio, 198/476 ms, 92.9% throttled
           4-8 rps with Tier 2 in-process
           ...with Kafka OFF and Tier 2 OFF

DERIVED    all the op rates, all the pod counts, all the node counts

ASSUMED    6 ms/inference, 2 KiB, 4 KiB, 2.3 KiB, 10% distinct,
           40% Tier-2 hit rate, 1 KiB/message, 1 KiB each way,
           0.70 utilisation
```

**One measurement, nine assumptions, and every server count is arithmetic on
them.** That is not a weakness of the plan - it is what every capacity plan looks
like. The difference is whether the labels are written down.

---

**Companion to:** [B10 — capacity estimation](B10-capacity-estimation.md).
