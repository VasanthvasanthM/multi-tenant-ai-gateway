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
