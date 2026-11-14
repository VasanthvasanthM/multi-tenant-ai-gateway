# Observability: verification queries

The **dashboard** queries are already version-controlled — 17 panel expressions in
[`deploy/grafana/dashboards/gateway-red.json`](../../deploy/grafana/dashboards/gateway-red.json)
and 9 in
[`cache-economics.json`](../../deploy/grafana/dashboards/cache-economics.json).
`scripts/observability-up.sh` loads both as ConfigMaps labelled
`grafana_dashboard=1`, so Grafana picks them up automatically and the dashboards
reproduce from git on any cluster.

This file holds the **other** queries — the ad-hoc ones used to prove the pipeline
works and to diagnose it when it does not. They were run repeatedly during the
2026-11-13 bring-up and are written down here so the next person does not
reconstruct them.

## Reaching Prometheus and Grafana

```bash
kubectl port-forward -n monitoring svc/kps-kube-prometheus-stack-prometheus 19090:9090 &
kubectl port-forward -n monitoring svc/kps-grafana 13000:80 &
```

Grafana has anonymous viewing on, so no login is needed. Querying **through**
Grafana rather than Prometheus is the better test, because it exercises the
datasource wiring as well as the data:

```bash
curl -s -G --data-urlencode 'query=count({__name__=~"gateway_.*"})' \
  http://localhost:13000/api/datasources/proxy/uid/prometheus/api/v1/query
```

A healthy answer during the bring-up was `480` series.

## Is anything of ours being scraped at all?

```promql
count({__name__=~"gateway_.*"})
```

Zero means the `ServiceMonitor` is missing or not selected — not that the gateway
is broken. Check the target directly:

```bash
curl -s 'http://localhost:19090/api/v1/targets?state=active' \
  | python -c "import json,sys; [print(t['scrapePool'], t['health']) for t in json.load(sys.stdin)['data']['activeTargets'] if 'gateway' in t['scrapePool']]"
```

Expect `serviceMonitor/default/aigw-ai-gateway-gateway/0 up`.

> **`monitoring.serviceMonitor.enabled` defaults to `false`.** The chart ships a
> ServiceMonitor that no deployment creates unless this is turned on, and every
> dashboard then renders empty with no error anywhere. This cost a bring-up.

## The four numbers worth checking by hand

```promql
# Request rate
sum(rate(gateway_request_duration_seconds_count[1m]))

# Cache hit ratio — the figure the product is sold on
sum(rate(gateway_cache_lookup_total{result="hit"}[5m]))
  / clamp_min(sum(rate(gateway_cache_lookup_total[5m])), 0.001)

# Hit ratio split by tier (tier2's denominator is only what tier1 missed)
sum by (tier) (rate(gateway_cache_lookup_total{result="hit"}[5m]))
  / clamp_min(sum by (tier) (rate(gateway_cache_lookup_total[5m])), 0.001)

# p99 latency
histogram_quantile(0.99, sum(rate(gateway_request_duration_seconds_bucket[5m])) by (le))
```

`clamp_min` on every denominator is deliberate: an idle window divides by zero and
a panel showing `NaN` reads as broken rather than as quiet.

## Degradation that does not raise an error

These are the counters that move when the gateway is quietly serving worse. None
of them fail a request, which is exactly why they need watching.

```promql
# Tier 1 timing out against Redis. Each one becomes an upstream call, which
# deepens the queue, which causes more timeouts. Throughput collapses rather
# than plateauing, and the only visible symptom is the hit ratio falling.
sum(rate(gateway_ratelimit_degraded_total[5m]))

# Tier 2 skipped because the prompt was over the length limit
sum(rate(gateway_cache_tier2_skipped_total[5m]))

# Shadow mode: what Tier 2 WOULD have served. The false-hit measurement.
sum(rate(gateway_cache_shadow_would_have_served_total[5m]))

# Budget gate, in shadow mode — refusals that would have happened
sum(rate(gateway_budget_shadow_would_have_refused_total[5m]))

# Read replica lag, per shard
max by (shard) (gateway_replica_lag_seconds)
```

## The custom metrics API and the HPA

Registered is **not** the same as serving, and the difference is invisible until
an HPA silently falls back to CPU.

```bash
# Does the adapter actually return a NUMBER?
kubectl get --raw \
  "/apis/custom.metrics.k8s.io/v1beta1/namespaces/default/pods/*/gateway_requests_per_second"

# Is the HPA using it, or quietly on CPU?
kubectl get hpa          # TARGETS must show a number, never <unknown>
kubectl get hpa aigw-ai-gateway-gateway -o jsonpath='{.status.conditions}'
```

The condition to look for is
`ScalingActive=True, reason=ValidMetricFound ... from pods metric gateway_requests_per_second`.
An HPA whose metric is unavailable reports `<unknown>`, falls back to CPU **without
erroring**, and on an I/O-bound gateway produces a flat replica count that reads as
evidence it does not need to scale.

## When the stack itself is the problem

On a small cluster the monitoring stack can destabilise what it is monitoring.
Observed on a 6-CPU VM: node-exporter CrashLooping (35 restarts), the operator at
8 restarts and Prometheus at 19, which took the API server to
`TLS handshake timeout` — and since `kubectl port-forward` tunnels *through* the
API server, Grafana failed mid-page-load with "failed to load its application
files". That reads as a Grafana `root_url` misconfiguration and is not one.

```bash
kubectl get pods -n monitoring            # look at RESTARTS, not just Running
kubectl get --raw /readyz                 # the API server itself

# node-exporter cannot read /proc properly under WSL2 and is not needed for the
# gateway dashboards, which scrape the pod's own /actuator/prometheus
kubectl delete daemonset -n monitoring kps-prometheus-node-exporter
kubectl scale deploy -n monitoring kps-kube-prometheus-stack-operator --replicas=0
```

Verify the fix end to end rather than by the page returning 200 — fetch an asset,
because the HTML arrives before the tunnel dies:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:13000/login
curl -s http://localhost:13000/login | grep -oE '/public/build/[^"]+\.js' | head -1
```

---

# Logs and traces: the click path

PromQL above answers "how much" and "how bad". This section is the other two
pillars — "what did it actually say" and "where did the time go".

## 0. Before anything: sign in, and check the tunnel

```bash
kubectl port-forward -n monitoring svc/kps-grafana 13000:80 &
curl -s -o /dev/null -w 'grafana %{http_code}
' http://localhost:13000/login
```

Then open <http://localhost:13000> and **sign in as `admin` / `admin`**.

> **If there is no "Explore" item in the left menu, you are not signed in.**
> Anonymous access is enabled with the **Viewer** role, and Viewers do not get
> Explore. Dashboards still render, which is what makes this confusing: the
> metrics work, so the stack looks healthy, and the two pillars you came for are
> simply absent from the menu. This is the single most common reason "I cannot
> find the logs".

A blank page or a 502 is the port-forward, not Grafana. The tunnel runs through
the API server and dies whenever it hiccups — restart it rather than debugging
Grafana.

## 1. Logs — Explore with the Loki datasource

**Explore** (compass icon, left menu) → datasource picker at the top left →
**Loki** → make sure the mode toggle is on **Code**, not Builder → type the
query → **Run query** (top right, or Shift+Enter).

Start here. The label is `component`, not `app` or `job`:

```logql
{component="gateway"}
```

Then narrow:

```logql
# just warnings and errors
{component="gateway"} |= "WARN"
{component="gateway"} |~ "WARN|ERROR"

# the cache deciding things - which tier served, and what it refused
{component="gateway"} |= "tier-2 near miss"
{component="gateway"} |= "EMBEDDING FALLBACK ACTIVE"

# the two degradations that do not raise an error anywhere
{component="gateway"} |= "limiter unavailable"
{component="gateway"} |= "failing open"

# one specific pod
{component="gateway", instance=~".*gateway-797f965c5c.*"}

# count errors per minute - switch the panel to a graph
sum(count_over_time({component="gateway"} |~ "ERROR" [1m]))
```

`|=` is a literal substring match and `|~` is a regex. Use `|=` where you can;
it is much cheaper.

**Time range matters more than the query.** The picker defaults to *Last 1 hour*.
If the gateway restarted recently, or traffic only ran for a few minutes, widen
it to 6 or 12 hours before concluding there are no logs. An empty result with a
valid query is almost always the time range.

To confirm Loki has anything at all, without guessing labels:

```bash
curl -s "http://admin:admin@localhost:13000/api/datasources/proxy/uid/loki/loki/api/v1/label/component/values"
# expect: {"status":"success","data":["gateway"]}
```

If that returns an empty list, the problem is **Alloy**, not Loki or Grafana —
Alloy is the agent that tails pod logs and ships them. Check it is running:

```bash
kubectl get pods -n monitoring | grep alloy      # want 2/2 on each node
kubectl logs -n monitoring daemonset/alloy -c alloy --tail=30
```

## 2. Traces — Explore with the Tempo datasource

**Explore** → datasource **Tempo** → the query-type tabs are **Search**,
**TraceQL**, **Service Graph**.

### Search tab (the easy path)

Set **Service Name** to `ai-gateway-gateway`, leave everything else empty, press
**Run query**. You get a table of recent traces; click any **Trace ID** to open
the flame graph, then expand spans to see where the time went.

Useful filters on that tab:

- **Span Name** `http post /v1/chat/completions` — only real gateway requests,
  not actuator probes
- **Min Duration** `500ms` — only the slow ones, which is usually the question

### TraceQL tab (when you know what you are hunting)

```traceql
{ resource.service.name = "ai-gateway-gateway" }
{ name = "http post /v1/chat/completions" }
{ duration > 500ms }
{ resource.service.name = "ai-gateway-gateway" && duration > 1s }
{ status = error }
```

### Verifying traces exist without the UI

```bash
NOW=$(date +%s)
curl -s -G "http://admin:admin@localhost:13000/api/datasources/proxy/uid/tempo/api/search"   --data-urlencode "start=$((NOW-1800))" --data-urlencode "end=$NOW"   --data-urlencode 'limit=5'
```

A JSON body with a `traces` array means tracing works end to end. **An empty
body or an HTML error page means Tempo is not up** — check
`kubectl get pods -n monitoring | grep tempo` before touching Grafana.

### If there are no traces, it is almost certainly the exporter port

Tracing is off by default, and the one configuration mistake that produces
"everything looks right, zero traces" is the port:

```bash
kubectl set env deploy/aigw-ai-gateway-gateway   MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo.monitoring.svc:4318/v1/traces   MANAGEMENT_TRACING_SAMPLING_PROBABILITY=1.0
```

**4318 and the `/v1/traces` path — not 4317.** Spring Boot drives the OTLP
*HTTP* exporter. 4317 is the gRPC port; pointed there, the dependency is
present, sampling is set, the endpoint is populated, and spans silently fail to
export. Confirm what the pod actually has:

```bash
kubectl get deploy aigw-ai-gateway-gateway   -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}={.value}{"
"}{end}'   | grep -i -E "otlp|sampling"
```

Sampling is 10% by default. At low traffic that means most requests produce no
trace at all, so set it to `1.0` while demonstrating.

**And there must be traffic.** Traces are not retained long. If nothing has hit
the gateway in the last few minutes there is nothing to find — send a request,
then look:

```bash
curl -s -o /dev/null -H 'Host: ai-gateway.local'   -H 'Authorization: Bearer sk-local-dev-acme-00000000000000000000'   -H 'Content-Type: application/json' -X POST   http://localhost:18080/v1/chat/completions   -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"trace me"}]}'
```

## 3. Pivoting between the three

This is the point of wiring them to the same labels.

- **Metric spike -> the log lines behind it.** Alloy ships logs with the same
  `namespace` and `pod` labels the metrics carry, so a pod name from a
  Prometheus graph pastes straight into a LogQL selector.
- **Trace -> its logs.** The Tempo datasource is configured with a Loki
  correlation, so an open span offers a **Logs** link for that request.
- **Log line -> its trace.** Gateway log lines carry `[traceId,spanId]` in the
  `[,]` field after the timestamp. Copy the trace ID, switch to Tempo, and paste
  it into the Search tab's **Trace ID** box.

> A log line showing an empty `[,]` has no trace context — it was emitted
> outside a request (startup, a scheduled poll, the outbox relay). That is
> expected, not a bug.

## 4. Quick triage order

When "the logs are not showing", check in this order. Each is cheaper than the
one after it:

1. Is there an **Explore** menu item? If not, sign in as `admin`.
2. Does `curl localhost:13000/api/health` return 200? If not, restart the
   port-forward.
3. Widen the **time range** to 12 hours.
4. `kubectl get pods -n monitoring` — is Loki/Tempo/Alloy actually `Running`?
5. Only then suspect the query.
