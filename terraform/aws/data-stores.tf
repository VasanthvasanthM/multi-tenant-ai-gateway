# =============================================================================
# Aurora PostgreSQL, ElastiCache Redis, MSK.
#
# All three sit in the data subnets, which have no route to a NAT gateway, and
# all three have security groups with no egress rules. See security.tf.
# =============================================================================

# -----------------------------------------------------------------------------
# Aurora PostgreSQL - the Tier-2 vector store
#
# Serverless v2 rather than provisioned. The access pattern is the reason: an
# HNSW index has to stay in memory to be fast, so the useful capacity metric is
# working-set size rather than steady QPS, and that grows with the tenant corpus
# in steps rather than smoothly. Provisioned instances would be sized for the
# peak and idle at it.
# -----------------------------------------------------------------------------
resource "aws_db_subnet_group" "aurora" {
  name       = "${local.name}-aurora"
  subnet_ids = aws_subnet.data[*].id

  tags = { Name = "${local.name}-aurora" }
}

resource "random_password" "aurora" {
  length = 32
  # Excluded rather than allowed: an unescaped '@' or '/' in a password breaks
  # a JDBC or R2DBC URL, and the failure is an authentication error that looks
  # like a wrong credential rather than a parsing problem.
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret" "aurora" {
  name                    = "${local.name}/aurora/master"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30

  tags = { Name = "${local.name}-aurora-master" }
}

resource "aws_secretsmanager_secret_version" "aurora" {
  secret_id = aws_secretsmanager_secret.aurora.id

  secret_string = jsonencode({
    username = "aigw"
    password = random_password.aurora.result
  })
}

resource "aws_rds_cluster_parameter_group" "aurora" {
  name   = "${local.name}-aurora"
  family = "aurora-postgresql16"

  # pgvector has to be in shared_preload_libraries for the HNSW index to be
  # usable; CREATE EXTENSION alone is not enough for the index type.
  parameter {
    name         = "shared_preload_libraries"
    value        = "vector,pg_stat_statements"
    apply_method = "pending-reboot"
  }

  # Every connection logged. At this scale that is noisy, but it is the only
  # way to answer "which service opened 900 connections" during an incident -
  # and the connection count is the limit sharding is meant to relieve.
  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  # DDL, not "all".
  #
  # log_statement = all logs every single statement, which at ~2e9 requests a
  # day would make the log bill exceed the database bill - the cache lookup
  # alone is one statement per request. "ddl" records schema changes, which is
  # the part nobody can reconstruct afterwards, and
  # log_min_duration_statement above already catches the slow queries that
  # matter operationally.
  parameter {
    name  = "log_statement"
    value = "ddl"
  }

  tags = { Name = "${local.name}-aurora" }
}

resource "aws_rds_cluster" "aurora" {
  cluster_identifier = "${local.name}-aurora"
  engine             = "aurora-postgresql"
  engine_mode        = "provisioned"
  engine_version     = var.aurora_engine_version
  database_name      = "aigw"

  master_username = "aigw"
  master_password = random_password.aurora.result

  db_subnet_group_name            = aws_db_subnet_group.aurora.name
  vpc_security_group_ids          = [aws_security_group.aurora.id]
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.aurora.name

  serverlessv2_scaling_configuration {
    min_capacity = var.aurora_min_capacity
    max_capacity = var.aurora_max_capacity
  }

  storage_encrypted = true
  kms_key_id        = aws_kms_key.data.arn

  backup_retention_period      = var.aurora_backup_retention_days
  preferred_backup_window      = "03:00-04:00"
  preferred_maintenance_window = "sun:04:30-sun:05:30"

  # Deletion protection ON, and a final snapshot taken. The cache tables are
  # reproducible; usage_ledger is what invoices are built from and tenants and
  # api_keys are not recoverable from anywhere else.
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name}-aurora-final"

  # postgresql only. "upgrade" logs exist but are almost entirely noise, and
  # every enabled export is a CloudWatch Logs bill.
  enabled_cloudwatch_logs_exports = ["postgresql"]

  # Tags follow the snapshot. Without this a restored cluster carries none of
  # the Project/Environment tags, so it is invisible to cost allocation and to
  # any policy that selects on them - which is exactly when someone is
  # restoring under pressure and not checking.
  copy_tags_to_snapshot = true

  # IAM database authentication, so the services can use short-lived tokens
  # instead of the master password. The master credential then exists only for
  # schema migrations.
  iam_database_authentication_enabled = true

  tags = { Name = "${local.name}-aurora" }
}

resource "aws_rds_cluster_instance" "writer" {
  identifier         = "${local.name}-aurora-writer"
  cluster_identifier = aws_rds_cluster.aurora.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.aurora.engine
  engine_version     = aws_rds_cluster.aurora.engine_version

  # Promotion tier 0: this instance is preferred as the failover target, so a
  # failover lands on a known instance rather than whichever reader wins.
  promotion_tier = 0

  # Minor versions applied automatically, inside the maintenance window. Minor
  # Postgres releases are overwhelmingly security and correctness fixes;
  # deferring them means running known-vulnerable versions to avoid a restart
  # that Aurora performs with a failover anyway.
  auto_minor_version_upgrade = true

  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.data.arn
  monitoring_interval             = 30
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn

  tags = { Name = "${local.name}-aurora-writer" }
}

resource "aws_rds_cluster_instance" "reader" {
  count = var.aurora_replica_count

  identifier         = "${local.name}-aurora-reader-${count.index}"
  cluster_identifier = aws_rds_cluster.aurora.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.aurora.engine
  engine_version     = aws_rds_cluster.aurora.engine_version

  # Tier 1+, so a reader is never preferred over the writer during failover.
  promotion_tier = 1

  auto_minor_version_upgrade = true

  performance_insights_enabled    = true
  performance_insights_kms_key_id = aws_kms_key.data.arn
  monitoring_interval             = 30
  monitoring_role_arn             = aws_iam_role.rds_monitoring.arn

  tags = { Name = "${local.name}-aurora-reader-${count.index}" }
}

resource "aws_iam_role" "rds_monitoring" {
  name = "${local.name}-rds-monitoring"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "monitoring.rds.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# -----------------------------------------------------------------------------
# ElastiCache Redis - Tier 1, token buckets, API key cache
#
# Cluster mode ON. Tier-1 keys are hash-tagged `{tenant:acme}:...`, which keeps
# a tenant's cache entries and its token bucket in the same slot - without that
# tag the atomic Lua rate limiter fails with CROSSSLOT, and the failure only
# appears under cluster mode, never in local development against a single node.
# -----------------------------------------------------------------------------
resource "aws_elasticache_subnet_group" "redis" {
  name       = "${local.name}-redis"
  subnet_ids = aws_subnet.data[*].id

  tags = { Name = "${local.name}-redis" }
}

resource "aws_elasticache_parameter_group" "redis" {
  name   = "${local.name}-redis"
  family = "redis7"

  # allkeys-lru, not noeviction.
  #
  # Tier 1 is a cache: when it fills, evicting the least recently used entry is
  # correct and the cost is a lower hit rate. With noeviction, a full Redis
  # starts REFUSING WRITES - and since the same Redis holds the token buckets
  # and the API key cache, a full cache would break rate limiting and
  # authentication rather than just caching.
  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }

  tags = { Name = "${local.name}-redis" }
}

resource "random_password" "redis_auth" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "redis_auth" {
  name                    = "${local.name}/redis/auth-token"
  kms_key_id              = aws_kms_key.data.arn
  recovery_window_in_days = 30

  tags = { Name = "${local.name}-redis-auth" }
}

resource "aws_secretsmanager_secret_version" "redis_auth" {
  secret_id     = aws_secretsmanager_secret.redis_auth.id
  secret_string = random_password.redis_auth.result
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id = "${local.name}-redis"
  description          = "Tier 1 cache, token buckets and API key cache"

  engine         = "redis"
  engine_version = "7.1"
  node_type      = var.redis_node_type
  port           = 6379

  parameter_group_name = aws_elasticache_parameter_group.redis.name
  subnet_group_name    = aws_elasticache_subnet_group.redis.name
  security_group_ids   = [aws_security_group.redis.id]

  num_node_groups         = var.redis_shard_count
  replicas_per_node_group = var.redis_replicas_per_shard

  automatic_failover_enabled = true
  multi_az_enabled           = true

  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.data.arn
  transit_encryption_enabled = true
  auth_token                 = random_password.redis_auth.result

  # Snapshots are kept even though Tier 1 is reproducible. The token buckets are
  # not the reason either - they refill. It is the API key cache: losing it means
  # every request fails authentication until the control plane republishes, and
  # a snapshot turns that from an outage into a restore.
  snapshot_retention_limit = 3
  snapshot_window          = "02:00-03:00"
  maintenance_window       = "sun:05:00-sun:06:00"

  apply_immediately = false

  tags = { Name = "${local.name}-redis" }
}

# -----------------------------------------------------------------------------
# MSK Serverless - the event backbone
#
# Serverless rather than provisioned, and the reason is the traffic shape rather
# than cost: event volume tracks request volume exactly, which is diurnal with a
# 4x peak (scaling-model.md). A provisioned cluster has to be sized for the peak
# and sits idle at it for most of the day.
#
# The trade is that Serverless only supports IAM authentication - which is what
# the gateway should use anyway, since it removes a stored credential.
# -----------------------------------------------------------------------------
resource "aws_msk_serverless_cluster" "events" {
  cluster_name = "${local.name}-events"

  vpc_config {
    subnet_ids         = aws_subnet.data[*].id
    security_group_ids = [aws_security_group.msk.id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }

  tags = { Name = "${local.name}-events" }
}

# -----------------------------------------------------------------------------
# IRSA role for the gateway pods.
#
# Scoped to one namespace and one service account. A role trusted by the whole
# cluster would let any pod assume it, which defeats the point of IRSA.
# -----------------------------------------------------------------------------
locals {
  # The issuer without the scheme, which is the form an IAM condition key takes.
  # Written once here because the condition below needs it twice and HCL
  # requires each key = value on a single line.
  oidc_host = replace(aws_iam_openid_connect_provider.eks.url, "https://", "")
}

resource "aws_iam_role" "gateway_irsa" {
  name = "${local.name}-gateway-irsa"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.eks.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:sub" = "system:serviceaccount:default:aigw-ai-gateway"
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Name = "${local.name}-gateway-irsa" }
}

resource "aws_iam_role_policy" "gateway_irsa" {
  name = "${local.name}-gateway"
  role = aws_iam_role.gateway_irsa.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Read its own secrets only. A wildcard here would let the gateway read
        # every secret in the account, including ones belonging to other
        # systems.
        Sid    = "ReadOwnSecrets"
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = [
          aws_secretsmanager_secret.aurora.arn,
          aws_secretsmanager_secret.redis_auth.arn,
        ]
      },
      {
        Sid      = "DecryptWithDataKey"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [aws_kms_key.data.arn]
      },
      {
        # Produce only, and only to the gateway topics. The gateway must not be
        # able to consume: that is the consumers' job, and a producer that can
        # also commit offsets can silently skip events.
        Sid    = "ProduceToGatewayTopics"
        Effect = "Allow"
        Action = [
          "kafka-cluster:Connect",
          "kafka-cluster:WriteData",
          "kafka-cluster:DescribeTopic",
        ]
        Resource = [
          aws_msk_serverless_cluster.events.arn,
          "arn:aws:kafka:${var.region}:${data.aws_caller_identity.current.account_id}:topic/${local.name}-events/*/gateway.*",
        ]
      },
      {
        Sid      = "ConnectToAuroraWithIam"
        Effect   = "Allow"
        Action   = ["rds-db:connect"]
        Resource = ["arn:aws:rds-db:${var.region}:${data.aws_caller_identity.current.account_id}:dbuser:${aws_rds_cluster.aurora.cluster_resource_id}/aigw"]
      },
    ]
  })
}
