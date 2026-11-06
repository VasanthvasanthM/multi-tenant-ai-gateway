# =============================================================================
# Network.
#
# Three tiers of subnet, and the split is load-bearing rather than conventional:
#
#   public   - only the load balancers. Nothing else is ever placed here.
#   private  - EKS nodes. Egress through NAT, no inbound from the internet.
#   data     - Aurora, ElastiCache, MSK. NO route to a NAT gateway at all.
#
# The data tier having no egress route is the point. A compromised database host
# cannot reach the internet to exfiltrate anything or pull a second stage, and
# that is enforced by the absence of a route rather than by a security group
# somebody can widen.
# =============================================================================

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, var.availability_zone_count)

  # /20 per subnet from a /16: 4,094 usable addresses each.
  #
  # Sized for the EKS pod-per-IP model (see the vpc_cidr variable). The data
  # subnets are deliberately the same size even though they hold a handful of
  # endpoints - carving them smaller saves nothing and makes a later resize a
  # migration.
  public_subnets  = [for i in range(var.availability_zone_count) : cidrsubnet(var.vpc_cidr, 4, i)]
  private_subnets = [for i in range(var.availability_zone_count) : cidrsubnet(var.vpc_cidr, 4, i + 4)]
  data_subnets    = [for i in range(var.availability_zone_count) : cidrsubnet(var.vpc_cidr, 4, i + 8)]

  name = "${var.name_prefix}-${var.environment}"
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

# -----------------------------------------------------------------------------
# Flow logs.
#
# To CloudWatch rather than S3, retained for var.log_retention_days. Not
# optional in any environment handling other people's prompts: without them,
# "did anything reach the database from outside the data subnets" is
# unanswerable after the fact.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/aws/vpc/${local.name}/flow-logs"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.logs.arn
}

resource "aws_flow_log" "main" {
  vpc_id          = aws_vpc.main.id
  traffic_type    = "ALL"
  iam_role_arn    = aws_iam_role.flow_logs.arn
  log_destination = aws_cloudwatch_log_group.flow_logs.arn
}

resource "aws_iam_role" "flow_logs" {
  name = "${local.name}-flow-logs"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "flow_logs" {
  name = "${local.name}-flow-logs"
  role = aws_iam_role.flow_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogGroups",
        "logs:DescribeLogStreams",
      ]
      Resource = "${aws_cloudwatch_log_group.flow_logs.arn}:*"
    }]
  })
}

# -----------------------------------------------------------------------------
# Subnets
# -----------------------------------------------------------------------------
resource "aws_subnet" "public" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  cidr_block        = local.public_subnets[count.index]
  availability_zone = local.azs[count.index]

  # Public IPs are NOT auto-assigned. Only the load balancers live here and ELB
  # manages its own addresses; auto-assign would silently give a public IP to
  # anything mistakenly placed in this tier.
  map_public_ip_on_launch = false

  tags = {
    Name                     = "${local.name}-public-${local.azs[count.index]}"
    "kubernetes.io/role/elb" = "1"
    # The cluster tag is what lets the AWS Load Balancer Controller discover
    # these subnets. Without it, an Ingress gets no load balancer and the only
    # symptom is an Ingress with no ADDRESS, indefinitely.
    "kubernetes.io/cluster/${local.name}" = "shared"
  }
}

resource "aws_subnet" "private" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  cidr_block        = local.private_subnets[count.index]
  availability_zone = local.azs[count.index]

  tags = {
    Name                                  = "${local.name}-private-${local.azs[count.index]}"
    "kubernetes.io/role/internal-elb"     = "1"
    "kubernetes.io/cluster/${local.name}" = "shared"
  }
}

resource "aws_subnet" "data" {
  count = var.availability_zone_count

  vpc_id            = aws_vpc.main.id
  cidr_block        = local.data_subnets[count.index]
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.name}-data-${local.azs[count.index]}" }
}

# -----------------------------------------------------------------------------
# Routing
# -----------------------------------------------------------------------------
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = local.name }
}

# One NAT gateway PER AZ, not one shared.
#
# A single NAT is cheaper by roughly $65/month per avoided zone, and it makes
# that zone a single point of failure for all egress - so an AZ outage takes
# out every node's ability to pull an image or reach a provider, in every zone.
# Cross-AZ NAT data transfer also costs $0.01/GB, which at real volume exceeds
# the saving.
resource "aws_eip" "nat" {
  count = var.availability_zone_count

  domain = "vpc"

  tags = { Name = "${local.name}-nat-${local.azs[count.index]}" }
}

resource "aws_nat_gateway" "main" {
  count = var.availability_zone_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = { Name = "${local.name}-nat-${local.azs[count.index]}" }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  count = var.availability_zone_count

  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index].id
  }

  tags = { Name = "${local.name}-private-${local.azs[count.index]}" }
}

resource "aws_route_table_association" "private" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# The data route table has NO default route.
#
# Local VPC routing only. This is the security boundary described at the top of
# the file: Aurora, ElastiCache and MSK cannot reach the internet at all, and
# that is enforced by a missing route rather than by a rule someone can relax.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-data" }
}

resource "aws_route_table_association" "data" {
  count = var.availability_zone_count

  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# -----------------------------------------------------------------------------
# VPC endpoints.
#
# S3 as a gateway endpoint because it is free and ECR image layers come from S3
# - every image pull would otherwise traverse NAT at $0.045/GB, which on a
# 300 MB image across 20 nodes is real money on every rollout.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"

  route_table_ids = concat(
    aws_route_table.private[*].id,
    [aws_route_table.data.id],
  )

  tags = { Name = "${local.name}-s3" }
}
