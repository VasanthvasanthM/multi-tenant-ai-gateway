# Demo / recording script

A shot list for recording the system. Roughly **6–8 minutes**. Record with
**Win+G** (Windows Game Bar, built in) or OBS.

---

## Before you start — bring everything up

Four terminals, or one with `&`. **Every URL below needs its port-forward
running**; if a page is blank or 502s, the tunnel has died — restart it.

```bash
# 0. Cluster (skip if already running)
export PATH="/c/Users/vasan/AppData/Local/Microsoft/WinGet/Packages/k3d.k3d_Microsoft.Winget.Source_8wekyb3d8bbwe:$PATH"
k3d cluster start ai-gateway

# 1. Grafana  -> http://localhost:13000
kubectl port-forward -n monitoring svc/kps-grafana 13000:80 &

# 2. Alertmanager -> http://localhost:19093
kubectl port-forward -n monitoring svc/kps-kube-prometheus-stack-alertmanager 19093:9093 &

# 3. Gateway API for the tenant dashboard
kubectl port-forward svc/aigw-ai-gateway-gateway 18081:8080 &

# 4. Tenant dashboard -> http://localhost:5173
cd dashboard && VITE_GATEWAY_URL=http://localhost:18081 \
  VITE_API_KEY=sk-local-dev-acme-00000000000000000000 npx vite --port 5173
```

### Port-forwards die; run them in a retry loop

Every tunnel above goes through the API server, so all four drop together
whenever it hiccups — and it does, on this host. Rather than restarting them by
hand mid-take, run each in a loop:

```bash
pf() { local n="$1"; shift
  while true; do kubectl port-forward "$@" >>"/tmp/pf-$n.log" 2>&1
                 sleep 3; done; }
pf grafana      -n monitoring svc/kps-grafana 13000:80 &
pf alertmanager -n monitoring svc/kps-kube-prometheus-stack-alertmanager 19093:9093 &
pf gateway      svc/aigw-ai-gateway-gateway 18081:8080 &
pf prometheus   -n monitoring svc/kps-kube-prometheus-stack-prometheus 19090:9090 &
```

If one refuses to bind with *"Only one usage of each socket address"*, an
orphaned forward from a previous session still holds the port and `pkill` will
not reach it — it is a Windows process:

```bash
netstat -ano | grep 19093 | grep LISTENING | awk '{print $NF}' | xargs -r -n1 taskkill //F //PID
```

### Reseed the dashboard data

The dev Postgres runs on an **emptyDir**: a pod restart wipes every row, and
`tenant_usage_rollup` does not even exist in a gateway-only deployment. After
any cluster restart:

```bash
./scripts/seed-demo-data.sh acme 48     # 48 hours of per-hour rollups
./scripts/seed-demo-data.sh --clean     # remove afterwards
```

It derives hits from requests and then asserts no row exceeds 100%, because an
earlier hand-written seed drew both independently and put a **103.7% hit ratio**
on screen.

Check each one answers before you hit record:

```bash
curl -s -o /dev/null -w 'grafana %{http_code}\n'   http://localhost:13000/login
curl -s -o /dev/null -w 'dashboard %{http_code}\n' http://localhost:5173/
curl -s -o /dev/null -w 'gateway %{http_code}\n' -H 'Host: ai-gateway.local' \
  http://localhost:18080/actuator/health
```

**Sign into Grafana as `admin` / `admin`.** Anonymous access is Viewer, and
Viewers do not get the **Explore** menu — which you need for logs and traces.

**Tier 2 is not in the Helm chart.** It needs the glibc image, the model on the
node and four env vars; the deployment in this cluster is already patched for it
(`tier2-measured.md` §6 has the commands if it has to be rebuilt). Check before
recording:

```bash
kubectl logs deploy/aigw-ai-gateway-gateway | grep "embedding service ready"
# want: model=all-MiniLM-L6-v2 dimension=384 threads=2
# model=hashing-trigram-v1 means the fallback is active and Tier 2 serves nothing
```

**Give the pod two cores if Tier 2 is on.** At 1000m, embedding starves the
Redis client, the 500 ms timeout trips, and the log fills with
`rate limiter unavailable ... failing open` — 68 times in 200 lines. At 2000m
that goes to zero.

**Start traffic in a spare terminal** so the panels move while you talk:

```bash
export PATH="/c/Users/vasan/AppData/Local/Programs/k6:$PATH"
k6 run -e VUS=16 -e WARMUP=10s -e DURATION=600s \
  -e RUN_ID="demo-$(date +%s)" -e GATEWAY=http://localhost:18080 \
  -e HOST_HEADER=ai-gateway.local loadtest/k6/capacity.js
```

---

## Shot 1 — What it is (30 s, no screen)

> "A multi-tenant AI gateway. It sits in front of LLM providers and answers
> repeated prompts from a two-tier cache instead of paying for them again.
> Tier 1 is an exact hash in Redis, Tier 2 is a semantic match in pgvector."

## Shot 2 — A real request (45 s, terminal)

```bash
curl -s -H 'Host: ai-gateway.local' \
  -H 'Authorization: Bearer sk-local-dev-acme-00000000000000000000' \
  -H 'Content-Type: application/json' \
  -X POST http://localhost:18080/v1/chat/completions \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"What is the capital of France?"}]}'
```

Run it **twice**. Point at the `gateway` block in the response:

- first: `"served_by":"upstream"`, `"cache_hit":false`
- second: `"served_by":"tier1_exact"`, `"cache_hit":true`, and a much lower `latency_ms`

> "Same prompt, second time served from cache. That is the product in one shot."

> **Warm the cache path before you record this shot.** On a cold JVM the *first*
> cached response is the slowest thing in the whole demo — measured here at
> **158 ms against the upstream call's 40 ms**, because that request is the one
> paying to JIT-compile the cache path. Recorded cold, the shot appears to prove
> the cache makes things four times *slower*. After ~8 requests the same hit
> settles at **2–5 ms vs 40 ms**, which is the number worth filming:
>
> ```bash
> for i in $(seq 1 10); do curl -s -o /dev/null -H 'Host: ai-gateway.local' >   -H 'Authorization: Bearer sk-local-dev-acme-00000000000000000000' >   -H 'Content-Type: application/json' -X POST >   http://localhost:18080/v1/chat/completions >   -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"warmup"}]}'; done
> ```
>
> Then use a *fresh* prompt for the on-camera miss, so the first request is a
> genuine upstream call against an already-warm process.

## Shot 2b — The semantic cache, and its sharp edge (90 s, terminal)

This is the feature the product is named for, and it only became runnable on
2026-11-14 — the local image was Alpine, and ONNX Runtime needs glibc. Full
story in [`tier2-measured.md`](../architecture/tier2-measured.md).

**Flush Redis first**, so a Tier-1 hit is impossible and anything served has to
have come from the vectors:

```bash
kubectl exec deploy/redis -- redis-cli FLUSHALL
```

Then ask the same question in different words:

```bash
curl -s -H 'Host: ai-gateway.local' -H 'Authorization: Bearer sk-local-dev-acme-00000000000000000000'   -H 'Content-Type: application/json' -X POST http://localhost:18080/v1/chat/completions   -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"How do I cancel my subscription?"}]}'
# then: "steps to cancel my subscription"  -> served_by: tier2_semantic
```

> "Different words, different hash, so Tier 1 cannot help — and Redis is empty
> anyway. That came out of pgvector, matched on meaning."

**Then show the honest half.** Pick one:

```sql
-- in: kubectl exec -it deploy/postgres -- psql -U aigw -d aigw
SELECT round((-(a.embedding <#> b.embedding))::numeric,4) AS sim,
       left(replace(a.prompt_text,'user: ',''),46) AS a,
       left(replace(b.prompt_text,'user: ',''),46) AS b
  FROM prompt_cache a JOIN prompt_cache b ON a.id < b.id
 WHERE a.prompt_text LIKE '%onboarding process%'
   AND b.prompt_text LIKE '%onboarding process%'
 ORDER BY sim DESC LIMIT 3;
```

> "Two prompts asking about *different* requirements score 0.9987 — higher than
> any real paraphrase in the corpus. The cache would answer one with the other.
> A higher threshold makes that strictly worse, because at 0.99 these are the
> only things that still match. Which is why shadow mode is the default: query
> Tier 2, log what it would have served, serve nothing, and read the false-hit
> rate before you trust it."

That single slide is worth more than the whole happy path — it shows the
limitation was measured rather than discovered by a user.

## Shot 3 — Tenant dashboard (60 s) — http://localhost:5173

React 19 + TypeScript + Vite. Walk the top row: hit ratio, requests, tokens
saved, estimated saving. Click **7d** to show it re-querying. Expand
**Table view**.

> "Per-tenant usage. The footer says the saving is a calculation from a
> configured token price, not a measured invoice — the UI distinguishes what it
> measured from what it inferred."

**Say that the figures here are seeded** if they still are. Do not let a viewer
assume it is live traffic.

## Shot 4 — Metrics (75 s) — Grafana → Dashboards

**AI Gateway – RED**: request rate, error ratio, p99, in flight; then request
rate by pod.

**AI Gateway – Cache economics**: hit ratio both tiers, upstream calls avoided,
lookups by tier and outcome, cache lookup p99 by tier.

> "Hit ratio by tier, not just overall — Tier 2's denominator is only what Tier 1
> missed, so the two are not comparable and the panel says so."

## Shot 5 — Logs (45 s) — Explore → **Loki**

```
{component="gateway"}
{component="gateway"} | level="WARN"
```

> "Alloy ships these with the same namespace and pod labels the metrics carry,
> so you can pivot from a spike to the lines behind it."

## Shot 6 — Traces (45 s) — Explore → **Tempo** → Search

Service Name `ai-gateway-gateway` → **Run query**. Show the results **table**
first — real traces with a spread of durations — then open one.

> "Tracing is wired end to end: OTLP export on 4318, Tempo ingesting, Grafana
> querying. Sampling is 10% by default and 100% here."

> ### Do not say "expand the spans" — there is only one
>
> Every trace contains exactly **one** span: the Spring HTTP server span. There
> are no children for the Tier-1 lookup, the Tier-2 vector search, the embedding
> inference or the upstream call, because there is **no custom instrumentation
> anywhere in the codebase** — no `@Observed`, no `Observation`, no
> `tracer.nextSpan` in `gateway/` or `embedding/`. Only Spring's automatic HTTP
> instrumentation is active.
>
> What the span does carry: `service.name`, `uri`, `method`, `status`,
> `outcome`, `exception` and the duration. That is enough to prove the pipeline
> works and to find slow requests; it is not enough to say *where* a request
> spent its time, which is what anyone watching will assume a trace shows.
>
> So show the table and the span attributes, and if asked, say plainly that
> per-tier spans are not instrumented yet. Claiming a waterfall that is not
> there is the one thing that would undermine the rest of the demo.
>
> **The honest version is also the more interesting one:** this is exactly the
> gap that would have made `tier2-measured.md` §4 easy — the CPU cost of
> embedding had to be inferred from cgroup counters and p50 shifts, when a child
> span around the inference call would have shown it directly.

## Shot 7 — Alerting (45 s) — Slack + http://localhost:19093

Show `#ai-gateway-alerts`: `Watchdog`, a `:rotating_light:` critical, a
`RESOLVED`, and the grouped `TargetDown` covering three targets in one message.

> "Grouped by alertname and severity, so a cluster-wide problem is one message
> rather than one per pod. Repeat interval is four hours — alerts that repeat
> every five minutes get muted, and a muted channel is worse than no alerting.
> The webhook comes from a Kubernetes Secret, never from the repo."

## Shot 8 — The engineering (90 s, code/docs)

Pick **two**, not all:

- **`docs/architecture/scaling-model.md` §4a** — the measurement: one pod at
  ≥271 rps, and the arithmetic to 488 pods for 2 billion requests/day, with
  every assumption listed and labelled (M) or (C).
- **CI** — 14 jobs green: unit, integration, coverage gate, mutation testing,
  SonarCloud, Trivy, image builds.
- **`loadtest/scripts/capacity-sweep.sh`** — the gates that *discard* a
  measurement: rate-limited, server errors, hit ratio below floor, replica count
  mismatch.

## Shot 9 — Close (30 s)

> "One pod measured at 271 requests a second. The fleet arithmetic to two
> billion a day is in the repo with its assumptions stated, and the one
> coefficient still unmeasured is named rather than hidden. That is the claim —
> no more than that."

---

## Recording notes

- **1920×1080**, browser zoom ~110% so text is readable when compressed.
- Close unrelated tabs; the port-forward URLs are plain `localhost`.
- If a page stalls mid-take, the port-forward died — restart it and re-shoot.
  It happens on this cluster.
- Keep k6 running the whole time so no panel is flat.
- Do **not** show the Slack webhook URL, the dev API key, or
  `deploy/k8s/dev-infra.yaml` on screen.
