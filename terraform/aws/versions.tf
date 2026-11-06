# =============================================================================
# AWS target for the AI gateway.
#
#   *** THIS IS NEVER APPLIED. ***
#
# It exists to be reviewed, validated, linted, security-scanned and costed. CI
# runs `init -backend=false`, `fmt -check`, `validate`, tflint, checkov and
# Infracost against it; nothing in this repository ever calls `apply`, and no
# AWS credentials are configured anywhere in the pipeline.
#
# That is a deliberate boundary, not a limitation. The stated constraint for the
# whole project is free and open-source only, and this stack costs roughly
# $3,500 a month IDLE - 3 gateway nodes, Aurora at minimum ACUs, nothing under
# load - rising to $25,000-30,000 at the modelled peak. The itemised breakdown
# is in README.md, and it is hand-computed rather than measured, which is why
# CI runs Infracost against this directory.
#
# Worth stating because it is the point: scaling-model.md derives a provider
# bill of ~$274M/year without the cache and ~$82M/year with it. At peak this
# entire stack is about 0.2% of the spend it avoids - the gateway is not
# competing with the LLM bill, it is rounding error against it.
#
# What it is FOR: the architecture claims it scales to billions of users
# (docs/architecture/scaling-model.md), and a claim like that should be
# accompanied by the infrastructure it would actually need, sized and priced,
# rather than left as an assertion. Writing it also surfaces the decisions a
# laptop never forces - subnet layout, IRSA, encryption, backup retention, which
# services are the single points of failure.
#
# Deliberately NO third-party modules. terraform-aws-modules/eks is excellent
# and would be the right choice for something being applied, but for something
# whose purpose is to be read, plain resources show the actual decisions instead
# of hiding them behind a module's defaults.
# =============================================================================

terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.0"
    }
    # For the EKS OIDC provider thumbprint. Computed from the issuer's
    # certificate rather than hardcoded: the well-known Amazon thumbprint
    # rotates, and a stale one makes every IRSA assume-role fail with an opaque
    # InvalidIdentityToken.
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # A local backend, for a configuration that is never applied. An S3 backend
  # with DynamoDB locking is what this would use in reality, and writing one
  # here would mean either a bucket that does not exist (so `init` fails in CI)
  # or a real bucket for state that is never written.
  #
  # The real thing would be:
  #
  #   backend "s3" {
  #     bucket       = "<org>-tfstate"
  #     key          = "ai-gateway/aws.tfstate"
  #     region       = "eu-west-1"
  #     encrypt      = true
  #     use_lockfile = true   # S3 native locking; DynamoDB is no longer needed
  #   }
}

provider "aws" {
  region = var.region

  # No credentials, no assume_role, no profile. `validate` does not contact AWS,
  # and a configuration that cannot authenticate cannot be applied by accident.

  default_tags {
    tags = {
      Project     = "ai-gateway"
      Environment = var.environment
      ManagedBy   = "terraform"
      # Named so a stray resource is traceable to this directory rather than to
      # "someone's experiment".
      Source = "github.com/VasanthvasanthM/multi-tenant-ai-gateway//terraform/aws"
    }
  }
}
