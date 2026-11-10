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
#   1. EACH STEP IS MEASURED AT ITS OWN PEAK. X(N) is the most N pods can
#      retire, so each step walks a concurrency ladder and keeps its best valid
#      point. A single fixed VUS cannot do this: high enough to saturate the top
#      step, it drives the bottom step so far past its knee that the request
#      queue alone exceeds the Redis command timeout - Tier 1 then times out,
#      every timeout becomes an upstream call, the extra work deepens the queue,
#      and throughput COLLAPSES instead of plateauing. One pod measured 210 rps
#      at vus=32 and 55 rps at vus=90, which is the same pod twice.
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

# X(N) is the MOST that N pods can retire, and that is a peak over offered
# concurrency - not the throughput at one arbitrary VU count. One fixed VUS
# cannot measure it at every step: a value high enough to saturate the top step
# drives the bottom step so far past its knee that the queue alone exceeds the
# Redis command timeout, at which point Tier 1 starts timing out, every timeout
# becomes an upstream call, and throughput COLLAPSES rather than plateauing.
# Measured here: one pod reported 210 rps at VUS=32 and 55 rps at VUS=90.
#
# So each step walks a ladder and keeps its best valid point, which is the
# textbook definition of capacity and also immune to the collapse: a collapsed
# point is simply never the maximum.
VUS_LADDER="${VUS_LADDER:-}"

# A step whose hit ratio fell well below what the traffic mix defines was not
# serving the same workload - Tier 1 was failing and the request went upstream.
# EXACT_PERCENT + PARAPHRASE_PERCENT is the designed ceiling (0.70 by default,
# since Tier 2 is off in dev and a paraphrase lands in Tier 1 after its first
# miss). Measuring 0.56 against that means a quarter of the cache was gone.
HIT_RATIO_FLOOR="${HIT_RATIO_FLOOR:-0.62}"
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

# Sums usage_usec, nr_periods and nr_throttled across every gateway pod. Summed
# rather than averaged so a pod that appeared mid-window cannot dilute the
# others; cgroup_delta.py divides by the pod count.
cgroup_totals() {
  local pod total_usage=0 total_periods=0 total_throttled=0 stat
  for pod in $(kubectl get pods -n "$NAMESPACE" -o name 2>/dev/null | grep "$DEPLOYMENT" || true); do
    # The path goes through `sh -c` with MSYS_NO_PATHCONV set, because Git Bash
    # on Windows rewrites anything that looks like an absolute POSIX path before
    # kubectl sees it: /sys/fs/cgroup/cpu.stat was arriving in the container as
    # "C:/Program Files/Git/sys/fs/cgroup/cpu.stat". The read failed silently
    # and the CPU column simply read "n/a" for a whole sweep.
    stat="$(MSYS_NO_PATHCONV=1 kubectl exec -n "$NAMESPACE" "${pod#pod/}" -- \
      sh -c 'cat /sys/fs/cgroup/cpu.stat' 2>/dev/null || true)"
    [ -n "$stat" ] || continue
    total_usage=$((total_usage + $(echo "$stat" | awk '/^usage_usec/ {print $2}')))
    total_periods=$((total_periods + $(echo "$stat" | awk '/^nr_periods/ {print $2}')))
    total_throttled=$((total_throttled + $(echo "$stat" | awk '/^nr_throttled/ {print $2}')))
  done
  [ "$total_periods" -gt 0 ] || return 0
  printf '%s %s %s' "$total_usage" "$total_periods" "$total_throttled"
}

# "90s", "2m", "500ms" -> whole seconds.
duration_seconds() {
  printf '%s' "$1" | awk '
    /ms$/ { sub(/ms$/, ""); printf "%d", $0 / 1000; exit }
    /s$/  { sub(/s$/,  ""); printf "%d", $0; exit }
    /m$/  { sub(/m$/,  ""); printf "%d", $0 * 60; exit }
    /h$/  { sub(/h$/,  ""); printf "%d", $0 * 3600; exit }
            { printf "%d", $0 }'
}

# Runs ONE measurement at a given pod count and VU count.
#
# On success prints "throughput p50 p95 p99 cpu throttled hit_ratio vus" to
# stdout; on an invalid point prints the reason to stderr and returns 1. The
# caller treats an invalid point as absent rather than as a zero - a zero would
# be fitted, and it would drag the curve down as though the system had failed to
# scale, when in fact the step was never validly measured.
run_point() {
  local pods="$1" vus="$2"
  local log="$LOG_DIR/capacity-step-$pods-vus$vus.log"
  local snap="$LOG_DIR/cgroup-before-$pods-vus$vus.txt"
  local snap_pid k6_status result window cpu_line cpu throttled after_cpu before_cpu
  local step_throughput step_p50 step_p95 step_p99 step_hit_ratio
  local step_rate_limited step_errors

  # The cgroup snapshot is taken when the MEASURE phase begins, not when k6
  # starts: the warmup scenario runs at a quarter of the VUs, so a snapshot
  # spanning both would report a CPU figure no phase actually ran at. k6 starts
  # the measure scenario at startTime = WARMUP, so the same sleep lands on it.
  : > "$snap"
  ( sleep "$(duration_seconds "$WARMUP")"; cgroup_totals > "$snap" 2>/dev/null ) &
  snap_pid=$!

  set +e
  k6 run --quiet \
    -e "GATEWAY=$GATEWAY" -e "HOST_HEADER=$HOST_HEADER" -e "API_KEY=$API_KEY" \
    -e "VUS=$vus" -e "DURATION=$DURATION" -e "WARMUP=$WARMUP" \
    -e "RUN_ID=$STAMP-$pods-$vus" \
    "$REPO_ROOT/loadtest/k6/capacity.js" > "$log" 2>&1
  k6_status=$?
  set -e
  wait "$snap_pid" 2>/dev/null || true
  before_cpu="$(cat "$snap" 2>/dev/null || true)"

  result="$(grep -m1 '^CAPACITY_RESULT' "$log" || true)"
  if [ -z "$result" ]; then
    warn "  vus=$vus produced no result line (k6 exit $k6_status)"
    return 1
  fi

  eval "$(echo "$result" | sed 's/^CAPACITY_RESULT //' | tr ' ' '\n' | sed 's/^/step_/')"

  if [ "${step_rate_limited:-0}" != "0" ]; then
    warn "  vus=$vus: ${step_rate_limited} rate-limited responses - measured the limiter"
    return 1
  fi
  if [ "${step_errors:-0}" != "0" ]; then
    warn "  vus=$vus: ${step_errors} server errors - part of the load took an error path"
    return 1
  fi
  # The detector for Tier 1 timing out under its own queue. Once the request
  # queue is deeper than spring.data.redis.timeout, cache lookups start timing
  # out, each timeout becomes an upstream call, and the extra work deepens the
  # queue further. Throughput collapses instead of plateauing, and the only
  # visible symptom is the hit ratio falling away from what the mix defines.
  if ! awk -v h="${step_hit_ratio:-0}" -v f="$HIT_RATIO_FLOOR" 'BEGIN { exit !(h >= f) }'; then
    warn "  vus=$vus: hit ratio ${step_hit_ratio} below floor $HIT_RATIO_FLOOR - Tier 1 was failing, not serving"
    return 1
  fi

  cpu=""
  throttled=""
  after_cpu="$(cgroup_totals)"
  window="$(duration_seconds "$DURATION")"
  if [ -n "$before_cpu" ] && [ -n "$after_cpu" ] && [ "$window" -gt 0 ] 2>/dev/null; then
    cpu_line="$(CPU_BEFORE="$before_cpu" CPU_AFTER="$after_cpu" \
      CPU_LIMIT="$CPU_LIMIT" CPU_PODS="$pods" CPU_WINDOW="$window" \
      py "$REPO_ROOT/loadtest/analysis/cgroup_delta.py" || true)"
    cpu="${cpu_line%% *}"
    throttled="${cpu_line##* }"
  fi

  printf '%s %s %s %s %s %s %s %s\n' \
    "${step_throughput:-0}" "${step_p50:-}" "${step_p95:-}" "${step_p99:-}" \
    "${cpu:-}" "${throttled:-}" "${step_hit_ratio:-}" "$vus"
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

  # Walk the concurrency ladder and keep the best valid point. Defaults scale
  # with the pod count so the bottom step is not driven far past its knee just
  # to saturate the top one.
  ladder="$VUS_LADDER"
  if [ -z "$ladder" ]; then
    ladder="$((16 * pods)) $((32 * pods)) $((64 * pods))"
  fi

  best=""
  best_throughput=0
  for vus in $ladder; do
    if point="$(run_point "$pods" "$vus")"; then
      t="$(echo "$point" | cut -d" " -f1)"
      info "  vus=$vus -> $t rps"
      if awk -v a="$t" -v b="$best_throughput" 'BEGIN { exit !(a > b) }'; then
        best="$point"
        best_throughput="$t"
      fi
    fi
  done

  if [ -z "$best" ]; then
    warn "step $pods had no valid point on the ladder; excluded"
    continue
  fi

  # A peak in the INTERIOR of the ladder is the real capacity. A peak at the
  # top rung means the ladder ran out before the system did, so X(N) is a lower
  # bound and the curve will flatten for a reason that is not the gateway.
  top_rung="${ladder##* }"
  best_vus="$(echo "$best" | cut -d" " -f8)"
  if [ "$best_vus" = "$top_rung" ]; then
    warn "step $pods peaked at the TOP rung (vus=$best_vus): throughput was still"
    warn "rising when the ladder ended, so this point is a lower bound on X($pods)."
  fi

  read -r b_tput b_p50 b_p95 b_p99 b_cpu b_thr b_hit b_vus <<EOF_BEST
$best
EOF_BEST

  ingress_cpu="$(kubectl top pods -n ingress-nginx --no-headers 2>/dev/null     | awk '$1 ~ /controller/ { gsub(/m/, "", $2); print $2; exit }' || true)"

  printf '%s,%s,%s,%s,%s,%s,%s
'     "$pods" "$b_tput" "$b_p50" "$b_p95" "$b_p99" "$b_cpu" "$b_hit" >> "$CSV"

  ok "step $pods: $b_tput rps at vus=$b_vus, p50 $b_p50 ms, p95 $b_p95 ms, hit $b_hit, pod CPU ${b_cpu:-n/a}% (throttled ${b_thr:-n/a}% of periods), ingress ${ingress_cpu:-n/a}m"
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
