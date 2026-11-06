output "cluster_name" {
  description = "EKS cluster name."
  value       = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  description = "EKS API endpoint."
  value       = aws_eks_cluster.main.endpoint
}

output "oidc_provider_arn" {
  description = "IRSA provider ARN, for any further service-account roles."
  value       = aws_iam_openid_connect_provider.eks.arn
}

output "aurora_writer_endpoint" {
  description = "Aurora writer. Every WRITE goes here."
  value       = aws_rds_cluster.aurora.endpoint
}

output "aurora_reader_endpoint" {
  description = <<-EOT
    Aurora reader. Tier-2 cache READS go here.

    Safe because replica lag on this data produces a cache miss, never a wrong
    answer (ADR-0007). NOT for API key lookups or budget checks, where a stale
    read is a security or billing problem.
  EOT
  value       = aws_rds_cluster.aurora.reader_endpoint
}

output "redis_configuration_endpoint" {
  description = "Redis Cluster configuration endpoint. Cluster mode is on."
  value       = aws_elasticache_replication_group.redis.configuration_endpoint_address
}

output "msk_bootstrap_brokers" {
  description = "MSK bootstrap brokers, SASL/IAM."
  value       = aws_msk_serverless_cluster.events.bootstrap_brokers_sasl_iam
}

output "gateway_irsa_role_arn" {
  description = <<-EOT
    Role for the gateway's service account. Annotate the ServiceAccount with:
      eks.amazonaws.com/role-arn: <this value>
  EOT
  value       = aws_iam_role.gateway_irsa.arn
}

output "secret_arns" {
  description = "Secrets the services read. Values are never outputs."
  value = {
    aurora_master = aws_secretsmanager_secret.aurora.arn
    redis_auth    = aws_secretsmanager_secret.redis_auth.arn
  }
}

output "required_topic_configuration" {
  description = <<-EOT
    Topics to create after the cluster exists.

    MSK Serverless has no Terraform resource for topics - partition count and
    retention are set through the Kafka admin API, not through AWS. So these two
    variables cannot be applied; they are surfaced here as the exact commands an
    operator needs, which is better than declaring variables that silently do
    nothing.

    Partition count is the number that matters: consumption is partition-ordered
    by design, so consumer replicas beyond this count sit IDLE. Repartition
    BEFORE scaling consumers - scaling the Deployment first looks like it should
    help and provably cannot.
  EOT
  value = {
    partitions   = var.msk_partitions_per_topic
    retention_ms = var.msk_retention_hours * 3600 * 1000
    topics = [
      "gateway.usage.v1",
      "gateway.cache-write.v1",
      "gateway.audit.v1",
      "gateway.tenant.v1",
      "gateway.dead-letter.v1",
    ]
    create_command = join(" ", [
      "kafka-topics.sh --create",
      "--bootstrap-server ${aws_msk_serverless_cluster.events.bootstrap_brokers_sasl_iam}",
      "--command-config client.properties",
      "--partitions ${var.msk_partitions_per_topic}",
      "--replication-factor 3",
      "--config retention.ms=${var.msk_retention_hours * 3600 * 1000}",
      "--topic <topic>",
    ])
  }
}
