# =============================================================================
# tflint configuration.
#
# tflint catches what `terraform validate` cannot. validate checks SCHEMA - that
# an argument exists and has the right type. tflint checks VALUES against the
# provider's own data: a misspelled instance type, a deprecated argument, an
# invalid engine version string. Those are exactly the mistakes that survive
# validate and fail at apply, which for terraform/aws means they would never be
# caught at all, because it is never applied.
# =============================================================================

plugin "terraform" {
  enabled = true
  preset  = "recommended"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"

  # deep_check is OFF. It calls the AWS API to verify that referenced resources
  # exist, which needs credentials this pipeline deliberately does not have -
  # see terraform/aws/README.md.
  deep_check = false
}

config {
  call_module_type = "local"
}

# -----------------------------------------------------------------------------
# Rule adjustments, each with a reason.
# -----------------------------------------------------------------------------

# Every variable here has a description and a type. Enforced rather than
# assumed, because a variable without a description is one somebody has to read
# the implementation to understand.
rule "terraform_documented_variables" {
  enabled = true
}

rule "terraform_documented_outputs" {
  enabled = true
}

rule "terraform_typed_variables" {
  enabled = true
}

# Naming convention: snake_case for everything.
rule "terraform_naming_convention" {
  enabled = true
  format  = "snake_case"
}

# ON. Both directories pin every provider in versions.tf, and the rule keeps a
# new provider from being introduced without a constraint - which is how a
# `terraform init` months later picks up a major version nobody reviewed.
rule "terraform_required_providers" {
  enabled = true
}

rule "terraform_required_version" {
  enabled = true
}

# ON, and it already earned its place: msk_partitions_per_topic and
# msk_retention_hours were declared and unused, because MSK Serverless has no
# Terraform resource for topics. Rather than deleting them they now feed an
# output that emits the operator commands - so the configuration documents the
# required partition count instead of silently dropping it.
rule "terraform_unused_declarations" {
  enabled = true
}
