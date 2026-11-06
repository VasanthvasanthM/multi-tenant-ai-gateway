terraform {
  required_version = ">= 1.9"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.0"
    }
  }

  # No remote backend. This state describes a disposable k3d cluster on one
  # laptop; an S3 backend would mean an AWS account and a bucket for state about
  # a cluster that is deleted at the end of the day.
  #
  # The AWS target has the same local backend for a different reason - see
  # ../aws/versions.tf, which is never applied at all.
}

# -----------------------------------------------------------------------------
# Both providers read the kubeconfig k3d wrote.
#
# The CONTEXT is pinned rather than taking whatever is current. Without it,
# `terraform apply` targets whichever cluster kubectl happens to point at, and
# the failure mode is deploying into the wrong cluster - which is unrecoverable
# in a way a wrong `kubectl get` is not.
# -----------------------------------------------------------------------------
provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

provider "helm" {
  kubernetes = {
    config_path    = var.kubeconfig_path
    config_context = var.kube_context
  }
}
