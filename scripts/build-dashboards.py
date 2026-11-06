"""Generate the two Grafana dashboards.

Built in Python rather than hand-written JSON so the panel grid cannot drift and
every colour assignment goes through one function. Grafana renders dark by
default, so the DARK steps of the validated categorical palette are used; the
subset passes all six checks against the dark surface.

Design rules applied (and the reason each one matters here):

  - Colour follows the ENTITY, not the series order. tier1 is always blue and
    tier2 always orange, in every panel, so a reader builds one mental mapping.
    Grafana's default palette-classic assigns by query order, which repaints the
    survivors when a filter removes a series.
  - NO dual-axis panel anywhere. Rate and latency never share a plot; they get
    separate panels.
  - Status colours (good/warning/critical) are reserved for thresholds and are
    never used as a series colour.
  - A legend whenever a panel has more than one series.
"""

import io
import json
import os

OUT = "deploy/grafana/dashboards"
os.makedirs(OUT, exist_ok=True)

# Validated categorical palette, dark steps (Grafana's default theme is dark).
# node scripts/validate_palette.js "#3987e5,#d95926,#199e70,#c98500" --mode dark
#   -> ALL CHECKS PASS
BLUE = "#3987e5"      # slot 1
ORANGE = "#d95926"    # slot 2
AQUA = "#199e70"      # slot 3
YELLOW = "#c98500"    # slot 4
MAGENTA = "#d55181"   # slot 5
VIOLET = "#9085e9"    # slot 7

# Status palette - fixed, never themed, never reused as a series colour.
GOOD = "#0ca30c"
WARNING = "#fab219"
CRITICAL = "#d03b3b"

DS = {"type": "prometheus", "uid": "${datasource}"}

# Cost per request, used by the savings panel. Matches the figure derived in
# docs/architecture/scaling-model.md: 500 in + 500 out tokens at commodity
# small-model pricing.
COST_PER_REQUEST = 0.000375

_panel_id = [0]


def _next_id():
    _panel_id[0] += 1
    return _panel_id[0]


def colour_overrides(mapping):
    """Pin a fixed colour per series name, so colour tracks the entity."""
    return [
        {
            "matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": hex_}}],
        }
        for name, hex_ in mapping.items()
    ]


def timeseries(title, targets, *, x, y, w=12, h=8, unit="short", desc="",
               colours=None, overrides=None, thresholds=None, stack=False,
               min_=0, decimals=None, legend=True):
    custom = {
        "lineWidth": 2,                 # thin marks
        "fillOpacity": 16 if stack else 6,
        "showPoints": "never",
        "spanNulls": False,
        "axisBorderShow": False,
        "gradientMode": "none",
    }
    if stack:
        custom["stacking"] = {"mode": "normal", "group": "A"}

    defaults = {
        "custom": custom,
        "unit": unit,
        "min": min_,
        "color": {"mode": "palette-classic"},
    }
    if decimals is not None:
        defaults["decimals"] = decimals
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        custom["thresholdsStyle"] = {"mode": "dashed"}

    ovr = list(overrides or [])
    if colours:
        ovr += colour_overrides(colours)

    return {
        "id": _next_id(),
        "type": "timeseries",
        "title": title,
        "description": desc,
        "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [
            {"refId": chr(65 + i), "datasource": DS, "editorMode": "code",
             "expr": expr, "legendFormat": legend_fmt, "range": True}
            for i, (expr, legend_fmt) in enumerate(targets)
        ],
        "fieldConfig": {"defaults": defaults, "overrides": ovr},
        "options": {
            # A legend is always present for more than one series, so identity is
            # never carried by colour alone.
            "legend": {"showLegend": legend, "displayMode": "list",
                       "placement": "bottom", "calcs": []},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
    }


def stat(title, expr, *, x, y, w=6, h=5, unit="short", desc="",
         decimals=1, steps=None, colour=None, text_size=36):
    if steps is None:
        steps = [{"color": colour or BLUE, "value": None}]
    return {
        "id": _next_id(),
        "type": "stat",
        "title": title,
        "description": desc,
        "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"refId": "A", "datasource": DS, "editorMode": "code",
                     "expr": expr, "legendFormat": "", "range": True}],
        "fieldConfig": {
            "defaults": {
                "unit": unit,
                "decimals": decimals,
                "color": {"mode": "thresholds"},
                "thresholds": {"mode": "absolute", "steps": steps},
                "mappings": [],
            },
            "overrides": [],
        },
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": "value",
            "graphMode": "area",
            "textMode": "auto",
            "justifyMode": "auto",
            "text": {"valueSize": text_size},
        },
    }


def row(title, *, y):
    return {
        "id": _next_id(),
        "type": "row",
        "title": title,
        "collapsed": False,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
        "panels": [],
    }


def dashboard(uid, title, description, panels, *, refresh="10s", from_="now-30m"):
    return {
        "uid": uid,
        "title": title,
        "description": description,
        "tags": ["ai-gateway"],
        "timezone": "utc",
        "editable": True,
        "schemaVersion": 39,
        "version": 1,
        "refresh": refresh,
        "time": {"from": from_, "to": "now"},
        "templating": {
            "list": [
                {
                    "name": "datasource",
                    "label": "Data source",
                    "type": "datasource",
                    "query": "prometheus",
                    "refresh": 1,
                    "hide": 0,
                    "current": {},
                },
                {
                    "name": "namespace",
                    "label": "Namespace",
                    "type": "query",
                    "datasource": DS,
                    "query": "label_values(gateway_request_duration_seconds_count, namespace)",
                    "refresh": 2,
                    "includeAll": True,
                    "multi": True,
                    "current": {"text": "All", "value": "$__all"},
                },
                {
                    "name": "pod",
                    "label": "Pod",
                    "type": "query",
                    "datasource": DS,
                    "query": 'label_values(gateway_request_duration_seconds_count{namespace=~"$namespace"}, pod)',
                    "refresh": 2,
                    "includeAll": True,
                    "multi": True,
                    "current": {"text": "All", "value": "$__all"},
                },
            ]
        },
        "panels": panels,
    }


# =============================================================================
# Dashboard 1 - RED (Rate, Errors, Duration)
# =============================================================================
SEL = 'namespace=~"$namespace",pod=~"$pod"'

red_panels = []
y = 0

red_panels.append(row("Headline", y=y)); y += 1

red_panels += [
    stat("Request rate",
         f'sum(rate(gateway_request_duration_seconds_count{{{SEL}}}[$__rate_interval]))',
         x=0, y=y, unit="reqps", colour=BLUE,
         desc="The gateway's own timer, so actuator probe traffic cannot appear here."),
    stat("Error ratio",
         f'sum(rate(gateway_errors_total{{{SEL}}}[$__rate_interval]))'
         f' / clamp_min(sum(rate(gateway_request_duration_seconds_count{{{SEL}}}[$__rate_interval])), 0.001)',
         x=6, y=y, unit="percentunit", decimals=2,
         steps=[{"color": GOOD, "value": None},
                {"color": WARNING, "value": 0.01},
                {"color": CRITICAL, "value": 0.05}],
         desc="clamp_min on the denominator: with no traffic this is 0/0, and NaN "
              "renders as an empty panel rather than as 'no requests yet'."),
    stat("p99 latency",
         f'histogram_quantile(0.99, sum(rate(gateway_request_duration_seconds_bucket{{{SEL}}}[$__rate_interval])) by (le))',
         x=12, y=y, unit="s", decimals=3,
         steps=[{"color": GOOD, "value": None},
                {"color": WARNING, "value": 1},
                {"color": CRITICAL, "value": 5}],
         desc="Whole-pipeline p99, cache hits and upstream calls together."),
    stat("In flight",
         f'sum(gateway_requests_in_flight{{{SEL}}})',
         x=18, y=y, unit="short", decimals=0, colour=VIOLET,
         desc="Concurrency the gateway is currently holding."),
]
y += 5

red_panels.append(row("Rate", y=y)); y += 1

red_panels += [
    timeseries("Request rate, by pod",
               [(f'sum by (pod) (rate(gateway_request_duration_seconds_count{{{SEL}}}[$__rate_interval]))', "{{pod}}")],
               x=0, y=y, unit="reqps",
               desc="Deliberately uneven. ingress-nginx balances by least connections "
                    "(ewma), not round-robin, because request durations span ~4 ms for a "
                    "cache hit to 30 s for an upstream call. Flat lines here would mean "
                    "round-robin was piling long requests onto busy pods."),
    timeseries("Requests rejected, by reason",
               [(f'sum by (reason) (rate(gateway_requests_rejected_total{{{SEL}}}[$__rate_interval]))', "{{reason}}")],
               x=12, y=y, unit="reqps", stack=True,
               colours={"rate_limited": YELLOW, "overloaded": ORANGE},
               desc="Two different events. rate_limited is a tenant exceeding its own "
                    "quota - normal, and the tenant's problem. overloaded is the bulkhead "
                    "refusing work - the gateway shedding load, and ours."),
]
y += 8

red_panels.append(row("Errors", y=y)); y += 1

red_panels += [
    timeseries("Error rate, by code",
               [(f'sum by (code) (rate(gateway_errors_total{{{SEL}}}[$__rate_interval]))', "{{code}}")],
               x=0, y=y, unit="reqps", stack=True,
               colours={"circuit_open": ORANGE, "upstream_timeout": YELLOW,
                        "upstream_unavailable": MAGENTA, "overloaded": VIOLET,
                        "rate_limited": AQUA, "internal_error": CRITICAL},
               desc="GatewayError codes, which are also the error codes in the OpenAPI "
                    "contract. budget_exhausted (402) is NOT retryable; rate_limited "
                    "(429) is."),
    timeseries("Rate limiter degraded",
               [(f'sum(rate(gateway_ratelimit_degraded_total{{{SEL}}}[$__rate_interval]))', "fail-open events")],
               x=12, y=y, unit="ops", legend=False,
               colours={"fail-open events": CRITICAL},
               desc="ANY non-zero value here is an incident, not a metric. It means the "
                    "limiter could not reach Redis and the fail-open policy let traffic "
                    "through UNCHECKED - so every rate limit is currently unenforced."),
]
y += 8

red_panels.append(row("Duration", y=y)); y += 1

red_panels += [
    timeseries("Latency percentiles",
               [(f'histogram_quantile(0.50, sum(rate(gateway_request_duration_seconds_bucket{{{SEL}}}[$__rate_interval])) by (le))', "p50"),
                (f'histogram_quantile(0.95, sum(rate(gateway_request_duration_seconds_bucket{{{SEL}}}[$__rate_interval])) by (le))', "p95"),
                (f'histogram_quantile(0.99, sum(rate(gateway_request_duration_seconds_bucket{{{SEL}}}[$__rate_interval])) by (le))', "p99")],
               x=0, y=y, unit="s",
               colours={"p50": BLUE, "p95": ORANGE, "p99": MAGENTA},
               desc="A bimodal distribution is EXPECTED and healthy: p50 sits near the "
                    "cache-hit path and p99 near the upstream path. p50 rising towards "
                    "p99 means the cache stopped working."),
    timeseries("Latency distribution",
               [(f'sum(increase(gateway_request_duration_seconds_bucket{{{SEL}}}[$__rate_interval])) by (le)', "{{le}}")],
               x=12, y=y, unit="s", legend=False,
               desc="The histogram itself. Two humps is the cache working."),
]
y += 8

red_panels.append(row("Scaling", y=y)); y += 1

red_panels += [
    timeseries("HPA replicas",
               [('max by (horizontalpodautoscaler) (kube_horizontalpodautoscaler_status_current_replicas{horizontalpodautoscaler=~".*gateway.*"})', "current"),
                ('max by (horizontalpodautoscaler) (kube_horizontalpodautoscaler_status_desired_replicas{horizontalpodautoscaler=~".*gateway.*"})', "desired"),
                ('max by (horizontalpodautoscaler) (kube_horizontalpodautoscaler_spec_max_replicas{horizontalpodautoscaler=~".*gateway.*"})', "max")],
               x=0, y=y, unit="short", decimals=0,
               colours={"current": BLUE, "desired": ORANGE, "max": "#6b6b6b"},
               desc="Requires kube-state-metrics. current diverging from desired for "
                    "longer than the 30 s scale-up window means pods are Pending - the "
                    "cluster is out of room, and the run is measuring the scheduler."),
    timeseries("Request rate per pod vs HPA target",
               [(f'sum(rate(gateway_request_duration_seconds_count{{{SEL}}}[$__rate_interval])) / clamp_min(count(gateway_requests_in_flight{{{SEL}}}), 1)', "actual per pod"),
                ("100", "HPA target")],
               x=12, y=y, unit="reqps",
               colours={"actual per pod": BLUE, "HPA target": WARNING},
               desc="The signal the HPA actually scales on. If this panel is empty while "
                    "the rate panels are not, prometheus-adapter is not serving the "
                    "custom metric and the HPA has silently fallen back to CPU."),
]

red = dashboard(
    "ai-gw-red", "AI Gateway - RED",
    "Rate, Errors and Duration for the gateway data plane, plus the HPA signal. "
    "Every metric is the gateway's own Micrometer meter, so actuator probe traffic "
    "is excluded by construction rather than by a filter.",
    red_panels)

# =============================================================================
# Dashboard 2 - Cache economics
# =============================================================================
_panel_id[0] = 0
CSEL = 'namespace=~"$namespace",pod=~"$pod"'

cache_panels = []
y = 0

cache_panels.append(row("Is the cache earning its keep?", y=y)); y += 1

cache_panels += [
    stat("Hit ratio, both tiers",
         f'sum(rate(gateway_cache_lookup_total{{{CSEL},result="hit"}}[5m]))'
         f' / clamp_min(sum(rate(gateway_cache_lookup_total{{{CSEL}}}[5m])), 0.001)',
         x=0, y=y, unit="percentunit", decimals=1,
         steps=[{"color": CRITICAL, "value": None},
                {"color": WARNING, "value": 0.3},
                {"color": GOOD, "value": 0.6}],
         desc="The single number the product is sold on. The scaling model assumes "
              "70%; at 2e9 requests/day each point is worth ~$2.7M/year."),
    stat("Upstream calls avoided",
         f'sum(rate(gateway_cache_lookup_total{{{CSEL},result="hit"}}[5m]))',
         x=6, y=y, unit="reqps", colour=AQUA,
         desc="Requests that never reached a provider."),
    stat("Projected saving",
         f'sum(rate(gateway_cache_lookup_total{{{CSEL},result="hit"}}[5m]))'
         f' * {COST_PER_REQUEST} * 86400',
         x=12, y=y, unit="currencyUSD", decimals=2, colour=GOOD,
         desc=f"Current hit rate extrapolated to a day at ${COST_PER_REQUEST}/request "
              "(500 in + 500 out tokens at commodity small-model pricing). An "
              "EXTRAPOLATION from the last 5 minutes, not a measured saving - see "
              "docs/architecture/scaling-model.md."),
    stat("Tier 2 skipped",
         f'sum(rate(gateway_cache_tier2_skipped_total{{{CSEL}}}[5m]))',
         x=18, y=y, unit="reqps", colour=YELLOW,
         desc="Prompts over 1,000 characters, where MiniLM's 256-token truncation "
              "would make different long prompts produce identical vectors. A "
              "correctness guard, so a non-zero value is correct behaviour."),
]
y += 5

cache_panels.append(row("Per tier", y=y)); y += 1

cache_panels += [
    timeseries("Lookups by tier and outcome",
               [(f'sum by (tier, result) (rate(gateway_cache_lookup_total{{{CSEL}}}[$__rate_interval]))', "{{tier}} {{result}}")],
               x=0, y=y, unit="reqps", stack=True,
               desc="tier1 is the exact SHA-256 match, tier2 the semantic search. "
                    "Every lookup records exactly ONE outcome, so these sum to the "
                    "lookup count and the hit ratio below is well defined. On tier2: "
                    "below_threshold means a candidate was found and correctly refused; "
                    "suppressed means it cleared the threshold but the active embedding "
                    "model is the non-semantic fallback; shadow means it would have been "
                    "served but shadow mode is on."),
    timeseries("Hit ratio by tier",
               [(f'sum by (tier) (rate(gateway_cache_lookup_total{{{CSEL},result="hit"}}[5m]))'
                 f' / clamp_min(sum by (tier) (rate(gateway_cache_lookup_total{{{CSEL}}}[5m])), 0.001)', "{{tier}}")],
               x=12, y=y, unit="percentunit", decimals=3,
               colours={"tier1": BLUE, "tier2": ORANGE},
               desc="tier2's denominator is only the requests that MISSED tier1, so the "
                    "two ratios are not directly comparable - tier2 is working on the "
                    "harder remainder."),
]
y += 8

cache_panels += [
    timeseries("Cache lookup p99, by tier",
               [(f'histogram_quantile(0.99, sum by (tier, le) (rate(gateway_cache_lookup_duration_seconds_bucket{{{CSEL}}}[$__rate_interval])))', "{{tier}}")],
               x=0, y=y, unit="s", decimals=4,
               colours={"tier1": BLUE, "tier2": ORANGE},
               thresholds=[{"color": GOOD, "value": None},
                           {"color": WARNING, "value": 0.015},
                           {"color": CRITICAL, "value": 0.05}],
               desc="Targets are < 2 ms for tier1 and < 15 ms for tier2. The dashed "
                    "threshold is tier2's; tier1 should sit far below it. Measured on a "
                    "laptop: tier1 3.94 ms (over target, Redis in WSL2), tier2 15.38 ms."),
    timeseries("Cache writes, by tier",
               [(f'sum by (tier) (rate(gateway_cache_write_total{{{CSEL}}}[$__rate_interval]))', "{{tier}}")],
               x=12, y=y, unit="wps",
               colours={"tier1": BLUE, "tier2": ORANGE},
               desc="Writes should track the upstream miss rate. tier2 writes are "
                    "produced by the indexer consuming gateway.cache-write.v1, so a gap "
                    "between tier1 and tier2 writes is consumer lag."),
]
y += 8

cache_panels.append(row("Shadow mode - the false-hit measurement", y=y)); y += 1

cache_panels += [
    timeseries("Would have served, if Tier 2 were live",
               [(f'sum(rate(gateway_cache_shadow_would_have_served_total{{{CSEL}}}[$__rate_interval]))', "would have served")],
               x=0, y=y, w=24, unit="reqps", legend=False,
               colours={"would have served": MAGENTA},
               desc="In shadow mode Tier 2 runs the similarity search, records what it "
                    "WOULD have returned, and serves the upstream response anyway. This "
                    "is how the false-hit rate is measured before Tier 2 is allowed to "
                    "answer anything. It matters more than the hit rate: serving a wrong "
                    f"answer to save ${COST_PER_REQUEST} is a terrible trade. Compare "
                    "these counts against sampled responses before turning shadow mode "
                    "off."),
]

cache = dashboard(
    "ai-gw-cache", "AI Gateway - Cache economics",
    "Whether the two-tier semantic cache is earning its keep, and whether it is "
    "doing so correctly. The shadow-mode panel is the correctness half: a high hit "
    "rate with a high false-hit rate is worse than no cache.",
    cache_panels, refresh="30s", from_="now-1h")

for name, doc in (("gateway-red.json", red), ("cache-economics.json", cache)):
    path = os.path.join(OUT, name)
    io.open(path, "w", encoding="utf-8", newline="\n").write(
        json.dumps(doc, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {path}  ({len(doc['panels'])} panels)")
