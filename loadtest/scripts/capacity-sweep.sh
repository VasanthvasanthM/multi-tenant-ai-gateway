#!/usr/bin/env bash
#
# Capacity sweep: measure SATURATION throughput at 1..N gateway pods, then fit
# the USL.
#
#   ./loadtest/scripts/capacity-sweep.sh
#   MAX_PODS=3 VUS=50 DURATION=60s ./loadtest/scripts/capacity-sweep.sh
#
# ---------------------------------------------------------------------------
# HOW THIS DIFFERS FROM sweep.sh, AND WHY BOTH EXIST
# ---------------------------------------------------------------------------
#
# sweep.sh offers a fixed arrival rate and asks whether N pods sustained it.
# That is an SLO question and it is the right one for release gating.
#
# It cannot answer the USL question. The USL fits X(N), the maximum throughput
# at N pods, and a fixed offered rate measures the OFFER, not the capacity: if
# one pod already sustains 300 rps then every step reports ~300 rps, the fit
# sees a flat line through equal values, and it returns sigma = kappa = 0 -
# perfect linear scalability, concluded from a sweep that never loaded anything.
#
# So this script drives loadtest/k6/capacity.js, which is closed-loop: a fixed
# VU count, each VU with one request in flight. Throughput becomes an output.
#
# ---------------------------------------------------------------------------
# WHAT MAKES A STEP COMPARABLE TO THE ONE BEFORE IT
# ---------------------------------------------------------------------------
#
#   1. VUS IS CONSTANT across steps. It is the offered concurrency; changing it
#      between steps would mix "more pods" with "more load" in one curve.
#
#   2. EVERY POD MUST HAVE A CPU LIMIT. Without one, a single pod expands to the
#      whole node and adding pods adds no capacity - the curve flattens and the
#      fit reports the NODE's core count as the architecture's scaling ceiling.
#      This script refuses to run if the limit is unset, because that failure is
#      invisible in the output: it produces a confident, wrong N_max.
#
#   3. THE CACHE IS PRE-WARMED and the hit ratio is recorded per step. The tier
#      that answers decides the latency, so a sweep whose hit ratio drifted is
#      measuring the cache filling, not scalability.
#
#   4. INGRESS CPU IS RECORDED. Every pod is reached through one shared
#      ingress-nginx; if that saturates, the serialization it contributes is
#      attributed by the fit to sigma and read as a property of the gateway.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS_DIR="$REPO_ROOT/loadtest/results"
LOG_DIR="$RESULTS_DIR/logs"

MAX_PODS="${MAX_PODS:-3}"
VUS="${VUS:-50}"
DURATION="${DURATION:-60s}"
WARMUP="${WARMUP:-30s}"
SETTLE_SECONDS="${SETTLE_SECONDS:-30}"
PREWARM="${PREWARM:-60s}"

RELEASE="${RELEASE:-aigw}"
NAMESPACE="${NAMESPACE:-default}"
DEPLOYMENT="${DEPLOYMENT:-${RELEASE}-ai-gateway-gateway}"

GATEWAY="${GATEWAY:-http://localhost:8080}"
HOST_HEADER="${HOST_HEADER:-ai-gateway.local}"
API_KEY="${API_KEY:-sk-local-dev-acme-00000000000000000000}"

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m  !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

py() {
  # python3 on Windows is an App Execution Alias that passes `command -v` and
  # then refuses to run, so each candidate is probed by executing it.
  local cand
  for cand in python3 python py; do
    if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import sys' >/dev/null 2>&1; then
      "$cand" "$@"
      return $?
    fi
  done
  die "no working Python 3 found; the USL fit needs one"
}

mkdir -p "$RESULTS_DIR" "$LOG_DIR"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
info "Preflight"

command -v kubectl >/dev/null 2>&1 || die "kubectl not found"
command -v k6 >/dev/null 2>&1 || die "k6 not found on PATH"
kubectl get deploy "$DEPLOYMENT" -n "$NAMESPACE" >/dev/null 2>&1 \
  || die "deployment '$DEPLOYMENT' not found in namespace '$NAMESPACE'"

CPU_LIMIT="$(kubectl get deploy "$DEPLOYMENT" -n "$NAMESPACE" \
  -o jsonpath='{.spec.template.spec.containers[0].resources.limits.cpu}')"
if [ -z "$CPU_LIMIT" ]; then
  die "the gateway container has no CPU limit.

  One pod would expand to the whole node, so adding pods would add no capacity
  and the fit would report the node's core count as this architecture's ceiling.
  Set one, for example:

    helm upgrade $RELEASE deploy/helm/ai-gateway --reuse-values \\
      --set services.gateway.resources.limits.cpu=400m"
fi
ok "gateway CPU limit is $CPU_LIMIT per pod"

# An HPA left enabled will fight every `kubectl scale` in this loop, and the
# step would then run at a pod count nobody chose.
if kubectl get hpa -n "$NAMESPACE" 2>/dev/null | grep -q "$DEPLOYMENT"; then
  die "an HPA targets $DEPLOYMENT; it will override each scale step. Disable it:
    helm upgrade $RELEASE deploy/helm/ai-gateway --reuse-values \\
      --set services.gateway.autoscaling.enabled=false"
fi
ok "no HPA competing for the replica count"

health="$(curl -s -o /dev/null -w '%{http_code}' -m 15 \
  -H "Host: $HOST_HEADER" "$GATEWAY/actuator/health" || true)"
[ "$health" = "200" ] || die "gateway health returned '$health' at $GATEWAY (Host: $HOST_HEADER)"
ok "gateway reachable at $GATEWAY"

NODE_CPUS="$(kubectl get nodes -o jsonpath='{.items[0].status.capacity.cpu}')"
TOTAL_LIMIT_CORES="$(py -c "
import sys
limit = '$CPU_LIMIT'
cores = float(limit[:-1]) / 1000 if limit.endswith('m') else float(limit)
print('%.2f' % (cores * $MAX_PODS))
")"
info "Top step needs ${TOTAL_LIMIT_CORES} cores of gateway CPU; a node reports ${NODE_CPUS}"
warn "If the nodes are containers on one VM, their reported capacity is that"
warn "VM's counted once per node - so the real ceiling is lower than it looks."

# ---------------------------------------------------------------------------
# Pre-warm once, before the sweep
# ---------------------------------------------------------------------------
# Otherwise step 1 runs against a cold cache and the last step against a warm
# one: the hit ratio climbs across the sweep and the fit describes a cache
# filling up as though it were scalability.
STAMP="$(date +%Y%m%d-%H%M%S)"

info "Pre-warming the cache"
# Driven through k6 and capacity.js, NOT a curl loop. An earlier version warmed
# with the string "warm $i", which appears nowhere in lib/corpus.js - so it
# filled Tier 1 with entries the measurement would never ask for and left the
# real corpus cold. A warm-up has to issue the prompts the measurement issues,
# and the only place those live is the corpus module.
#
# The measure phase is clamped to 1s because only the warmup scenario matters
# here. This run's own result is discarded, so a threshold breach is not a
# failure and its exit status is ignored deliberately.
prewarm_log="$LOG_DIR/capacity-prewarm.log"
set +e
k6 run --quiet \
  -e "GATEWAY=$GATEWAY" -e "HOST_HEADER=$HOST_HEADER" -e "API_KEY=$API_KEY" \
  -e "VUS=8" -e "WARMUP=$PREWARM" -e "DURATION=1s" \
  -e "RUN_ID=prewarm-$STAMP" \
  "$REPO_ROOT/loadtest/k6/capacity.js" > "$prewarm_log" 2>&1
set -e
ok "cache pre-warmed for $PREWARM against the real corpus"

# ---------------------------------------------------------------------------
# Sweep
# ---------------------------------------------------------------------------
CSV="$RESULTS_DIR/capacity-$STAMP.csv"
printf 'pods,rps,p50_ms,p95_ms,p99_ms,cpu_pct,hit_ratio\n' > "$CSV"

for pods in $(seq 1 "$MAX_PODS"); do
  info "Step $pods/$MAX_PODS: scaling to $pods pod(s)"
  kubectl scale deploy "$DEPLOYMENT" -n "$NAMESPACE" --replicas="$pods" >/dev/null

  if ! kubectl rollout status deploy "$DEPLOYMENT" -n "$NAMESPACE" --timeout=300s >/dev/null 2>&1; then
    warn "step $pods never became Ready; skipping it rather than measuring a"
    warn "pod count that was not actually serving."
    kubectl get pods -n "$NAMESPACE" -l "app.kubernetes.io/name=gateway" 2>/dev/null | head -8 >&2 || true
    continue
  fi

  ready="$(kubectl get deploy "$DEPLOYMENT" -n "$NAMESPACE" -o jsonpath='{.status.readyReplicas}')"
  [ "${ready:-0}" = "$pods" ] || { warn "step $pods: ${ready:-0} ready, expected $pods; skipping"; continue; }

  # Settle AFTER Ready. Ready means a probe passed; it does not mean the JIT has
  # warmed or that the connection pools have filled.
  ok "$pods replica(s) Ready; settling ${SETTLE_SECONDS}s"
  sleep "$SETTLE_SECONDS"

  log="$LOG_DIR/capacity-step-$pods.log"
  set +e
  k6 run --quiet \
    -e "GATEWAY=$GATEWAY" -e "HOST_HEADER=$HOST_HEADER" -e "API_KEY=$API_KEY" \
    -e "VUS=$VUS" -e "DURATION=$DURATION" -e "WARMUP=$WARMUP" \
    -e "RUN_ID=$STAMP-$pods" \
    "$REPO_ROOT/loadtest/k6/capacity.js" > "$log" 2>&1
  k6_status=$?
  set -e

  result="$(grep -m1 '^CAPACITY_RESULT' "$log" || true)"
  if [ -z "$result" ]; then
    warn "step $pods produced no result line (k6 exit $k6_status); skipping"
    tail -5 "$log" >&2 || true
    continue
  fi

  # shellcheck disable=SC2046
  eval "$(echo "$result" | sed 's/^CAPACITY_RESULT //' | tr ' ' '\n' | sed 's/^/step_/')"

  if [ "${step_rate_limited:-0}" != "0" ]; then
    warn "step $pods saw ${step_rate_limited} rate-limited responses, so it"
    warn "measured the limiter. Excluded. Raise gateway.rate-limit."
    continue
  fi
  if [ "${step_errors:-0}" != "0" ]; then
    warn "step $pods saw ${step_errors} server errors: part of the load was"
    warn "answered by an error path far cheaper than serving it. Excluded."
    continue
  fi

  # Mean CPU across the gateway pods, as a percentage of ONE pod's limit. This
  # is what distinguishes "out of CPU" from "blocked on something else", and the
  # two have opposite implications for what the fitted sigma means.
  cpu=""
  if kubectl top pods -n "$NAMESPACE" >/dev/null 2>&1; then
    cpu="$(kubectl top pods -n "$NAMESPACE" --no-headers 2>/dev/null \
      | awk -v d="$DEPLOYMENT" -v lim="$CPU_LIMIT" '
          $1 ~ d { gsub(/m/, "", $2); sum += $2; n++ }
          END {
            if (n == 0) exit
            l = lim; sub(/m$/, "", l)
            if (lim !~ /m$/) l = l * 1000
            if (l > 0) printf "%.1f", (sum / n) / l * 100
          }')"
  fi

  ingress_cpu="$(kubectl top pods -n ingress-nginx --no-headers 2>/dev/null \
    | awk '$1 ~ /controller/ { gsub(/m/, "", $2); print $2; exit }' || true)"

  printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$pods" "${step_throughput:-0}" "${step_p50:-}" "${step_p95:-}" \
    "${step_p99:-}" "$cpu" "${step_hit_ratio:-}" >> "$CSV"

  ok "step $pods: ${step_throughput:-?} rps, p50 ${step_p50:-?} ms, p95 ${step_p95:-?} ms, hit ${step_hit_ratio:-?}, pod CPU ${cpu:-n/a}%, ingress ${ingress_cpu:-n/a}m"

  unset step_throughput step_p50 step_p95 step_p99 step_hit_ratio \
        step_rate_limited step_errors step_completed step_vus step_seconds \
        step_tier1 step_tier2 step_upstream
done

# ---------------------------------------------------------------------------
# Fit
# ---------------------------------------------------------------------------
rows="$(($(wc -l < "$CSV") - 1))"
info "Sweep complete: $rows usable step(s) in $CSV"

# Was the TOP step actually saturated? If the pods still had CPU headroom then
# VUS, not the gateway, set the throughput - the curve is a measurement of the
# offered concurrency and flattens wherever the generator ran out of requests to
# keep in flight. The fit cannot detect this: it sees a believable concave curve
# and attributes the flattening to sigma.
top_cpu="$(tail -1 "$CSV" | cut -d, -f6)"
if [ -n "$top_cpu" ] && py -c "import sys; sys.exit(0 if float('$top_cpu') < 85 else 1)" 2>/dev/null; then
  warn "the top step ran at ${top_cpu}% of one pod's CPU limit, so it was NOT"
  warn "saturated: VUS=$VUS could not keep every pod busy. Raise VUS and repeat,"
  warn "or read the fitted sigma as a property of the load, not the gateway."
fi

if [ "$rows" -lt 3 ]; then
  warn "The USL has three parameters, so a fit needs at least three points."
  warn "With $rows the fitter will refuse, and it is right to: two points pass"
  warn "exactly through any curve of this family, which pins lambda but leaves"
  warn "sigma and kappa trading off against each other - and N_max depends on"
  warn "exactly that trade-off. Raise MAX_PODS."
  cat "$CSV"
  exit 1
fi

REPORT="${CSV%.csv}.md"
py "$REPO_ROOT/loadtest/analysis/fit.py" "$CSV" | tee "$REPORT"
ok "report written to $REPORT"
