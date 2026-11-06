# =============================================================================
# EKS.
#
# Two node groups, because the workloads have genuinely different shapes:
#
#   gateway    - CPU-heavy on the Tier-2 path (ONNX inference), scales with
#                request rate, must tolerate being replaced at any time
#   platform   - the consumers and control plane, which scale with event volume
#                and barely scale at all respectively
#
# One mixed group would force one instance type on both and make the cluster
# autoscaler's decisions meaningless: it cannot tell whether a pending pod needs
# compute or memory when every node is the same compromise.
# =============================================================================

resource "aws_iam_role" "cluster" {
  name = "${local.name}-cluster"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster_policy" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_cloudwatch_log_group" "eks" {
  name              = "/aws/eks/${local.name}/cluster"
  retention_in_days = var.log_retention_days
  kms_key_id        = aws_kms_key.logs.arn
}

resource "aws_eks_cluster" "main" {
  name     = local.name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

  # API_AND_CONFIG_MAP, not the older CONFIG_MAP. Access entries are the
  # supported mechanism now; aws-auth ConfigMap editing is the way a cluster
  # gets locked out, because a malformed edit is only discovered when the next
  # person tries to authenticate.
  access_config {
    authentication_mode                         = "API_AND_CONFIG_MAP"
    bootstrap_cluster_creator_admin_permissions = true
  }

  vpc_config {
    # Nodes in private subnets; public subnets are reachable so the load
    # balancer controller can place internet-facing load balancers.
    subnet_ids = concat(aws_subnet.private[*].id, aws_subnet.public[*].id)

    # The public endpoint stays ON but CIDR-restricted in a real deployment.
    # Fully private means every CI runner and operator needs VPN or a bastion,
    # and the usual consequence is a long-lived bastion host that is itself the
    # weakest thing in the account.
    endpoint_private_access = true
    endpoint_public_access  = true
    public_access_cidrs     = ["0.0.0.0/0"]

    security_group_ids = [aws_security_group.nodes.id]
  }

  # Envelope encryption for Kubernetes Secrets. Without it, Secrets are stored
  # in etcd encrypted only by the AWS-managed key, and "our Secrets are
  # encrypted" is then true in a way that does not survive a question about
  # which key.
  encryption_config {
    provider {
      key_arn = aws_kms_key.eks.arn
    }
    resources = ["secrets"]
  }

  # All five log types. The audit log is the one that matters - it is the only
  # record of who changed what in the cluster, and it cannot be enabled
  # retroactively for an incident that already happened.
  enabled_cluster_log_types = [
    "api",
    "audit",
    "authenticator",
    "controllerManager",
    "scheduler",
  ]

  depends_on = [
    aws_iam_role_policy_attachment.cluster_policy,
    aws_cloudwatch_log_group.eks,
  ]

  tags = { Name = local.name }
}

# -----------------------------------------------------------------------------
# IRSA - IAM Roles for Service Accounts
#
# A pod gets its own IAM role rather than inheriting the node's. Without this,
# every pod on a node shares the node's permissions, so the gateway would hold
# whatever the metering consumer needs and vice versa - and a compromise of the
# least-protected pod yields the union of all of them.
# -----------------------------------------------------------------------------
data "tls_certificate" "oidc" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]
}

# -----------------------------------------------------------------------------
# Node groups
# -----------------------------------------------------------------------------
resource "aws_iam_role" "node" {
  name = "${local.name}-node"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    # SSM rather than SSH. No key pairs, no port 22 anywhere, and session
    # access is logged in CloudTrail - which SSH to a node is not.
    "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ])

  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "gateway" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "gateway"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = aws_subnet.private[*].id
  instance_types  = [var.gateway_node_instance_type]

  scaling_config {
    desired_size = var.gateway_node_min_size
    min_size     = var.gateway_node_min_size
    max_size     = var.gateway_node_max_size
  }

  # One at a time. The gateway's startup probe allows ~16 s for the JVM plus
  # the ONNX model load, and the chart sets maxUnavailable: 0 - replacing
  # several nodes at once would drop capacity faster than new pods become
  # ready, during exactly the window when a deploy is happening.
  update_config {
    max_unavailable = 1
  }

  labels = {
    workload = "gateway"
  }

  lifecycle {
    # The cluster autoscaler owns desired_size after creation. Without this,
    # every `terraform apply` scales the group back to the configured desired
    # size - so a routine apply during a traffic spike drops capacity.
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]

  tags = { Name = "${local.name}-gateway" }
}

resource "aws_eks_node_group" "platform" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "platform"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = aws_subnet.private[*].id

  # Memory-biased: the consumers batch database writes, and the indexer holds
  # the ONNX model as well.
  instance_types = ["m7i.xlarge"]

  scaling_config {
    desired_size = 2
    min_size     = 2
    max_size     = 6
  }

  update_config {
    max_unavailable = 1
  }

  labels = {
    workload = "platform"
  }

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]

  tags = { Name = "${local.name}-platform" }
}

# -----------------------------------------------------------------------------
# Addons.
#
# Versions are resolved by EKS rather than pinned here. Pinning an addon version
# in Terraform means it drifts behind the cluster version on every upgrade and
# the mismatch surfaces as a CNI that will not start.
# -----------------------------------------------------------------------------
resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "vpc-cni"
  resolve_conflicts_on_update = "PRESERVE"
}

resource "aws_eks_addon" "coredns" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "coredns"
  resolve_conflicts_on_update = "PRESERVE"

  depends_on = [aws_eks_node_group.platform]
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "kube-proxy"
  resolve_conflicts_on_update = "PRESERVE"
}

resource "aws_eks_addon" "pod_identity" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "eks-pod-identity-agent"
  resolve_conflicts_on_update = "PRESERVE"
}
