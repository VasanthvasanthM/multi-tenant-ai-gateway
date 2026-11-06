"""Check the generated Grafana dashboards against reality.

The valuable check is the third one: every metric a panel queries must be a
meter the Java actually registers. A dashboard referencing a metric that does
not exist renders as an empty panel with no error, which is indistinguishable
from "no traffic yet" - so it can sit broken indefinitely.
"""

import io
import json
import pathlib
import re
import sys

REPO = pathlib.Path(".").resolve()
DASH = REPO / "deploy" / "grafana" / "dashboards"

failures = []


def fail(m):
    failures.append(m)
    print(f"  FAIL  {m}")


def ok(m):
    print(f"  ok    {m}")


# ---------------------------------------------------------------------------
# What the Java registers, translated to Prometheus names.
# ---------------------------------------------------------------------------
meters = {}
for java in REPO.rglob("*.java"):
    if "target" in java.parts:
        continue
    text = java.read_text(encoding="utf-8", errors="replace")
    for m in re.finditer(
            r'(Counter|Timer|Gauge|DistributionSummary)\.builder\(\s*"([^"]+)"', text):
        meters[m.group(2)] = m.group(1)

available = set()
for name, kind in meters.items():
    base = name.replace(".", "_").replace("-", "_")
    if kind == "Counter":
        available.add(f"{base}_total")
        available.add(base)
    elif kind == "Timer":
        for suffix in ("_seconds_count", "_seconds_sum", "_seconds_max", "_seconds_bucket"):
            available.add(base + suffix)
    else:
        available.add(base)

# Metrics from components outside this codebase, which the dashboards may use.
EXTERNAL = {
    # kube-state-metrics, enabled in kube-prometheus-stack-values.yaml
    "kube_horizontalpodautoscaler_status_current_replicas",
    "kube_horizontalpodautoscaler_status_desired_replicas",
    "kube_horizontalpodautoscaler_spec_max_replicas",
    # Micrometer / Spring Boot built-ins
    "http_server_requests_seconds_count",
    "http_server_requests_seconds_bucket",
    "jvm_memory_used_bytes",
    "process_cpu_usage",
}
available |= EXTERNAL

print(f"  {len(meters)} meters registered in Java -> {len(available)} Prometheus series names\n")

PROMQL_FUNCS = {
    "rate", "irate", "increase", "sum", "max", "min", "avg", "count",
    "histogram_quantile", "clamp_min", "clamp_max", "label_values", "by", "without",
    "topk", "bottomk", "quantile", "stddev", "delta", "idelta", "absent", "round",
}

for path in sorted(DASH.glob("*.json")):
    print(f"--- {path.name}")
    try:
        doc = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:                            # noqa: BLE001
        fail(f"{path.name}: invalid JSON: {exc}")
        continue
    ok("valid JSON")

    panels = doc["panels"]

    # Panel ids unique
    ids = [p["id"] for p in panels]
    if len(ids) != len(set(ids)):
        fail(f"{path.name}: duplicate panel ids")
    else:
        ok(f"{len(panels)} panels, ids unique")

    # Grid: no two non-row panels overlap
    boxes = []
    for p in panels:
        if p["type"] == "row":
            continue
        g = p["gridPos"]
        boxes.append((p["title"], g["x"], g["y"], g["w"], g["h"]))
    overlaps = []
    for i in range(len(boxes)):
        t1, x1, y1, w1, h1 = boxes[i]
        if x1 + w1 > 24:
            fail(f"{path.name}: '{t1}' extends past the 24-column grid")
        for j in range(i + 1, len(boxes)):
            t2, x2, y2, w2, h2 = boxes[j]
            if x1 < x2 + w2 and x2 < x1 + w1 and y1 < y2 + h2 and y2 < y1 + h1:
                overlaps.append((t1, t2))
    if overlaps:
        for a, b in overlaps:
            fail(f"{path.name}: panels overlap: '{a}' / '{b}'")
    else:
        ok("no overlapping panels, all within 24 columns")

    # Every metric referenced must exist
    exprs = []
    for p in panels:
        for t in p.get("targets", []):
            if t.get("expr"):
                exprs.append((p["title"], t["expr"]))
    for v in doc["templating"]["list"]:
        if v.get("query") and isinstance(v["query"], str) and "(" in v["query"]:
            exprs.append((f"variable ${v['name']}", v["query"]))

    unknown = {}
    for title, expr in exprs:
        for ident in re.findall(r"\b([a-z_][a-z0-9_]{3,})\b", expr):
            if ident in PROMQL_FUNCS or ident in available:
                continue
            # Label names and label values appear as bare identifiers too; only
            # treat an identifier as a metric if it looks like one.
            if not re.search(rf"(^|[\s(,]){re.escape(ident)}\s*(\{{|\[)", expr):
                continue
            unknown.setdefault(ident, set()).add(title)

    if unknown:
        for ident, where in sorted(unknown.items()):
            fail(f"{path.name}: metric '{ident}' is not registered anywhere "
                 f"(panels: {', '.join(sorted(where))})")
    else:
        ok(f"all metrics in {len(exprs)} expressions exist")

    # No dual-axis: a timeseries panel must not mix units across overrides
    for p in panels:
        if p["type"] != "timeseries":
            continue
        units = {o_prop["value"]
                 for o in p.get("fieldConfig", {}).get("overrides", [])
                 for o_prop in o.get("properties", [])
                 if o_prop.get("id") == "unit"}
        if len(units) > 1:
            fail(f"{path.name}: '{p['title']}' mixes units {units} - that is a dual axis")
    ok("no dual-axis panels")
    print()

if failures:
    print(f"{len(failures)} problem(s).")
    sys.exit(1)
print("Dashboards are structurally valid and every metric they query exists.")
