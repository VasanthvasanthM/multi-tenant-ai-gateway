variable "region" {
  description = "AWS region. One region only - see README.md on why multi-region is out of scope."
  type        = string
  default     = "eu-west-1"
}

variable "environment" {
  description = "Environment name, used in resource names and tags."
  type        = string
  default     = "prod"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "name_prefix" {
  description = "Prefix for every resource name."
  type        = string
  default     = "aigw"
}

# -----------------------------------------------------------------------------
# Network
# -----------------------------------------------------------------------------
variable "vpc_cidr" {
  description = <<-EOT
    VPC CIDR. A /16 rather than something tighter.

    EKS assigns a VPC IP per POD, not per node, so address exhaustion is a real
    failure mode: 200 pods per node across 20 nodes is 4,000 addresses before
    counting the control plane, the load balancers and the databases. A /20
    would run out and the symptom is pods stuck in ContainerCreating with
    "failed to assign an IP address".
  EOT
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zone_count" {
  description = <<-EOT
    Availability zones to spread across.

    Three, not two. Aurora and MSK both want three for quorum, and with two a
    single AZ failure leaves no majority - so a zone outage becomes a write
    outage rather than a capacity reduction.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.availability_zone_count >= 3
    error_message = "Aurora and MSK need three AZs for quorum; two cannot form a majority."
  }
}

# -----------------------------------------------------------------------------
# EKS
# -----------------------------------------------------------------------------
variable "kubernetes_version" {
  description = <<-EOT
    EKS control-plane version.

    NOT verified against the API, because this configuration is never applied
    and `validate` does not check it. Confirm before any real use:
      aws eks describe-addon-versions --query 'addons[0].addonVersions[0]'
  EOT
  type        = string
  default     = "1.33"
}

variable "gateway_node_instance_type" {
  description = <<-EOT
    Instance type for the gateway node group.

    Compute-optimised, not general purpose: the gateway is I/O-bound but the
    ONNX embedding inference on the Tier-2 path is genuinely CPU-bound, and it
    is the only part of a request that cannot be made to wait.
  EOT
  type        = string
  default     = "c7i.2xlarge"
}

variable "gateway_node_min_size" {
  description = "Minimum nodes in the gateway group."
  type        = number
  default     = 3
}

variable "gateway_node_max_size" {
  description = <<-EOT
    Maximum nodes in the gateway group.

    20 x c7i.2xlarge is 160 vCPU. At the capacity table in
    docs/architecture/scaling-model.md that is comfortably past the ~92,600 RPS
    peak the billion-user arithmetic derives - the gateway tier is not the hard
    part, state is.
  EOT
  type        = number
  default     = 20
}

# -----------------------------------------------------------------------------
# Aurora PostgreSQL - the Tier-2 vector store
# -----------------------------------------------------------------------------
variable "aurora_engine_version" {
  description = <<-EOT
    Aurora PostgreSQL version. Must be 15.5 or later for pgvector with HNSW.

    NOT verified against the API, for the same reason as kubernetes_version.
    Confirm with:
      aws rds describe-db-engine-versions --engine aurora-postgresql \
        --query 'DBEngineVersions[].EngineVersion'
  EOT
  type        = string
  default     = "16.6"
}

variable "aurora_min_capacity" {
  description = "Aurora Serverless v2 minimum ACUs. 1 ACU is ~2 GiB."
  type        = number
  default     = 2
}

variable "aurora_max_capacity" {
  description = <<-EOT
    Aurora Serverless v2 maximum ACUs.

    Sized for the HNSW index rather than for queries. An HNSW index must stay in
    memory to be fast, and at the scale in scaling-model.md the per-tenant
    vector corpus is the thing that grows without bound - so this is the first
    limit the system hits (see "What breaks first", item 1).
  EOT
  type        = number
  default     = 64
}

variable "aurora_replica_count" {
  description = <<-EOT
    Aurora reader instances.

    Read replicas are safe for this workload specifically because replica lag
    causes a cache MISS and never a wrong answer - ADR-0007. They are NOT used
    for API key lookups or budget checks, where a stale read is a security or
    billing problem.
  EOT
  type        = number
  default     = 2
}

variable "aurora_backup_retention_days" {
  description = <<-EOT
    Backup retention.

    Seven days, not the one-day default. The cache tables are reproducible - a
    lost entry costs one upstream call - but tenants, api_keys, saga_instances
    and usage_ledger are not, and usage_ledger is what invoices are built from.
  EOT
  type        = number
  default     = 7
}

# -----------------------------------------------------------------------------
# ElastiCache - Tier 1, token buckets, key cache
# -----------------------------------------------------------------------------
variable "redis_node_type" {
  description = <<-EOT
    ElastiCache node type. Memory-optimised: Tier 1 is a pure memory play, and
    its size grows with distinct prompts rather than with users.
  EOT
  type        = string
  default     = "cache.r7g.large"
}

variable "redis_shard_count" {
  description = <<-EOT
    Redis Cluster shards.

    Cluster mode is on. Tier-1 keys are hash-tagged `{tenant:acme}:...`, which
    keeps one tenant's keys - cache entries and token bucket together - in one
    slot, so the atomic Lua rate limiter still works. Without the hash tag,
    cluster mode breaks that script with CROSSSLOT.
  EOT
  type        = number
  default     = 3
}

variable "redis_replicas_per_shard" {
  description = <<-EOT
    Replicas per shard.

    One. Redis is load-bearing for AUTHENTICATION here - the gateway makes no
    synchronous call to the control plane (ADR-0010) and reads keys from Redis -
    so a Redis failure is a data-plane failure, which is the one place in this
    design that cannot degrade gracefully.
  EOT
  type        = number
  default     = 1
}

# -----------------------------------------------------------------------------
# MSK - the event backbone
# -----------------------------------------------------------------------------
variable "msk_partitions_per_topic" {
  description = <<-EOT
    Partitions per gateway.*.v1 topic.

    Consumption is partition-ordered by design, so consumer replicas beyond the
    partition count sit IDLE. Repartitioning is the operation that has to happen
    before scaling consumers, and scaling the Deployment first looks like it
    should help and provably cannot.
  EOT
  type        = number
  default     = 24
}

variable "msk_retention_hours" {
  description = <<-EOT
    Topic retention.

    72 hours. Long enough to replay after a consumer outage over a weekend,
    short enough that the usage topic - one record per request, at ~2e9 requests
    a day - does not become the most expensive thing in the account.
  EOT
  type        = number
  default     = 72
}

variable "log_retention_days" {
  description = <<-EOT
    CloudWatch Logs retention.

    90 days, not the 365 checkov asks for. The EKS audit log is the expensive
    one - it records every API call in the cluster - and a year of it at this
    scale costs more than the control plane. 90 days covers the window in which
    an incident is actually investigated.

    Raise it where a compliance regime requires a year; the setting is a
    variable precisely so that is a values change rather than a code change.
    The checkov suppression for CKV_AWS_338 records this trade.
  EOT
  type        = number
  default     = 90

  validation {
    condition     = var.log_retention_days >= 30
    error_message = "Below 30 days the audit log is gone before most incidents are investigated."
  }
}
