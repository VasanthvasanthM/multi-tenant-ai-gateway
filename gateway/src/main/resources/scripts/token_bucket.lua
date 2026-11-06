--[[
  Per-tenant token bucket rate limiter.

  WHY THIS IS A LUA SCRIPT
  ------------------------
  The naive implementation is GET tokens, compute, SET tokens. That is a read-modify-write
  across a network round trip, so two gateway replicas serving the same tenant at the same
  moment both read the same token count and both decide they may proceed. Under load that
  is not a rare race - it is the normal case, and the effective limit silently becomes
  (configured limit x replica count).

  Redis executes a script atomically against a single key, so the read, the refill
  computation and the write cannot interleave with another replica's. This is the entire
  reason the logic lives in Lua rather than in Java.

  WHY SERVER TIME RATHER THAN CLIENT TIME
  ---------------------------------------
  The timestamp comes from redis.call('TIME'), not from the caller. Gateway replicas run on
  different Kubernetes nodes with independently drifting clocks; if each passed its own
  wall clock, a pod running a few seconds fast would persist a future timestamp and every
  other pod would then compute a negative elapsed time and refill nothing. Redis is the one
  clock all replicas already share.

  Since Redis 5, effects replication is the default, so a non-deterministic command such as
  TIME is safe inside a script - replicas receive the resulting writes, not the script.

  WHY TOKEN BUCKET RATHER THAN LEAKY BUCKET
  -----------------------------------------
  A leaky bucket enforces a strictly uniform rate. Real API clients are bursty: a batch job
  fires fifty requests then idles for a minute, which is well inside its average but fails a
  leaky bucket outright. A token bucket accumulates allowance while idle, permitting the
  burst, while still bounding the sustained rate.

  KEYS[1]  bucket hash key, already namespaced and cluster hash-tagged by the caller
  ARGV[1]  capacity          - maximum tokens, i.e. the largest permitted burst
  ARGV[2]  refillPerSecond   - sustained rate
  ARGV[3]  requested         - tokens this call consumes
  ARGV[4]  ttlSeconds        - idle expiry, so dormant tenants cost no memory

  Returns { allowed, remaining, retryAfterMillis }
    allowed          1 or 0
    remaining        whole tokens left after this call
    retryAfterMillis 0 when allowed; otherwise the wait until the request would succeed
--]]

local key             = KEYS[1]
local capacity        = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local requested       = tonumber(ARGV[3])
local ttlSeconds      = tonumber(ARGV[4])

-- Single shared clock. TIME yields { seconds, microseconds }.
local clock = redis.call('TIME')
local nowMillis = (tonumber(clock[1]) * 1000) + math.floor(tonumber(clock[2]) / 1000)

local stored = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(stored[1])
local lastRefill = tonumber(stored[2])

-- First contact, or the bucket expired while idle: start full. Starting empty would make
-- a tenant's very first request fail, which looks exactly like an outage to them.
if tokens == nil or lastRefill == nil then
  tokens = capacity
  lastRefill = nowMillis
end

-- Guard against a stored timestamp in the future. It should be impossible now that time
-- comes from the server, but a clock stepping backwards (NTP correction, VM migration)
-- could still produce one, and a negative elapsed value would subtract tokens.
local elapsedMillis = nowMillis - lastRefill
if elapsedMillis < 0 then
  elapsedMillis = 0
end

tokens = math.min(capacity, tokens + (elapsedMillis * refillPerSecond / 1000.0))

local allowed = 0
local retryAfterMillis = 0

if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  -- Report exactly how long to wait, so a well-behaved client backs off by the right
  -- amount instead of guessing and making the overload worse.
  local deficit = requested - tokens
  retryAfterMillis = math.ceil((deficit / refillPerSecond) * 1000.0)
end

redis.call('HSET', key, 'tokens', tokens, 'ts', nowMillis)
redis.call('EXPIRE', key, ttlSeconds)

-- Lua numbers convert to Redis integers by truncation, so tokens is floored deliberately:
-- reporting 4 remaining when 4.9 are available is the safe direction to round.
return { allowed, math.floor(tokens), retryAfterMillis }
