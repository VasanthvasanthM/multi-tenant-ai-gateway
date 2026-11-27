# The EXACT strings the benchmark wrote, copied from Tier2CostBenchmarkIT
def response(i):
    return ("Click 'Forgot password' on the sign-in page and request a new link. "
            "Reset links expire after 30 minutes for security. If link " + str(i)
            + " also fails, clear your browser cache and try an incognito window.")

def key(i):   return "{tenant:acme}:c1:" + format(i, '064x')
def value(i): return ('{"v":1,"c":"' + response(i) + '","m":"gpt-4o-mini","pt":22,"ct":48,'
                      '"p":"openai","ts":1763049600000}')

ks = [len(key(i).encode())   for i in range(500)]
vs = [len(value(i).encode()) for i in range(500)]
print("sample key  :", key(0))
print("sample value:", value(0))
print()
print(f"key   bytes: every one is {ks[0]}")
print(f"value bytes: min {min(vs)}  max {max(vs)}  mean {sum(vs)/len(vs):.1f}")
payload_each = ks[0] + sum(vs)/len(vs)
print(f"payload per entry = {ks[0]} + {sum(vs)/len(vs):.1f} = {payload_each:.1f} bytes")
print(f"payload for 500   = {(sum(ks)+sum(vs)):,} bytes")
print()
measured_kib = 0.60
measured_each = measured_kib * 1024
print(f"MEASURED: used_memory_dataset / DBSIZE = {measured_kib} KiB = {measured_each:.0f} bytes/entry")
print(f"so total dataset memory  = {measured_each*500:,.0f} bytes for 500 keys")
print(f"overhead per entry       = {measured_each:.0f} - {payload_each:.0f} = {measured_each-payload_each:.0f} bytes")
print(f"overhead as a share      = {100*(measured_each-payload_each)/measured_each:.0f}%")
print()
# jemalloc size classes that matter
classes = [8,16,32,48,64,80,96,112,128,160,192,224,256,320,384,448,512,640,768]
def rounded(n):
    for c in classes:
        if n <= c: return c
    return ((n+4095)//4096)*4096
print("jemalloc rounding of the two big allocations:")
print(f"  key   sds: 3 header + {ks[0]} + 1 null = {ks[0]+4} -> allocated {rounded(ks[0]+4)}")
v = round(sum(vs)/len(vs))
print(f"  value sds: 3 header + {v} + 1 null = {v+4} -> allocated {rounded(v+4)}")
print()
print("what the 2 KiB assumption would have predicted for 500 keys:",
      f"{2048*500:,} bytes  (actual {measured_each*500:,.0f})")
