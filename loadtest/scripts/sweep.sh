#!/usr/bin/env bash
#
# The pod sweep: measure throughput at 1..N gateway pods, then fit the USL.
#
#   ./loadtest/scripts/sweep.sh
#   MAX_PODS=4 RPS=400 ./loadtest/scripts/sweep.sh
#
# Produces loadtest/results/sweep-<timestamp>.csv and a markdown report beside
# it.
#
# ---------------------------------------------------------------------------
# WHAT THIS SCRIPT IS CAREFUL ABOUT, AND WHY
# ---------------------------------------------------------------------------
#
#   1. CPU ISOLATION. k6 is pinned to its own cores and never shares with the
#      system under test. Without that the load generator competes with the
#      thing it is measuring, and the throughput curve bends because the
#      GENERATOR ran out of CPU - which looks identical to the gateway
#      saturating and is the easiest way to produce a confident wrong answer on
#      a laptop.
#
#   2. PRE-WARMING, once, before the sweep. Otherwise step 1 runs against a
#      cold cache and step 6 against a warm one, the hit ratio climbs across
#      the sweep, and the fit describes a cache filling up.
#
#   3. SETTLING after each scale change. A new pod needs ~16 s for the JVM plus
#      the model load, and the HPA has a 30 s stabilisation window. Measuring
#      before the new pods are Ready attributes their absence to the previous
#      step.
#
#   4. REJECTING invalid steps. A step that dropped iterations could not sustain
#      its offered rate; a step that was rate limited measured the limiter.
#      Either way it is excluded from the CSV rather than fitted, because the
#      fit cannot tell the difference and the report would be wrong rather than
#      noisy.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RESULTS_DIR="$REPO_ROOT/loadtest/results"

MAX_PODS="${MAX_PODS:-6}"
RPS="${RPS:-300}"
DURATION="${DURATION:-2m}"
WARMUP="${WARMUP:-45s}"
SETTLE_SECONDS="${SETTLE_SECONDS:-60}"

RELEASE="${RELEASE:-aigw}"
NAMESPACE="${NAMESPACE:-default}"
DEPLOYMENT="${DEPLOYMENT:-${RELEASE}-ai-gateway-gateway}"

GATEWAY="${GATEWAY:-http://localhost:8080}"
HOST_HEADER="${HOST_HEADER:-ai-gateway.local}"
API_KEY="${API_KEY:-sk-dev-local}"

# Cores for k6, and cores for everything else. Defaults suit a 8-thread laptop:
# k6 on the last two, the cluster on the rest.
K6_CPUS="${K6_CPUS:-6-7}"

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m  !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
info "Preflight"

command -v kubectl >/dev/null 2>&1 || die "kubectl is not installed"
command -v k6 >/dev/null 2>&1 || command -v docker >/dev/null 2>&1 \
  || die "need either k6 or docker (to run grafana/k6)"

kubectl cluster-info >/dev/null 2>&1 \
  || die "no reachable cluster. Run ./scripts/cluster-up.sh first."

kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" >/dev/null 2>&1 \
  || die "deployment $DEPLOYMENT not found in $NAMESPACE.
  Deploy it first:
    helm upgrade --install $RELEASE deploy/helm/ai-gateway \\
      -f deploy/helm/ai-gateway/values-scale.yaml"
ok "deployment $DEPLOYMENT found"

# An HPA would fight every manual scale in this script: it would scale back
# toward its own target between steps, and the pod count during a step would
# not be the pod count the step is labelled with.
if kubectl -n "$NAMESPACE" get hpa "$DEPLOYMENT" >/dev/null 2>&1; then
  die "an HPA exists for $DEPLOYMENT.

  A sweep sets the replica count explicitly, and an HPA would move it back
  between steps - so each step would be measured at a pod count that is not the
  one recorded. Delete it for the duration of the sweep:

    kubectl -n $NAMESPACE delete hpa $DEPLOYMENT

  The HPA's own behaviour is a separate measurement, and it needs the custom
  metric to be serving - check 'kubectl get hpa' shows a number, not <unknown>."
fi
ok "no HPA to interfere"

if ! curl -fsS -m 5 -H "Host: $HOST_HEADER" "$GATEWAY/actuator/health/readiness" >/dev/null 2>&1; then
  die "gateway not reachable at $GATEWAY (Host: $HOST_HEADER)"
fi
ok "gateway reachable"

mkdir -p "$RESULTS_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
CSV="$RESULTS_DIR/sweep-$STAMP.csv"
REPORT="$RESULTS_DIR/sweep-$STAMP.md"
LOG_DIR="$RESULTS_DIR/sweep-$STAMP-logs"
mkdir -p "$LOG_DIR"

echo "pods,rps,p50_ms,p95_ms,p99_ms,cpu_pct,hit_ratio" > "$CSV"

# ---------------------------------------------------------------------------
# k6 invocation: native if present, else the official image.
# ---------------------------------------------------------------------------
run_k6() {
  local rps="$1" out="$2"

  if command -v k6 >/dev/null 2>&1; then
    # taskset where available (Linux); on Windows/macOS it is absent and the
    # pinning is skipped with a warning rather than silently not happening.
    if command -v taskset >/dev/null 2>&1; then
      taskset -c "$K6_CPUS" k6 run \
        -e "GATEWAY=$GATEWAY" -e "HOST_HEADER=$HOST_HEADER" -e "API_KEY=$API_KEY" \
        -e "RPS=$rps" -e "DURATION=$DURATION" -e "WARMUP=$WARMUP" \
        "$REPO_ROOT/loadtest/k6/completions.js" 2>&1 | tee "$out"
    else
      warn "taskset unavailable: k6 is NOT pinned, so it shares cores with the"
      warn "cluster. The curve may bend because the generator ran out of CPU."
      k6 run \
        -e "GATEWAY=$GATEWAY" -e "HOST_HEADER=$HOST_HEADER" -e "API_KEY=$API_KEY" \
        -e "RPS=$rps" -e "DURATION=$DURATION" -e "WARMUP=$WARMUP" \
        "$REPO_ROOT/loadtest/k6/completions.js" 2>&1 | tee "$out"
    fi
  else
    # --cpuset-cpus is the container equivalent of taskset and works on Docker
    # Desktop, where taskset does not exist.
    docker run --rm -i \
      --cpuset-cpus "$K6_CPUS" \
      --network host \
      -v "$REPO_ROOT/loadtest/k6:/scripts:ro" \
      -e "GATEWAY=$GATEWAY" -e "HOST_HEADER=$HOST_HEADER" -e "API_KEY=$API_KEY" \
      -e "RPS=$rps" -e "DURATION=$DURATION" -e "WARMUP=$WARMUP" \
      grafana/k6:latest run /scripts/completions.js 2>&1 | tee "$out"
  fi
}

# ---------------------------------------------------------------------------
# Pre-warm once, before any measurement.
# ---------------------------------------------------------------------------
info "Pre-warming the cache at 1 pod"
kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas=1 >/dev/null
kubectl -n "$NAMESPACE" rollout status deployment "$DEPLOYMENT" --timeout=300s >/dev/null
run_k6 "$(( RPS / 4 ))" "$LOG_DIR/prewarm.log" >/dev/null 2>&1 || true
ok "cache pre-warmed"

# ---------------------------------------------------------------------------
# The sweep
# ---------------------------------------------------------------------------
for pods in $(seq 1 "$MAX_PODS"); do
  info "Step: $pods pod(s)"

  kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" --replicas="$pods" >/dev/null
  kubectl -n "$NAMESPACE" rollout status deployment "$DEPLOYMENT" --timeout=300s >/dev/null

  ready=$(kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" \
            -o jsonpath='{.status.readyReplicas}')
  [ "${ready:-0}" = "$pods" ] || die "expected $pods ready replicas, got ${ready:-0}"

  ok "$pods replica(s) Ready; settling ${SETTLE_SECONDS}s"
  # Settle AFTER Ready, not instead of it. Ready means the probe passed; it does
  # not mean the JIT has warmed or the connection pools have filled.
  sleep "$SETTLE_SECONDS"

  log="$LOG_DIR/step-$pods.log"
  run_k6 "$RPS" "$log" >/dev/null

  result=$(grep -m1 '^SWEEP_RESULT' "$log" || true)
  [ -n "$result" ] || { warn "step $pods produced no result line; skipping"; continue; }

  # shellcheck disable=SC2086
  eval "$(echo "$result" | sed 's/^SWEEP_RESULT //' | tr ' ' '\n' | sed 's/^/step_/')"

  dropped="${step_dropped:-0}"
  limited="${step_rate_limited:-0}"

  if [ "${dropped%.*}" -gt 0 ] 2>/dev/null; then
    warn "step $pods DROPPED $dropped iterations: it could not sustain ${RPS} rps."
    warn "Excluded - an unsustained step measures the generator's queue, not the"
    warn "gateway, and the fit cannot tell the difference."
    continue
  fi
  if [ "${limited%.*}" -gt 0 ] 2>/dev/null; then
    warn "step $pods saw $limited rate-limited responses: the limiter shaped the"
    warn "load, so this step measures the limiter. Excluded. Raise the tenant quota."
    continue
  fi

  # Mean CPU across the gateway pods, if metrics-server is available. Optional:
  # without it the fit still works, but the report cannot distinguish "out of
  # capacity" from "blocked on I/O".
  cpu=""
  if kubectl top pods -n "$NAMESPACE" >/dev/null 2>&1; then
    cpu=$(kubectl top pods -n "$NAMESPACE" --no-headers 2>/dev/null \
            | awk -v d="$DEPLOYMENT" '$1 ~ d {gsub(/m/,"",$2); sum+=$2; n++}
                                      END {if (n) printf "%.1f", sum/n/10}')
  fi

  printf '%s,%s,%s,%s,%s,%s,%s\n' \
    "$pods" "${step_rps:-0}" "${step_p50:-}" "${step_p95:-}" "${step_p99:-}" \
    "$cpu" "${step_hit_ratio:-}" >> "$CSV"

  ok "pods=$pods rps=${step_rps:-?} p99=${step_p99:-?}ms hit=${step_hit_ratio:-?}"
done

# ---------------------------------------------------------------------------
# Fit
# ---------------------------------------------------------------------------
rows=$(( $(wc -l < "$CSV") - 1 ))
info "Sweep complete: $rows valid step(s) in $CSV"

if [ "$rows" -lt 3 ]; then
  die "only $rows valid step(s). The USL has three parameters and needs at least
  three points - two fit perfectly and tell you nothing. Check the excluded
  steps in $LOG_DIR."
fi

info "Fitting"
python "$REPO_ROOT/loadtest/analysis/fit.py" "$CSV" --output "$REPORT" || true

cat <<SUMMARYEOF

  results  $CSV
  report   $REPORT
  logs     $LOG_DIR

  Read the "Reasons to distrust this fit" section of the report FIRST. A fit is
  easy to produce and easy to quote; that section lists the conditions under
  which it should not be.

SUMMARYEOF
