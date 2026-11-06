variable "kubeconfig_path" {
  description = "Path to the kubeconfig k3d wrote."
  type        = string
  default     = "~/.kube/config"
}

variable "kube_context" {
  description = <<-EOT
    Kubeconfig context to target. Pinned on purpose: without it, apply targets
    whichever cluster kubectl currently points at, and deploying into the wrong
    cluster is not recoverable the way a wrong `kubectl get` is.
  EOT
  type        = string
  default     = "k3d-ai-gateway"

  validation {
    condition     = can(regex("^k3d-", var.kube_context))
    error_message = <<-EOT
      This configuration is for a local k3d cluster and the context must start
      with "k3d-". It installs MetalLB and a NodePort-pinned ingress controller,
      which are wrong for a managed cluster, and the guard exists so a stray
      KUBECONFIG cannot point it at a real one.
    EOT
  }
}

# -----------------------------------------------------------------------------
# Chart versions.
#
# Pinned, and each one was checked to exist before being written here. They
# deliberately MATCH scripts/cluster-up.sh and scripts/observability-up.sh: two
# sources of truth for a chart version is how a Terraform apply silently
# downgrades what a script installed.
# -----------------------------------------------------------------------------
variable "metallb_chart_version" {
  description = "MetalLB chart version. Must match scripts/cluster-up.sh."
  type        = string
  default     = "0.16.0"
}

variable "ingress_nginx_chart_version" {
  description = "ingress-nginx chart version. Must match scripts/cluster-up.sh."
  type        = string
  default     = "4.15.1"
}

variable "kube_prometheus_stack_chart_version" {
  description = "kube-prometheus-stack version. Must match scripts/observability-up.sh."
  type        = string
  default     = "92.21.2"
}

variable "prometheus_adapter_chart_version" {
  description = "prometheus-adapter version. Must match scripts/observability-up.sh."
  type        = string
  default     = "5.4.0"
}

# -----------------------------------------------------------------------------
# What to install.
#
# Separate flags rather than one "profile" string, because the combinations are
# genuinely used: metrics without the application when measuring the cluster
# itself, and the application without metrics when iterating on the chart.
#
# The RAM note is not decoration. The full set does not fit alongside Docker
# Desktop on a 7.4 GB machine - see the profile table in PROGRESS.md.
# -----------------------------------------------------------------------------
variable "install_load_balancing" {
  description = "MetalLB + ingress-nginx. ~0.3 GB."
  type        = bool
  default     = true
}

variable "install_observability" {
  description = "kube-prometheus-stack + prometheus-adapter. ~1.0 GB."
  type        = bool
  default     = false
}

variable "install_application" {
  description = "The ai-gateway chart itself. 0.5-2.2 GB depending on the values file."
  type        = bool
  default     = false
}

variable "application_values_file" {
  description = <<-EOT
    Which values profile to deploy. Relative to this directory.

    values-full.yaml does NOT fit on a 7.4 GB laptop - see the header of that
    file. values-dev.yaml is the default for that reason.
  EOT
  type        = string
  default     = "../../deploy/helm/ai-gateway/values-dev.yaml"
}

variable "ingress_http_node_port" {
  description = <<-EOT
    NodePort for ingress HTTP. Must match the host port mapping in
    deploy/k3d/cluster.yaml, or nothing on the host can reach the gateway: a
    MetalLB address is not routable from a Windows or macOS host, so the pinned
    NodePort is the only path that works everywhere.
  EOT
  type        = number
  default     = 30080
}

variable "ingress_https_node_port" {
  description = "NodePort for ingress HTTPS. Must match deploy/k3d/cluster.yaml."
  type        = number
  default     = 30443
}
