# =============================================================================
# The local k3d cluster's Helm releases.
#
# WHAT THIS DOES NOT DO, and why:
#
#   1. It does not create the k3d cluster. k3d has no Terraform provider, and
#      wrapping `k3d cluster create` in a null_resource gives Terraform a
#      resource it cannot read, diff or meaningfully destroy - the worst kind of
#      Terraform, because `plan` then lies. scripts/cluster-up.sh owns the
#      cluster.
#
#   2. It does not create the MetalLB IPAddressPool. That needs the address
#      range derived from the live Docker network, and it has to be retried
#      because MetalLB's validating webhook starts serving a few seconds after
#      its Deployment reports Available. Terraform expresses neither well;
#      cluster-up.sh retries it 30 times.
#
# So: the script owns the cluster and the address pool, Terraform owns the
# releases. That split is deliberate rather than a limitation worked around -
# each tool is doing the part it is actually good at.
#
#   ./scripts/cluster-up.sh          # cluster + MetalLB pool
#   terraform -chdir=terraform/local init
#   terraform -chdir=terraform/local apply
# =============================================================================

locals {
  # Namespaces this configuration owns. monitoring is created only when the
  # observability stack is installed, so a metrics-free apply does not leave an
  # empty namespace behind.
  create_monitoring_namespace = var.install_observability
}

# -----------------------------------------------------------------------------
# Namespaces
#
# kubernetes_namespace_v1, not kubernetes_namespace: the unversioned names are
# deprecated in provider 3.x. The versioned form is also the stable one - it
# tracks the Kubernetes API version rather than the provider's own naming, so it
# does not change meaning under a provider upgrade.
# -----------------------------------------------------------------------------
resource "kubernetes_namespace_v1" "metallb" {
  count = var.install_load_balancing ? 1 : 0

  metadata {
    name = "metallb-system"

    # MetalLB's speaker needs privileged pod security to manipulate ARP. On a
    # cluster with Pod Security admission enforcing `restricted`, the speaker
    # pods are rejected and the only symptom is a LoadBalancer Service that
    # never gets an address.
    labels = {
      "pod-security.kubernetes.io/enforce" = "privileged"
      "pod-security.kubernetes.io/audit"   = "privileged"
      "pod-security.kubernetes.io/warn"    = "privileged"
    }
  }
}

resource "kubernetes_namespace_v1" "ingress_nginx" {
  count = var.install_load_balancing ? 1 : 0

  metadata {
    name = "ingress-nginx"
  }
}

resource "kubernetes_namespace_v1" "monitoring" {
  count = local.create_monitoring_namespace ? 1 : 0

  metadata {
    name = "monitoring"
  }
}

# -----------------------------------------------------------------------------
# MetalLB - the L4 load balancer
#
# Without it, a `type: LoadBalancer` Service sits in <pending> forever, because
# k3s's own servicelb is disabled in deploy/k3d/cluster.yaml (leaving both
# enabled makes them race for every Service).
# -----------------------------------------------------------------------------
resource "helm_release" "metallb" {
  count = var.install_load_balancing ? 1 : 0

  name       = "metallb"
  repository = "https://metallb.github.io/metallb"
  chart      = "metallb"
  version    = var.metallb_chart_version
  namespace  = kubernetes_namespace_v1.metallb[0].metadata[0].name

  # MetalLB installs CRDs and a webhook; the address pool applied afterwards
  # depends on both being ready.
  wait    = true
  timeout = 300
}

# -----------------------------------------------------------------------------
# ingress-nginx - the L7 load balancer
# -----------------------------------------------------------------------------
resource "helm_release" "ingress_nginx" {
  count = var.install_load_balancing ? 1 : 0

  name       = "ingress-nginx"
  repository = "https://kubernetes.github.io/ingress-nginx"
  chart      = "ingress-nginx"
  version    = var.ingress_nginx_chart_version
  namespace  = kubernetes_namespace_v1.ingress_nginx[0].metadata[0].name

  wait    = true
  timeout = 300

  set = [
    # LoadBalancer, so MetalLB performs the real L4 allocation...
    {
      name  = "controller.service.type"
      value = "LoadBalancer"
    },
    # ...and a PINNED NodePort, because a MetalLB address is not routable from a
    # Windows or macOS host. A LoadBalancer Service allocates a NodePort anyway,
    # so pinning it and mapping a k3d host port onto it gives the host a path
    # that works on every platform. Both paths reach the same Service.
    {
      name  = "controller.service.nodePorts.http"
      value = tostring(var.ingress_http_node_port)
    },
    {
      name  = "controller.service.nodePorts.https"
      value = tostring(var.ingress_https_node_port)
    },
    {
      name  = "controller.replicaCount"
      value = "1"
    },
    {
      name  = "controller.resources.requests.cpu"
      value = "100m"
    },
    {
      name  = "controller.resources.requests.memory"
      value = "128Mi"
    },
    {
      name  = "controller.metrics.enabled"
      value = "true"
    },
    # The load-generation settings. Without these ingress-nginx saturates before
    # the gateway does, and the USL fit in P12 would describe the ingress
    # controller rather than the service under test.
    {
      name  = "controller.config.max-worker-connections"
      value = "16384"
    },
    {
      name  = "controller.config.upstream-keepalive-connections"
      value = "320"
    },
  ]

  depends_on = [helm_release.metallb]
}

# -----------------------------------------------------------------------------
# Observability
#
# Values come from the same files the scripts use, so a Terraform apply and a
# script run produce the same cluster. Two copies of these settings is how they
# drift.
# -----------------------------------------------------------------------------
resource "helm_release" "kube_prometheus_stack" {
  count = var.install_observability ? 1 : 0

  name       = "kps"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = var.kube_prometheus_stack_chart_version
  namespace  = kubernetes_namespace_v1.monitoring[0].metadata[0].name

  values = [
    file("${path.module}/../../deploy/k8s/observability/kube-prometheus-stack-values.yaml")
  ]

  # The operator installs CRDs and a webhook, and prometheus-adapter plus any
  # ServiceMonitor depends on them existing.
  wait    = true
  timeout = 900
}

resource "helm_release" "prometheus_adapter" {
  count = var.install_observability ? 1 : 0

  name       = "prometheus-adapter"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "prometheus-adapter"
  version    = var.prometheus_adapter_chart_version
  namespace  = kubernetes_namespace_v1.monitoring[0].metadata[0].name

  values = [
    file("${path.module}/../../deploy/k8s/prometheus-adapter/values.yaml")
  ]

  wait    = true
  timeout = 300

  # Must come after Prometheus: the adapter queries it, and an adapter that
  # starts first reports every metric as unavailable until it retries.
  depends_on = [helm_release.kube_prometheus_stack]
}

# -----------------------------------------------------------------------------
# The application
#
# From the local chart path rather than a repository, so a chart change is
# deployed by `terraform apply` without a publish step.
# -----------------------------------------------------------------------------
resource "helm_release" "ai_gateway" {
  count = var.install_application ? 1 : 0

  name      = "aigw"
  chart     = "${path.module}/../../deploy/helm/ai-gateway"
  namespace = "default"

  values = [
    file("${path.module}/${var.application_values_file}")
  ]

  # Not waiting, deliberately.
  #
  # The gateway needs Redis and PostgreSQL, which this configuration does not
  # deploy (see Chart.yaml on why stateful infrastructure is not a subchart).
  # With wait = true, apply blocks for the full timeout and then fails on a
  # readiness probe that is correctly reporting a missing dependency - which
  # looks like a Terraform problem and is not one.
  wait = false

  depends_on = [helm_release.ingress_nginx]
}
