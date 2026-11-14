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

Check each one answers before you hit record:

```bash
curl -s -o /dev/null -w 'grafana %{http_code}\n'   http://localhost:13000/login
curl -s -o /dev/null -w 'dashboard %{http_code}\n' http://localhost:5173/
curl -s -o /dev/null -w 'gateway %{http_code}\n' -H 'Host: ai-gateway.local' \
  http://localhost:18080/actuator/health
```

**Sign into Grafana as `admin` / `admin`.** Anonymous access is Viewer, and
Viewers do not get the **Explore** menu — which you need for logs and traces.

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

Open a trace, expand the spans.

> "10% sampling by default, 100% here. Tempo is wired to Loki, so a span links
> straight to that request's logs."

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
