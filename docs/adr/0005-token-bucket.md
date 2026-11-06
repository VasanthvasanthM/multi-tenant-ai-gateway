# ADR-0005: Token bucket rather than leaky bucket

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author
- **Deviation from blueprint:** the blueprint specified a leaky bucket

## Context

Per-tenant rate limiting, enforced consistently across gateway replicas.

## Decision

Token bucket, implemented as a single atomic Lua script in Redis.

## Rationale

**Real API clients burst.** A nightly batch job fires fifty requests and then idles for
a minute. That is comfortably inside its average rate and a leaky bucket rejects it
outright, because a leaky bucket enforces a strictly *uniform* rate. The tenant then
reports an outage, and they are not wrong.

A token bucket accumulates allowance while idle and spends it in a burst, while still
bounding the sustained rate. It models the actual contract — "N requests per second on
average, up to a burst of C" — which is also what every provider the gateway talks to
offers.

**Why Lua rather than application code.** The naive implementation is `GET`, compute,
`SET`: a read-modify-write across a network round trip. Two replicas serving the same
tenant read the same count and both proceed. Under load that is not a rare race, it is
the normal case, and the effective limit silently becomes
`configured_limit x replica_count`. Redis executes a script atomically against one key,
so the read, the refill and the write cannot interleave.

**Why server time.** The timestamp comes from `redis.call('TIME')`, not the caller.
Replicas sit on different nodes with independently drifting clocks; a pod running fast
would persist a future timestamp and every other pod would then compute negative
elapsed time and refill nothing. Redis is the one clock all replicas already share.
Safe since Redis 5, where effects replication means replicas receive the writes rather
than the non-deterministic script.

## Consequences

**Accepted costs**

- A burst can briefly exceed the sustained rate. That is the feature.
- Logic lives in Lua, so it is tested through Redis rather than as a unit.
- One Redis round-trip per request, before any cache lookup. Deliberate: reversed, a
  tenant hammering a cached prompt would consume CPU and Redis without ever being
  limited.

**Benefits**

- Atomic and correct across any number of replicas, with no distributed lock.
- Returns the exact `retryAfterMillis`, so a well-behaved client backs off by the right
  amount instead of guessing and worsening the overload.
- A bucket starts **full** on first contact. Starting empty makes a tenant's very first
  request fail, which is indistinguishable from an outage from their side.

## Alternatives rejected

- **Leaky bucket** — rejects legitimate bursts.
- **Fixed window** — allows 2x the limit across a window boundary.
- **Sliding window log** — accurate and stores every request timestamp; the memory cost
  is not justified when a bucket is two numbers.
- **Application-side counting** — the race described above.
