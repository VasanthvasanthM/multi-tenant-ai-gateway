--[[
  Records tokens against a tenant's budget counter for the current period.

  WHY THIS IS A LUA SCRIPT
  ------------------------
  The operation is INCRBY followed by EXPIRE, and those must not be separable. INCRBY on a
  missing key creates it with no TTL. If the process died, lost its connection, or was
  descheduled between the two commands, the counter would survive forever - and because the
  key is period-stamped, a new one is created every period. The leak is unbounded in both
  tenants and time, and it is invisible: the gateway keeps working perfectly while Redis
  fills up. Lua makes the pair atomic, so a counter that exists always has an expiry.

  WHY THE TTL IS ONLY SET WHEN ABSENT
  -----------------------------------
  Re-issuing EXPIRE on every request would slide the window forward on every call, so an
  actively used counter would never expire. The TTL is therefore applied only when the key
  has none - on creation, or if a previous attempt somehow left it unset. The caller
  computes the TTL from the period boundary, so the counter outlives the period it belongs
  to by a deliberate grace margin and no longer.

  WHY THIS ALWAYS INCREMENTS, EVEN OVER THE LIMIT
  -----------------------------------------------
  Refusing to record usage that exceeded the budget would make the counter disagree with
  the usage ledger in Postgres, and the ledger is what the tenant is billed from. An
  over-limit tenant must show as over-limit, not as exactly at it. Enforcement is the
  caller's decision; this is an accounting primitive and takes no view on it.

  KEYS[1]  counter key, period-stamped and cluster hash-tagged by the caller
  ARGV[1]  tokens      - tokens to add; must be >= 0
  ARGV[2]  ttlSeconds  - applied only if the key currently has no expiry

  Returns the counter value after the increment.
--]]

local key        = KEYS[1]
local tokens     = tonumber(ARGV[1])
local ttlSeconds = tonumber(ARGV[2])

if tokens == nil or tokens < 0 then
  return redis.error_reply('tokens must be a non-negative number')
end

if ttlSeconds == nil or ttlSeconds < 1 then
  return redis.error_reply('ttlSeconds must be at least 1')
end

local used = redis.call('INCRBY', key, tokens)

-- TTL returns -1 for a key with no expiry and -2 for a key that does not exist. The key
-- certainly exists here, having just been incremented, so only -1 is reachable - but the
-- test is `< 0` rather than `== -1` so that a future Redis sentinel value cannot silently
-- skip the expiry and reintroduce the leak this script exists to prevent.
if redis.call('TTL', key) < 0 then
  redis.call('EXPIRE', key, ttlSeconds)
end

return used
