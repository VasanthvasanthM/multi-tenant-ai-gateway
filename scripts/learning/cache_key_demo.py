import hashlib, struct

def put_str(h, s):
    b = s.encode('utf-8')
    h.update(struct.pack('>i', len(b)))
    h.update(b)

def put_int(h, v):  h.update(struct.pack('>i', v))
def put_long(h, v): h.update(struct.pack('>q', v))
def bits(d):        return struct.unpack('>q', struct.pack('>d', d))[0]

def derive(tenant, model, temp, topp, maxtok, tools, messages):
    h = hashlib.sha256()
    h.update(bytes([1]))                 # SCHEMA_VERSION
    put_str(h, tenant)
    put_str(h, model)
    put_long(h, bits(temp))
    put_long(h, bits(topp))
    put_int(h, maxtok)
    put_str(h, tools)
    put_int(h, len(messages))
    for role, content in messages:
        put_str(h, role)
        put_str(h, content)
    return h.hexdigest()

msgs = [("user", "How do I reset my password?")]
k = derive("acme", "gpt-4o-mini", 0.0, 1.0, 500, "", msgs)
print("REAL KEY        ", k)
print("redis key       ", "{tenant:acme}:c1:" + k)
print("short form      ", k[:16])
print("length          ", len(k), "hex chars =", len(k)*4, "bits")

# one letter changed in the prompt
k2 = derive("acme", "gpt-4o-mini", 0.0, 1.0, 500, "", [("user", "How do I reset my password!")])
print()
print("'?' -> '!'      ", k2)

# different tenant, same prompt
k3 = derive("globex", "gpt-4o-mini", 0.0, 1.0, 500, "", msgs)
print("tenant globex   ", k3)

# the collision demo: naive concatenation vs length-prefixed
naive_a = hashlib.sha256(("acme"  + "gpt-4").encode()).hexdigest()
naive_b = hashlib.sha256(("acmeg" + "pt-4" ).encode()).hexdigest()
print()
print("NAIVE  acme|gpt-4 ", naive_a)
print("NAIVE  acmeg|pt-4 ", naive_b)
print("collide?          ", naive_a == naive_b)

def lenpref(a, b):
    h = hashlib.sha256(); put_str(h, a); put_str(h, b); return h.hexdigest()
print("FIXED  acme|gpt-4 ", lenpref("acme", "gpt-4"))
print("FIXED  acmeg|pt-4 ", lenpref("acmeg", "pt-4"))
print("collide?          ", lenpref("acme","gpt-4") == lenpref("acmeg","pt-4"))
