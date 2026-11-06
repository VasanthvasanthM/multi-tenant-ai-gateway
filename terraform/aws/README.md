# AWS target

> ## This is never applied.
>
> There are no AWS credentials anywhere in this repository or its CI pipeline,
> and nothing calls `terraform apply`. CI runs `init -backend=false`,
> `fmt -check`, `validate`, tflint, checkov and Infracost against this
> directory — static checks only.

## Why it exists

The architecture claims it scales to billions of users
([scaling-model.md](../../docs/architecture/scaling-model.md)). A claim like
that should come with the infrastructure it would actually need — sized,
priced, and security-reviewed — rather than being left as an assertion.

Writing it also forces decisions a laptop never does: subnet layout, which
services have no egress at all, IRSA scoping, backup retention, and which
component is the single point of failure. Several of those decisions are
recorded as comments in the `.tf` files because the reason is more useful than
the setting.

## What it describes

| Component | Choice | Why |
|---|---|---|
| **EKS** | 1.33, two node groups | `gateway` on `c7i` (ONNX inference is the one CPU-bound part of a request); `platform` on `m7i` for the consumers |
| **Aurora PostgreSQL** | Serverless v2, 2–64 ACU, 1 writer + 2 readers | The capacity metric is HNSW working-set size, not QPS, and it grows in steps — provisioned would be sized for the peak and idle at it |
| **ElastiCache Redis** | Cluster mode, 3 shards × 1 replica | Tier 1, token buckets and the API key cache. Hash-tagged keys keep a tenant's bucket and entries in one slot, which the atomic Lua limiter requires |
| **MSK Serverless** | SASL/IAM | Event volume tracks request volume exactly, which is diurnal with a 4× peak |
| **Network** | 3 AZs, public / private / **data** tiers | The data tier has **no NAT route at all**, so a compromised database host cannot reach the internet |

## Approximate cost

**Unverified.** These are hand-computed from published eu-west-1 on-demand
prices and have **not** been checked against Infracost or a real bill. CI runs
Infracost precisely because a hand-computed figure like this drifts.

At **minimum** capacity — 3 gateway nodes, Aurora at 2 ACU, nothing under load:

| Item | Monthly |
|---|---|
| EKS control plane | $73 |
| 3 × `c7i.2xlarge` (gateway) | ~$834 |
| 2 × `m7i.xlarge` (platform) | ~$294 |
| 3 × NAT gateway (one per AZ) | ~$105 + data |
| Aurora Serverless v2, 3 × 2 ACU | ~$526 |
| ElastiCache, 6 × `cache.r7g.large` | ~$904 |
| MSK Serverless (cluster + 120 partitions) | ~$679 |
| KMS (3 keys), Secrets Manager (2) | ~$4 |
| CloudWatch Logs | ~$50 |
| **Total, idle** | **~$3,500 / month** |

At the modelled peak — 20 gateway nodes and Aurora scaling toward 64 ACU — it
is roughly **$25,000–30,000 / month**, dominated by Aurora ACUs and node hours.

### The number that makes those numbers interesting

[scaling-model.md §4](../../docs/architecture/scaling-model.md) derives a
provider bill of **~$274M/year without the cache and ~$82M/year at a 70% hit
rate** — a saving of roughly **$192M/year**.

So at peak this entire stack costs about **0.2% of the provider spend it
avoids**. That is the actual economic argument for the system, and it only
becomes visible once the infrastructure is priced: the gateway is not
competing with the LLM bill, it is rounding error against it.

It also reframes where effort belongs. A 1% improvement in hit rate is worth
~$2.7M/year, which is more than the entire annual infrastructure cost at
minimum capacity. Tuning the cache beats tuning the cluster by three orders of
magnitude.

## Running the checks

From the repository root:

```bash
terraform -chdir=terraform/aws init -backend=false
terraform -chdir=terraform/aws fmt -check -recursive
terraform -chdir=terraform/aws validate

# --config is NOT optional. .tflint.hcl lives at the repository root, and
# --chdir makes tflint look for it in the target directory instead - so without
# an explicit path it runs with no aws ruleset loaded, checks nothing, and
# passes. Confirm the ruleset is loaded with `tflint --config=... --version`.
tflint --chdir=terraform/aws --config="$PWD/.tflint.hcl" --init
tflint --chdir=terraform/aws --config="$PWD/.tflint.hcl"

checkov --directory terraform --framework terraform --config-file .checkov.yaml

infracost breakdown --path terraform/aws
```

Current state: `validate` clean, tflint clean with the aws ruleset loaded, and
checkov **176 passed / 0 failed** with five suppressions, each carrying a
written reason in [`.checkov.yaml`](../../.checkov.yaml).

`validate` does **not** contact AWS, so none of this needs credentials. It also
means `validate` cannot check:

- that `kubernetes_version` and `aurora_engine_version` exist;
- that the instance types are available in the region;
- service quotas, which at these sizes would need raising.

Each of those is noted at the variable that declares it.

## Known gaps

These are real, not suppressed false positives. They are listed because
checkov flags them and the suppression in `.checkov.yaml` points here.

- **Secrets Manager rotation is not configured** (`CKV2_AWS_57`). Rotation
  needs a Lambda per secret type, and the Aurora master credential also needs
  the two-user rotation strategy so in-flight connections are not locked out
  mid-rotation. Until it exists, the master credential is long-lived — which is
  mitigated but not solved by `iam_database_authentication_enabled`, since the
  services can use short-lived IAM tokens and the master password is then only
  used for schema migrations.
- **No AWS Backup plan** (`CKV2_AWS_8`). Aurora's own automated backups are
  configured — 7 days, a defined window, deletion protection, a final snapshot
  — but there are no cross-account or cross-region copies. That is a
  disaster-recovery decision this configuration does not make.
- **Log retention is 90 days, not a year** (`CKV_AWS_338`). The EKS audit log
  records every API call in the cluster; a year of it at this scale costs more
  than the control plane. `var.log_retention_days` is the knob.

## What is deliberately missing

- **A remote backend.** An S3 backend would need a real bucket for state that
  is never written. The shape it would take is in a comment in
  [`versions.tf`](versions.tf).
- **Third-party modules.** `terraform-aws-modules/eks` would be the right
  choice for something being applied; for something whose purpose is to be
  read, plain resources show the decisions instead of hiding them behind a
  module's defaults.
- **Multi-region.** Tier 1 and Tier 2 are per-region caches, and a
  cross-region cache has to answer what happens when the same prompt is cached
  in two regions with different content. That is a design question, not a
  Terraform one, and it is not answered yet.
- **The Kubernetes workloads.** The Helm releases live in
  [`../local`](../local) against k3d. Pointing them at EKS is a provider
  change, not a rewrite.
