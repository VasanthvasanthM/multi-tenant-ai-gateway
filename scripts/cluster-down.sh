#!/usr/bin/env bash
#
# Tear down the local k3d cluster.
#
#   ./scripts/cluster-down.sh           # stop, keeping the cluster on disk
#   DELETE=1 ./scripts/cluster-down.sh  # delete it entirely
#
# Stop is the default on purpose. Deleting discards the pulled images along
# with the cluster, and re-pulling ingress-nginx, MetalLB and five service
# images costs several minutes on a slow connection. Stopping frees the
# memory, which is the thing that is actually scarce here.
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-ai-gateway}"

info() { printf '\033[0;36m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[0;32m  ok\033[0m %s\n' "$*"; }

command -v k3d >/dev/null 2>&1 || { echo "k3d is not installed - nothing to do."; exit 0; }

if ! k3d cluster list "$CLUSTER_NAME" >/dev/null 2>&1; then
  echo "No cluster named '$CLUSTER_NAME'."
  exit 0
fi

if [ "${DELETE:-0}" = "1" ]; then
  info "Deleting cluster '$CLUSTER_NAME'"
  k3d cluster delete "$CLUSTER_NAME"
  ok "deleted"
else
  info "Stopping cluster '$CLUSTER_NAME'"
  k3d cluster stop "$CLUSTER_NAME"
  ok "stopped - restart with ./scripts/cluster-up.sh, or delete with DELETE=1 $0"
fi
