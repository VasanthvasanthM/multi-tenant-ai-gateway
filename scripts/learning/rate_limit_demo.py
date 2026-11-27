import json, math
entry = {
  "v": 1,
  "c": ("Click \"Forgot password\" on the sign-in page and request a new link. "
        "Reset links expire after 30 minutes for security."),
  "m": "gpt-4o-mini",
  "pt": 14,
  "ct": 62,
  "p": "openai",
  "ts": 1764201600000,
}
s = json.dumps(entry, separators=(',', ':'))
print(s)
print()
key = "{tenant:acme}:c1:6c93c827c3013d4fe01e8bb9299941661452f632d74caac2a46a67e10840d5bf"
print("key bytes  ", len(key))
print("value bytes", len(s.encode()))
print("total      ", len(key) + len(s.encode()), "bytes of payload")

# retry-after, worked
cap, refill = 200, 50.0
for tokens, req in [(0.0, 1), (0.26, 1), (0.0, 5), (3.0, 10)]:
    deficit = req - tokens
    ms = math.ceil(deficit / refill * 1000.0)
    print(f"tokens={tokens:<5} requested={req:<3} deficit={deficit:<5} -> retryAfter={ms} ms")

print()
# refill, worked
tokens, last = 0.0, 0
for elapsed in [20, 100, 1000, 4000, 10000]:
    t = min(cap, tokens + elapsed * refill / 1000.0)
    print(f"idle {elapsed:>6} ms -> tokens {t:.1f}")
