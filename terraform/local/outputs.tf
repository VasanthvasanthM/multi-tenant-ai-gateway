output "kube_context" {
  description = "The context this configuration targets."
  value       = var.kube_context
}

output "installed" {
  description = "Which component groups this apply installed."
  value = {
    load_balancing = var.install_load_balancing
    observability  = var.install_observability
    application    = var.install_application
  }
}

output "gateway_url" {
  description = <<-EOT
    How to reach the gateway from the host.

    The mapped host port, not the MetalLB address: that address lives on the
    k3d Docker network and is not routable from a Windows or macOS host.
  EOT
  value       = "http://localhost:8080"
}

output "next_steps" {
  description = "What to do after apply."
  value = join("\n", compact([
    var.install_load_balancing ? "curl -H 'Host: ai-gateway.local' http://localhost:8080/actuator/health" : null,
    var.install_observability ? "kubectl -n monitoring port-forward svc/kps-grafana 3000:80" : null,
    var.install_observability ? "kubectl get hpa   # TARGETS must show a number, not <unknown>" : null,
    var.install_application ? "kubectl get pods -w" : null,
  ]))
}
