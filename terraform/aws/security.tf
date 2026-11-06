# =============================================================================
# Encryption keys and security groups.
#
# Security groups reference each OTHER rather than CIDR blocks. A CIDR rule
# survives the thing it was written for: delete a node group and the rule still
# permits whatever occupies those addresses next. A source-security-group rule
# cannot outlive its subject.
# =============================================================================

data "aws_caller_identity" "current" {}

# -----------------------------------------------------------------------------
# KMS
#
# Separate keys per concern, not one key for the account. A single key means one
# compromised role decrypts logs, database and secrets alike, and key rotation
# becomes an all-or-nothing operation.
# -----------------------------------------------------------------------------
resource "aws_kms_key" "logs" {
  description             = "${local.name} log encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountRoot"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        # CloudWatch Logs encrypts with the key itself, so it needs these
        # directly. Without this statement the log group creation fails with an
        # opaque InvalidParameterException.
        Sid       = "AllowCloudWatchLogs"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action = [
          "kms:Encrypt*",
          "kms:Decrypt*",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:Describe*",
        ]
        Resource = "*"
      },
    ]
  })

  tags = { Name = "${local.name}-logs" }
}

resource "aws_kms_alias" "logs" {
  name          = "alias/${local.name}-logs"
  target_key_id = aws_kms_key.logs.key_id
}

resource "aws_kms_key" "data" {
  description             = "${local.name} data-at-rest encryption (Aurora, ElastiCache, MSK)"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  # An explicit key policy, not the implicit default.
  #
  # A KMS key created without a policy gets a default that grants the account
  # root full access - which is permissive and, more importantly, invisible:
  # nothing in the configuration says who can use the key. Writing it out means
  # a reviewer can see the answer without consulting the console.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountRoot"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        # The AWS services that encrypt with this key on the account's behalf.
        #
        # kms:ViaService narrows the grant to requests that genuinely arrive
        # THROUGH one of those services. Without the condition, any principal
        # able to set a service principal in a request could use the key
        # directly - the service principal alone is not a boundary.
        Sid    = "AllowServiceUse"
        Effect = "Allow"
        Principal = { Service = [
          "rds.amazonaws.com",
          "elasticache.amazonaws.com",
          "kafka.amazonaws.com",
          "secretsmanager.amazonaws.com",
        ] }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:CreateGrant",
          "kms:DescribeKey",
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = [
              "rds.${var.region}.amazonaws.com",
              "elasticache.${var.region}.amazonaws.com",
              "kafka.${var.region}.amazonaws.com",
              "secretsmanager.${var.region}.amazonaws.com",
            ]
          }
        }
      },
    ]
  })

  tags = { Name = "${local.name}-data" }
}

resource "aws_kms_alias" "data" {
  name          = "alias/${local.name}-data"
  target_key_id = aws_kms_key.data.key_id
}

resource "aws_kms_key" "eks" {
  description             = "${local.name} EKS secret envelope encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  # An explicit key policy, not the implicit default.
  #
  # A KMS key created without a policy gets a default that grants the account
  # root full access - which is permissive and, more importantly, invisible:
  # nothing in the configuration says who can use the key. Writing it out means
  # a reviewer can see the answer without consulting the console.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowAccountRoot"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        # The AWS services that encrypt with this key on the account's behalf.
        #
        # kms:ViaService narrows the grant to requests that genuinely arrive
        # THROUGH one of those services. Without the condition, any principal
        # able to set a service principal in a request could use the key
        # directly - the service principal alone is not a boundary.
        Sid       = "AllowServiceUse"
        Effect    = "Allow"
        Principal = { Service = ["eks.amazonaws.com"] }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:CreateGrant",
          "kms:DescribeKey",
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = ["eks.${var.region}.amazonaws.com"]
          }
        }
      },
    ]
  })

  tags = { Name = "${local.name}-eks" }
}

resource "aws_kms_alias" "eks" {
  name          = "alias/${local.name}-eks"
  target_key_id = aws_kms_key.eks.key_id
}

# -----------------------------------------------------------------------------
# Security groups
# -----------------------------------------------------------------------------
resource "aws_security_group" "nodes" {
  name        = "${local.name}-nodes"
  description = "EKS worker nodes"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-nodes" }
}

resource "aws_vpc_security_group_egress_rule" "nodes_all" {
  security_group_id = aws_security_group.nodes.id
  description       = "Nodes need egress for image pulls and LLM provider calls"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "nodes_self" {
  security_group_id = aws_security_group.nodes.id
  description       = "Pod-to-pod traffic within the cluster"
  ip_protocol       = "-1"
  # Self-referencing, so this covers every node regardless of how the group is
  # scaled or replaced.
  referenced_security_group_id = aws_security_group.nodes.id
}

# --- Aurora ------------------------------------------------------------------
resource "aws_security_group" "aurora" {
  name        = "${local.name}-aurora"
  description = "Aurora PostgreSQL - Tier 2 vector store"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-aurora" }
}

resource "aws_vpc_security_group_ingress_rule" "aurora_from_nodes" {
  security_group_id            = aws_security_group.aurora.id
  description                  = "PostgreSQL from EKS nodes only"
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.nodes.id
}

# NO egress rule on the database groups.
#
# An AWS security group with no egress rules denies all egress. Combined with
# the data subnets having no NAT route, a compromised database host has two
# independent things stopping it reaching the internet.

# --- ElastiCache -------------------------------------------------------------
resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "ElastiCache Redis - Tier 1 cache, token buckets, API key cache"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-redis" }
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_nodes" {
  security_group_id            = aws_security_group.redis.id
  description                  = "Redis from EKS nodes only"
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.nodes.id
}

# --- MSK ---------------------------------------------------------------------
resource "aws_security_group" "msk" {
  name        = "${local.name}-msk"
  description = "MSK - gateway.*.v1 event topics"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-msk" }
}

resource "aws_vpc_security_group_ingress_rule" "msk_from_nodes_tls" {
  security_group_id            = aws_security_group.msk.id
  description                  = "Kafka TLS from EKS nodes"
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
  referenced_security_group_id = aws_security_group.nodes.id
}

# -----------------------------------------------------------------------------
# The VPC default security group.
#
# Every VPC gets one and it cannot be deleted. Its default rules allow all
# traffic between members, so anything launched without an explicit security
# group joins a permissive group by accident. Declaring it with NO rules makes
# that accident harmless.
#
# This resource does not create anything - it adopts the existing default group
# and removes its rules.
# -----------------------------------------------------------------------------
resource "aws_default_security_group" "main" {
  vpc_id = aws_vpc.main.id

  # No ingress, no egress. Deliberately empty blocks rather than omitted ones:
  # omitting them leaves the existing rules in place.

  tags = { Name = "${local.name}-default-DO-NOT-USE" }
}
