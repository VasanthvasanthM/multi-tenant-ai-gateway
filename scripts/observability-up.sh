#!/usr/bin/env bash
#
# Install the observability stack into the k3d cluster.
#
#   ./scripts/observability-up.sh                 # metrics  (default)
#   ./scripts/observability-up.sh --profile logs
#   ./scripts/observability-up.sh --profile traces
#   ./scripts/observability-up.sh --profile full
#   SKIP_RAM_CHECK=1 ./scripts/observability-up.sh
#
# PROFILES, and what each one is for:
#
#   metrics   Prometheus + Grafana + prometheus-adapter + the dashboards.
#             This is the MINIMUM the rest of the project needs: without
#             prometheus-adapter the RPS-based HPA has no metric, reports
#             <unknown>, and silently governs on CPU instead - so the P12
#             scaling measurement would be measuring the wrong signal.
#             ~1.0 GB
#
#   logs      + Loki + Alloy. Every gateway log line carries the trace and span
#             id, so a log search and a trace view can be joined.
#             ~1.4 GB
#
#   traces    + Tempo, and the application chart is told where to send spans.
#             The services already have the OTLP exporter and a 10% sampling
#             rate; what they lack is a destination.
#             ~1.3 GB
#
#   full      Everything. ~1.7 GB on top of the cluster and the application -
#             does NOT fit on a 7.4 GB laptop. Use a single profile at a time.
#
# Idempotent: re-running upgrades in place.
set -euo pipefail

PROFILE="metrics"
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) PROFILE="${2:-}"; shift 2 ;;
    --profile=*) PROFILE="${1#*=}"; shift ;;
    -h|--help) sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

case "$PROFILE" in
  metrics) WANT_LOGS=0; WANT_TRACES=0; NEED_MB=1300 ;;
  logs)    WANT_LOGS=1; WANT_TRACES=0; NEED_MB=1700 ;;
  traces)  WANT_LOGS=0; WANT_TRACES=1; NEED_MB=1600 ;;
  full)    WANT_LOGS=1; WANT_TRACES=1; NEED_MB=2000 ;;
  *) echo "unknown profile '$PROFILE' (metrics|logs|traces|full)" >&2; exit 2 ;;
esac

NS=monitoring
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALUES="$REPO_ROOT/deploy/k8s/observability"

# Pinned, and every version was checked to exist before being written here.
KPS_VERSION="92.21.2"
ADAPTER_VERSION="5.4.0"
LOKI_VERSION="7.5.0"
TEMPO_VERSION="1.24.4"
ALLOY_VERSION="1.15.0"

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m  !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
info "Preflight (profile: $PROFILE)"

for tool in kubectl helm; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed"
done

kubectl cluster-info >/dev/null 2>&1 \
  || die "no reachable cluster. Run ./scripts/cluster-up.sh first."
ok "cluster reachable: $(kubectl config current-context)"

available_mb() {
  if [ -r /proc/meminfo ]; then
    awk '/^MemAvailable:/ {printf "%d", $2/1024}' /proc/meminfo
  elif command -v powershell >/dev/null 2>&1; then
    powershell -NoProfile -Command \
      '[int](Get-Counter "\Memory\Available MBytes").CounterSamples[0].CookedValue' 2>/dev/null | tr -d '\r'
  else
    echo ""
  fi
}

if [ "${SKIP_RAM_CHECK:-0}" != "1" ]; then
  AVAIL="$(available_mb)"
  if [ -n "$AVAIL" ] && [ "$AVAIL" -lt "$NEED_MB" ]; then
    die "only ${AVAIL} MB available; the '$PROFILE' profile needs about ${NEED_MB} MB.

  A Prometheus that is OOMKilled mid-run invalidates the measurement it exists
  to take, and the pod restart looks like a Prometheus bug rather than a
  resource problem. Free memory, or use a smaller profile:

    ./scripts/observability-up.sh --profile metrics   # the minimum P12 needs

  To proceed anyway: SKIP_RAM_CHECK=1 $0 --profile $PROFILE"
  fi
  ok "${AVAIL:-unknown} MB available (need ~${NEED_MB} MB)"
fi

helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community grafana >/dev/null
ok "chart repositories updated"

# ---------------------------------------------------------------------------
# Prometheus + Grafana
# ---------------------------------------------------------------------------
info "Installing kube-prometheus-stack ${KPS_VERSION}"
helm upgrade --install kps prometheus-community/kube-prometheus-stack \
  --namespace "$NS" --create-namespace \
  --version "$KPS_VERSION" \
  -f "$VALUES/kube-prometheus-stack-values.yaml" \
  --wait --timeout 10m >/dev/null
ok "Prometheus and Grafana installed"

# The CRDs must exist before any ServiceMonitor is applied, including the one
# the application chart creates. Waiting here rather than hoping.
kubectl wait --for condition=established --timeout=120s \
  crd/servicemonitors.monitoring.coreos.com >/dev/null
ok "ServiceMonitor CRD established"

# ---------------------------------------------------------------------------
# Dashboards
#
# Loaded as ConfigMaps labelled grafana_dashboard, which the chart's sidecar
# watches. Deliberately NOT baked into the Grafana values: a dashboard in a
# values file is a JSON blob inside YAML, which no tool can lint and no diff can
# review.
# ---------------------------------------------------------------------------
info "Loading dashboards"
for dash in "$REPO_ROOT"/deploy/grafana/dashboards/*.json; do
  name="$(basename "$dash" .json)"
  kubectl create configmap "dashboard-$name" \
    --namespace "$NS" \
    --from-file="$(basename "$dash")=$dash" \
    --dry-run=client -o yaml \
    | kubectl label --local -f - grafana_dashboard=1 -o yaml \
    | kubectl apply -f - >/dev/null
  ok "$name"
done

# ---------------------------------------------------------------------------
# prometheus-adapter - what makes the RPS HPA work at all
# ---------------------------------------------------------------------------
info "Installing prometheus-adapter ${ADAPTER_VERSION}"
helm upgrade --install prometheus-adapter prometheus-community/prometheus-adapter \
  --namespace "$NS" \
  --version "$ADAPTER_VERSION" \
  -f "$REPO_ROOT/deploy/k8s/prometheus-adapter/values.yaml" \
  --wait --timeout 5m >/dev/null
ok "prometheus-adapter installed"

# ---------------------------------------------------------------------------
# Loki + Alloy
# ---------------------------------------------------------------------------
if [ "$WANT_LOGS" = "1" ]; then
  info "Installing Loki ${LOKI_VERSION}"
  helm upgrade --install loki grafana/loki \
    --namespace "$NS" \
    --version "$LOKI_VERSION" \
    -f "$VALUES/loki-values.yaml" \
    --wait --timeout 10m >/dev/null
  ok "Loki installed"

  info "Installing Alloy ${ALLOY_VERSION}"
  helm upgrade --install alloy grafana/alloy \
    --namespace "$NS" \
    --version "$ALLOY_VERSION" \
    -f "$VALUES/alloy-values.yaml" \
    --wait --timeout 5m >/dev/null
  ok "Alloy installed"
fi

# ---------------------------------------------------------------------------
# Tempo
# ---------------------------------------------------------------------------
if [ "$WANT_TRACES" = "1" ]; then
  info "Installing Tempo ${TEMPO_VERSION}"
  helm upgrade --install tempo grafana/tempo \
    --namespace "$NS" \
    --version "$TEMPO_VERSION" \
    -f "$VALUES/tempo-values.yaml" \
    --wait --timeout 10m >/dev/null
  ok "Tempo installed"
fi

# ---------------------------------------------------------------------------
# Grafana datasources for Loki and Tempo
#
# Prometheus is provisioned by kube-prometheus-stack itself. These two are added
# as a labelled ConfigMap for the datasource sidecar, with the correlation
# settings that make the three stores one tool rather than three:
#
#   Tempo -> Loki     "show me the logs for this trace"
#   Loki  -> Tempo    "show me the trace for this log line"
#
# Without derivedFields and tracesToLogs, each store is a separate search box
# and the trace id in every log line goes unused.
# ---------------------------------------------------------------------------
if [ "$WANT_LOGS" = "1" ] || [ "$WANT_TRACES" = "1" ]; then
  info "Provisioning Grafana datasources"

  {
    echo "apiVersion: v1"
    echo "kind: ConfigMap"
    echo "metadata:"
    echo "  name: grafana-datasources-extra"
    echo "  namespace: $NS"
    echo "  labels:"
    echo "    grafana_datasource: \"1\""
    echo "data:"
    echo "  extra-datasources.yaml: |"
    echo "    apiVersion: 1"
    echo "    datasources:"

    if [ "$WANT_LOGS" = "1" ]; then
      echo "      - name: Loki"
      echo "        type: loki"
      echo "        uid: loki"
      echo "        access: proxy"
      echo "        url: http://loki.${NS}.svc:3100"
      echo "        jsonData:"
      if [ "$WANT_TRACES" = "1" ]; then
        echo "          derivedFields:"
        echo "            - name: TraceID"
        # Alloy puts the trace id in structured metadata, not in a label, because
        # a trace id as a label would create one Loki stream per request.
        echo "              matcherType: label"
        echo "              matcherRegex: trace_id"
        echo "              datasourceUid: tempo"
        echo "              url: '\${__value.raw}'"
      fi
    fi

    if [ "$WANT_TRACES" = "1" ]; then
      echo "      - name: Tempo"
      echo "        type: tempo"
      echo "        uid: tempo"
      echo "        access: proxy"
      echo "        url: http://tempo.${NS}.svc:3200"
      echo "        jsonData:"
      if [ "$WANT_LOGS" = "1" ]; then
        echo "          tracesToLogsV2:"
        echo "            datasourceUid: loki"
        echo "            spanStartTimeShift: '-5m'"
        echo "            spanEndTimeShift: '5m'"
        echo "            filterByTraceID: true"
        echo "            tags:"
        echo "              - key: 'service.name'"
        echo "                value: 'component'"
      fi
      echo "          serviceMap:"
      echo "            datasourceUid: prometheus"
    fi
  } | kubectl apply -f - >/dev/null
  ok "datasources provisioned"

  # Grafana only reloads datasources on restart.
  kubectl rollout restart deployment/kps-grafana --namespace "$NS" >/dev/null 2>&1 || true
  kubectl rollout status deployment/kps-grafana --namespace "$NS" --timeout=180s >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------------------
info "Observability ready"

ADAPTER_OK="no"
kubectl get apiservices v1beta1.custom.metrics.k8s.io >/dev/null 2>&1 && ADAPTER_OK="yes"

cat <<SUMMARYEOF

  namespace        $NS
  profile          $PROFILE
  custom metrics   ${ADAPTER_OK}   (v1beta1.custom.metrics.k8s.io registered)

  Grafana (admin / admin, anonymous viewing is on):

      kubectl -n $NS port-forward svc/kps-grafana 3000:80
      open http://localhost:3000

  Prometheus:

      kubectl -n $NS port-forward svc/kps-kube-prometheus-stack-prometheus 9090:9090

  BEFORE TRUSTING ANY LOAD RUN, confirm the HPA has a real number. An HPA whose
  metric is unavailable reports <unknown> and falls back to CPU WITHOUT erroring,
  and an I/O-bound gateway on a CPU target produces a flat replica count that
  looks like evidence it does not need to scale:

      kubectl get hpa
      # TARGETS must show a number, not <unknown>

  If it shows <unknown>, the metric is not being served. Check directly:

      kubectl get --raw \\
        "/apis/custom.metrics.k8s.io/v1beta1/namespaces/default/pods/*/gateway_requests_per_second"

SUMMARYEOF

if [ "$WANT_TRACES" = "1" ]; then
  cat <<TRACEEOF
  Tempo is installed but the services are not yet sending to it. Redeploy the
  application with tracing enabled - it is off by default so that a missing
  collector does not make every span flush log a connection error:

      helm upgrade --install aigw deploy/helm/ai-gateway \\
        -f deploy/helm/ai-gateway/values-dev.yaml \\
        --set monitoring.tracing.enabled=true

TRACEEOF
fi
