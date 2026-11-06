# ADR-0012: ingress-nginx balances by least connections

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** project author


## Context

ingress-nginx defaults to round-robin. The gateway's request durations are extremely
uneven.

## Decision

`nginx.ingress.kubernetes.io/load-balance: "ewma"` — peak-EWMA, a
least-connections-style policy weighted by observed latency.

## Rationale

Request duration spans roughly **four orders of magnitude**:

| Path | Duration |
|---|---|
| Tier-1 hit | ~4 ms |
| Tier-2 hit | ~15 ms |
| Upstream completion | 200 ms - 30 s |

Round-robin distributes request **count** evenly, which is only the same as
distributing **load** evenly when durations are similar. Here they are not. A pod that
happens to receive three long upstream calls is saturated while its neighbours idle,
and round-robin keeps sending it its fair share of new requests regardless — including
cache hits that would have been served in 4 ms by an idle pod but now queue behind a
30-second generation.

The effect is specifically a **p99 problem**: the mean looks fine, and the tail is
produced by fast requests queued behind slow ones. That is the least diagnosable shape
of latency problem, because the slow requests are not the ones complaining.

`ewma` tracks in-flight count and observed latency per upstream and prefers the
genuinely idle pod.

## Consequences

**Accepted costs**

- Slightly more controller state per upstream. Negligible.
- Less predictable distribution, so "requests per pod" is no longer a flat line and a
  dashboard showing uneven counts is now **expected** rather than a symptom.
- EWMA needs a few requests to converge, so the first moments after a scale-up are
  closer to random.

**Benefits**

- Cache hits are not queued behind long generations.
- p99 reflects the work rather than the queueing.
- Composes correctly with the RPS-based HPA: load lands where there is capacity, so the
  measured per-pod rate means something.

## Related settings

```yaml
nginx.ingress.kubernetes.io/proxy-buffering: "off"
```

Nginx buffers proxied responses by default, which holds an SSE stream until the handler
completes — silently converting streaming into a slow buffered response. Off is
mandatory, not a tuning preference.

Timeouts are 120 s at the ingress while the gateway's own TimeLimiter is 30 s, so the
**gateway** owns the deadline and returns a structured `504` rather than nginx
returning an opaque one.

## Note for load testing

ingress-nginx must not be the bottleneck, or a USL fit describes the ingress
controller. That is controlled by the controller ConfigMap
(`max-worker-connections`, `upstream-keepalive-connections`), not by these per-Ingress
annotations. Confirm saturation appears in the gateway's latency histogram, not in
`nginx_ingress_controller_nginx_process_connections`.
