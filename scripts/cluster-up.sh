#!/usr/bin/env bash
#
# Create the local k3d cluster and the load balancing layer the gateway needs.
#
#   ./scripts/cluster-up.sh              # 1 server + 1 agent
#   AGENTS=2 ./scripts/cluster-up.sh     # spread replicas across nodes
#   SKIP_RAM_CHECK=1 ./scripts/cluster-up.sh
#
# What this installs, and why each piece is here rather than assumed:
#
#   k3d            a real multi-node Kubernetes control plane in Docker
#   MetalLB        an L4 load balancer, so a `type: LoadBalancer` Service
#                  resolves to an address instead of hanging in <pending>
#   ingress-nginx  the L7 load balancer, with least-connections balancing
#
# Idempotent: re-running reuses an existing cluster and upgrades the charts.
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-ai-gateway}"
AGENTS="${AGENTS:-1}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Pinned, and every one of these versions was checked to exist before being
# written here. An unresolvable version reports itself in the least useful
# possible place - see the trivy-action entry in PROGRESS.md.
METALLB_CHART_VERSION="0.16.0"
INGRESS_NGINX_CHART_VERSION="4.15.1"

# Memory the cluster needs before anything is created. Two k3s nodes, MetalLB,
# ingress-nginx and CoreDNS land at roughly this much. Below it the cluster
# comes up far enough to look healthy and then pods are OOMKilled one at a
# time, which is a far more confusing failure than refusing to start.
REQUIRED_MB=2000

# Host ports mapped onto the pinned ingress nodePorts. Overridable because 8080
# collides with almost every other local dev server.
HTTP_PORT="${HTTP_PORT:-8080}"
HTTPS_PORT="${HTTPS_PORT:-8443}"

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m  !!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Runs the first interpreter that ACTUALLY executes, not the first one on PATH.
# On Windows, python3 is an App Execution Alias stub that lives on PATH, passes
# `command -v`, and then prints "Python was not found" to stdout and exits 9009.
# Probing with -c is the only check that distinguishes it from an interpreter.
PY=""
for cand in python3 python py; do
  if command -v "$cand" >/dev/null 2>&1 && "$cand" -c 'import sys' >/dev/null 2>&1; then
    PY="$cand"
    break
  fi
done
py() {
  [ -n "$PY" ] || die "no working Python 3 found (tried python3, python, py). cluster-up needs one to compute the MetalLB pool range from the Docker subnet."
  "$PY" "$@"
}

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
info "Preflight"

for tool in docker kubectl helm k3d; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is not installed.
  k3d:     https://k3d.io/stable/#installation   (or: choco install k3d)
  kubectl: https://kubernetes.io/docs/tasks/tools/
  helm:    https://helm.sh/docs/intro/install/"
done
ok "docker, kubectl, helm, k3d present"

docker info >/dev/null 2>&1 || die "Docker is not running. Start Docker Desktop and retry."
ok "docker daemon responding"

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
  if [ -n "$AVAIL" ] && [ "$AVAIL" -lt "$REQUIRED_MB" ]; then
    die "only ${AVAIL} MB of memory available; this cluster needs about ${REQUIRED_MB} MB.

  A cluster started under this much memory does come up, and then pods are
  OOMKilled one at a time - which reads as an application bug rather than the
  resource problem it is. Free memory first:

    docker stop aigw-pg aigw-redis    # the local dev infrastructure
    wsl --shutdown                    # reclaims the WSL2 VM; restart Docker after

  Then re-run. To proceed anyway: SKIP_RAM_CHECK=1 $0"
  fi
  ok "${AVAIL:-unknown} MB available (need ~${REQUIRED_MB} MB)"
fi

# ---------------------------------------------------------------------------
# Cluster
# ---------------------------------------------------------------------------
if k3d cluster list "$CLUSTER_NAME" >/dev/null 2>&1; then
  info "Cluster '$CLUSTER_NAME' already exists - reusing it"
  k3d cluster start "$CLUSTER_NAME" >/dev/null 2>&1 || true
else
  info "Creating cluster '$CLUSTER_NAME' (1 server + $AGENTS agent)"

  # Host ports are overridable, because 8080 is the most contended port on a
  # developer machine - Tomcat, Jenkins and every Spring Boot app default to it.
  # k3d's failure when it is taken is a raw Docker bind error deep in a rollback
  # trace, which reads as a k3d bug rather than "something else owns the port".
  #
  # The config file stays the source of truth for the mapping; only the HOST side
  # is substituted, so the pinned nodePorts below still line up.
  CLUSTER_CONFIG="$REPO_ROOT/deploy/k3d/cluster.yaml"
  if [ "$HTTP_PORT" != "8080" ] || [ "$HTTPS_PORT" != "8443" ]; then
    CLUSTER_CONFIG="$(mktemp)"
    sed -e "s|\"8080:30080\"|\"${HTTP_PORT}:30080\"|"         -e "s|\"8443:30443\"|\"${HTTPS_PORT}:30443\"|"         "$REPO_ROOT/deploy/k3d/cluster.yaml" > "$CLUSTER_CONFIG"
    info "host ports remapped to ${HTTP_PORT}/${HTTPS_PORT}"
  fi

  if ! k3d cluster create --config "$CLUSTER_CONFIG" --agents "$AGENTS"; then
    die "cluster creation failed.

  If the error mentions 'Ports are not available', another process owns
  ${HTTP_PORT} or ${HTTPS_PORT}. Find it with:

    powershell \"Get-NetTCPConnection -LocalPort ${HTTP_PORT} -State Listen |
      ForEach-Object { Get-Process -Id \$_.OwningProcess }\"

  Then either stop it, or pick different host ports:

    HTTP_PORT=18080 HTTPS_PORT=18443 $0"
  fi
fi

kubectl config use-context "k3d-$CLUSTER_NAME" >/dev/null

# Repoint the API server at loopback.
#
# k3d writes `https://host.docker.internal:<port>` into the kubeconfig. On a
# machine with several interfaces - notably one tethered to a phone, where
# 192.168.43.x is the standard Android hotspot range - Docker Desktop resolves
# that name to the LAN address rather than to the host loopback, and every
# kubectl call then times out against an address nothing is listening on.
#
# The symptom is maximally misleading: `k3d cluster create` reports success, the
# containers are healthy, and kubectl says "Unable to connect to the server",
# which reads as a broken cluster. It is a name-resolution problem on the client
# side only. 127.0.0.1 is correct on every platform here, because the port is
# published to the host by Docker.
API_PORT="$(kubectl config view -o jsonpath="{.clusters[?(@.name==\"k3d-$CLUSTER_NAME\")].cluster.server}" | sed 's|.*:||')"
if [ -n "$API_PORT" ]; then
  kubectl config set-cluster "k3d-$CLUSTER_NAME"     --server="https://127.0.0.1:$API_PORT" >/dev/null
  ok "API server pinned to https://127.0.0.1:$API_PORT"
fi
# Wait for the API server to SERVE, not merely to accept a connection.
#
# `k3d cluster start` returns as soon as the containers are running, and k3s then
# takes several more seconds to bring up the apiserver's resource types. In that
# window kubectl connects successfully and fails with "the server doesn't have a
# resource type nodes" - which reads as a broken cluster or a version mismatch
# rather than as "ask again in five seconds".
#
# `kubectl wait` cannot cover this: it needs the resource type to exist before it
# can wait on a condition. Hence polling first.
#
# The probe is `get nodes`, not `--raw /readyz`: k3s does not serve /readyz at
# that path and returns NotFound even on a perfectly healthy cluster, so a
# readiness loop built on it waits out its whole timeout against a working
# API server. Probing the exact call that fails is the only honest check.
#
# 300s, not 120s. On a memory-constrained host k3s takes minutes to register
# resource types after the container reports Up, and a timeout shorter than
# reality turns a slow start into a hard failure.
info "Waiting for the API server to serve"
API_READY=0
for _ in $(seq 1 150); do
  if kubectl get nodes >/dev/null 2>&1; then
    API_READY=1
    break
  fi
  sleep 2
done
[ "$API_READY" = "1" ] || die "the API server did not become ready within 300s.

  Check the container is up and not restarting:

    docker logs k3d-${CLUSTER_NAME}-server-0 --tail 50"
ok "API server serving"

kubectl wait --for=condition=Ready nodes --all --timeout=180s >/dev/null
ok "$(kubectl get nodes --no-headers | wc -l | tr -d ' ') node(s) Ready"

# ---------------------------------------------------------------------------
# MetalLB
# ---------------------------------------------------------------------------
info "Installing MetalLB ${METALLB_CHART_VERSION}"

helm repo add metallb https://metallb.github.io/metallb >/dev/null 2>&1 || true
helm repo update metallb >/dev/null

# frrk8s explicitly OFF.
#
# Two reasons, and the first is sufficient on its own: this cluster advertises
# with L2Advertisement, so the FRR backend exists for BGP and is never used.
#
# The second is that leaving it at the chart default does not merely waste a
# DaemonSet - MetalLB 0.16.0 FAILS TO TEMPLATE. Its bundled frr-k8s subchart
# renders service-monitor.yaml against `.Values.prometheus.serviceMonitor`,
# which the parent chart never passes down, so helm dies with
# "nil pointer evaluating interface {}.serviceMonitor" before installing
# anything. That reads as a broken Helm repository rather than as a subchart
# this deployment does not want.
helm upgrade --install metallb metallb/metallb \
  --namespace metallb-system --create-namespace \
  --version "$METALLB_CHART_VERSION" \
  --set frrk8s.enabled=false \
  --set speaker.frr.enabled=false \
  --wait --timeout 5m >/dev/null
ok "MetalLB installed"

# The address pool has to come from the Docker network the cluster actually
# runs on. Hardcoding 172.18.x would work on exactly one machine: Docker picks
# the subnet when it creates the network, and it differs between hosts and
# between recreations of the same cluster.
NETWORK="k3d-${CLUSTER_NAME}"
SUBNET="$(docker network inspect "$NETWORK" -f '{{ (index .IPAM.Config 0).Subnet }}')"
[ -n "$SUBNET" ] || die "could not read the subnet of Docker network '$NETWORK'"

# Taken from the TOP of the range: Docker hands out container addresses from
# the bottom, so the top is the part that will not collide with a node.
# Computed arithmetically rather than by listing hosts() - a /16 has 65k of
# them and a /8 would hang.
POOL_RANGE="$(py - "$SUBNET" <<'PYEOF'
import ipaddress, sys
net = ipaddress.ip_network(sys.argv[1], strict=False)
last = int(net.broadcast_address)
print(f"{ipaddress.ip_address(last - 60)}-{ipaddress.ip_address(last - 11)}")
PYEOF
)"
ok "network $NETWORK is $SUBNET -> pool $POOL_RANGE"

# MetalLB CRDs are guarded by a validating webhook, and that webhook starts
# serving slightly after the Deployment reports Available. Applying the pool
# immediately fails with "connection refused" perhaps one run in three, which
# looks random. Retry rather than sleeping a guessed interval.
info "Applying address pool"
for attempt in $(seq 1 30); do
  if kubectl apply -f - >/dev/null 2>&1 <<POOLEOF
apiVersion: metallb.io/v1beta1
kind: IPAddressPool
metadata:
  name: ai-gateway-pool
  namespace: metallb-system
spec:
  addresses:
    - $POOL_RANGE
---
# L2, not BGP. L2Advertisement answers ARP for the pool addresses from one
# elected node and needs no router configuration, which is the only option on
# a laptop. BGP is the production mode and requires a peer that speaks it.
apiVersion: metallb.io/v1beta1
kind: L2Advertisement
metadata:
  name: ai-gateway-l2
  namespace: metallb-system
spec:
  ipAddressPools:
    - ai-gateway-pool
POOLEOF
  then
    ok "pool applied on attempt $attempt"
    break
  fi
  [ "$attempt" -eq 30 ] && die "MetalLB webhook never became ready. Check: kubectl -n metallb-system get pods"
  sleep 2
done

# ---------------------------------------------------------------------------
# ingress-nginx
# ---------------------------------------------------------------------------
info "Installing ingress-nginx ${INGRESS_NGINX_CHART_VERSION}"

helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx >/dev/null 2>&1 || true
helm repo update ingress-nginx >/dev/null

# nodePorts are PINNED to the values deploy/k3d/cluster.yaml maps host ports
# onto. A LoadBalancer Service also allocates a NodePort, so MetalLB still
# performs the real L4 allocation while the pinned port gives a Windows or
# macOS host a path that works - the MetalLB address itself is not routable
# from the host on those platforms.
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx --create-namespace \
  --version "$INGRESS_NGINX_CHART_VERSION" \
  --set controller.service.type=LoadBalancer \
  --set controller.service.nodePorts.http=30080 \
  --set controller.service.nodePorts.https=30443 \
  --set controller.replicaCount=1 \
  --set controller.resources.requests.cpu=100m \
  --set controller.resources.requests.memory=128Mi \
  --set controller.metrics.enabled=true \
  --wait --timeout 5m >/dev/null
ok "ingress-nginx installed"

LB_IP="$(kubectl -n ingress-nginx get svc ingress-nginx-controller \
          -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || true)"

if [ -z "$LB_IP" ]; then
  warn "ingress Service has no external IP yet. MetalLB may still be assigning:"
  warn "  kubectl -n ingress-nginx get svc ingress-nginx-controller -w"
fi

# ---------------------------------------------------------------------------
info "Cluster ready"
cat <<SUMMARYEOF

  context        k3d-${CLUSTER_NAME}
  nodes          $(kubectl get nodes --no-headers | wc -l | tr -d ' ')
  MetalLB pool   ${POOL_RANGE}
  ingress IP     ${LB_IP:-<pending>}   (routable from inside the Docker network)

  Reach the gateway from THIS machine on the mapped host port. The MetalLB
  address is not routable from a Windows or macOS host:

      curl -H 'Host: ai-gateway.local' http://localhost:${HTTP_PORT}/actuator/health

  Deploy the gateway:

      helm upgrade --install aigw deploy/helm/ai-gateway \\
        -f deploy/helm/ai-gateway/values-dev.yaml

  Tear down:

      ./scripts/cluster-down.sh

SUMMARYEOF
