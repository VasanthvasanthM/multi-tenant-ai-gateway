# B10 — Capacity estimation

From one measured pod to two billion requests a day, on named servers, with every
assumption labelled.

Recalculated 2026-11-17. The previous version in
[B5b](B5b-real-servers-and-mechanisms.md) contained a contradiction worth
recording: it sized gateway pods at 2 vCPU **because Tier 2's embedding needs two
threads**, then used 450 rps/pod - a figure extrapolated from a run with Tier 2
switched **off**. With it on, the same pod shape measured **4-8 rps**.

---

## The targets

```
2,000,000,000 requests/day
       23,148 rps average
       92,593 rps peak                (4x diurnal)
       28,148 rps need an embedding   (the 30.4% that miss Tier 1)
```

`30.4%` is `1 - 0.696`, and 0.696 is the **measured** Tier-1 hit ratio.

---

## The one measured input

```
1000m CPU / 384 MiB pod  ->  >= 271.3 rps, p50 198 ms, p95 476 ms
                             at a 0.696 hit ratio, Tier 2 OFF
```

Everything else below is arithmetic on that, or an assumption that is labelled as
one.

---

## Compute

The structural decision first: **embedding runs as its own service, not inside
the gateway.** Why is in the section after next.

```
gateway      92,593 / 271.3 rps/pod / 0.70 util   =  488 pods, 488 vCPU, 183 GiB
embedding    28,148/s / 166.7 per core / 0.70     =  242 vCPU
```

`166.7 per core` is `1000 / 6 ms`, and the 6 ms is **assumed** - see the gaps.

`0.70` is a utilisation target, not a measurement: a fleet sized to 100% of peak
has no headroom for a node failure or a traffic spike.

---

## The fleet, on named instances

| role | instance | vCPU / RAM | count | why that shape |
|---|---|---|---|---|
| **gateway** | `m7i.4xlarge` | 16 / 64 GiB | **39 nodes** | general purpose; 488 vCPU / 12.8 allocatable |
| **embedding** | `c7i.4xlarge` | 16 / 32 GiB | **19 nodes** | compute-optimised - pure CPU, little RAM |
| **Redis** | `r7g.4xlarge` | 16 / 128 GiB | **8** (4+4) | memory-bound: 381 GiB / 96 usable |
| **PostgreSQL** | `r7g.8xlarge` | 32 / 256 GiB | **12** (4 shards x 3) | the 439 GiB HNSW index must stay in RAM |
| **Kafka** | `m7i.2xlarge` | 8 / 32 GiB | **9** | disk throughput: 625 MiB/s / ~100 MiB/s |
| **consumers** | 1 vCPU each | - | **47 pods** | metering 19, audit 19, indexer 6, control-plane 3 |

```
total nodes   39 + 19 + 8 + 12 + 9        =  87
total vCPU   488 + 242 + 128 + 384 + 72   =  ~1,314
```

`12.8 allocatable` on a 16 vCPU node is ~80% - the kubelet, the CNI, the log
shipper and the metrics agent take the rest.

---

## Storage

```
distinct prompts/day                200,000,000     (10% of traffic, ASSUMED)

Redis   Tier 1 @ 2 KiB/entry              381 GiB
PG      Tier 2 rows @ 4 KiB/row           763 GiB   -> 191 GiB disk per shard
PG      HNSW index @ 2.3 KiB/entry        439 GiB   -> MUST stay in RAM
Kafka   208 MiB/s x RF3 x 24 h retention   51 TiB
```

The HNSW line is the one that picks the Postgres shape. An ANN index that spills
to disk stops being an ANN index - each hop becomes a disk seek - so RAM, not
disk, decides the shard count.

```
439 GiB / 192 GiB usable RAM  =  3 shards minimum  ->  4 chosen
```

Four rather than three because it divides cleanly and splits to 8 later. Kafka is
sized by **disk throughput**, not CPU or RAM, which is why it is the one tier that
does not change when traffic shape changes.

---

## Why embedding had to move off the request path

The measurement is brutal:

```
Tier 2 OFF, 1 core      271 rps
Tier 2 ON,  2 cores     4-8 rps
```

Twice the CPU, roughly forty times less throughput. And the naive arithmetic says
that should be impossible:

```
pure CPU cost of embedding at peak     169 cores
what the measurement implies       23,000-46,000 vCPU
```

A ~150x discrepancy, so embedding is not merely *using* CPU - it is **destroying
the pod around it**:

```
embedding saturates the core
    -> the Redis client is starved
    -> Tier-1 lookups exceed the 500 ms timeout
    -> each timeout becomes a full upstream call
    -> more load, more starvation
    -> 271 rps collapses to 4-8
```

And the worst consequence is not throughput:

> *"When CPU is tight, embedding starves the Redis client until the 500 ms timeout
> trips and **the rate limiter fails open** - a tenant-isolation failure reached
> through a cache feature."*

So the two architectures cost:

```
embedding in-process     11,574 - 23,148 pods   =   23,148 - 46,296 vCPU
embedding as a service          488 + 242 vCPU  =          730 vCPU
```

**~40x.** That is what turns "move embedding off the request path" from a
preference into a decision - and it is why ADR-0002's in-process choice, correct
for a 15 ms budget on one pod, does not survive contact with a fleet.

---

## What this plan rests on

One measurement, five assumptions:

```
271.3 rps/pod        MEASURED    the only load-bearing measurement
  6 ms/inference     ASSUMED     -> all 242 embedding vCPU
  2 KiB/Redis entry  ASSUMED     -> 8 Redis nodes            (A16)
  4 KiB/PG row       ASSUMED     -> 763 GiB
2.3 KiB/HNSW entry   ASSUMED     -> 439 GiB -> 4 shards
 10% distinct        ASSUMED     -> EVERY storage number
 0.70 utilisation    CHOSEN      -> every pod count
```

**The 10% is the biggest lever and nobody has checked it.** At 20% distinct, every
storage figure doubles and Postgres goes from 12 instances to 24.

Two are cheap to fix:

```
2 KiB per Redis entry   redis-cli INFO memory / DBSIZE          (A16)
6 ms per inference      the gateway.embedding.duration meter    (added in A13)
```

---

## How to read any of these numbers

Every line here is one of three things, and the label matters more than the
value:

```
MEASURED   271.3 rps, 0.696 hit ratio, 198 ms p50, 4-8 rps with Tier 2 on
CHOSEN     0.70 utilisation, 4 shards, RF3, 24 h retention
ASSUMED    every per-entry size, the 6 ms, the 10% distinct rate
```

A capacity plan whose assumptions are not labelled reads as precision it has not
earned. This one has exactly one measured input, and says so.

---

**Previous:** [B9 — PostgreSQL](B9-postgresql.md).
**Next:** B11 — start and stop commands.
