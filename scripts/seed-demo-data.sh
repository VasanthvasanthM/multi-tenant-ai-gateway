#!/usr/bin/env bash
# Recreate and seed tenant_usage_rollup for a dashboard demo.
#
# Why this exists: the dev Postgres runs on an emptyDir, so a pod restart wipes
# everything. And tenant_usage_rollup belongs to the *metering* service, which a
# gateway-only (values-dev.yaml) deployment does not install -- so the table is
# absent, not merely empty, and the dashboard API still answers 200 with zeros.
#
# The DDL below is copied verbatim from
# metering/src/main/resources/db/migration/V1__metering.sql so the demo schema
# cannot drift from the real one.
#
# Usage:  ./scripts/seed-demo-data.sh [tenant] [hours]
#         ./scripts/seed-demo-data.sh --clean [tenant]
set -euo pipefail

if [[ "${1:-}" == "--clean" ]]; then
  TENANT="${2:-acme}"
  kubectl exec -i deploy/postgres -- psql -U aigw -d aigw -v ON_ERROR_STOP=1 \
    -c "DELETE FROM tenant_usage_rollup WHERE tenant_id = '${TENANT}';"
  echo "removed seeded rows for tenant '${TENANT}'"
  exit 0
fi

TENANT="${1:-acme}"
HOURS="${2:-48}"

# Piped as a file, not -c: multi-line SQL through kubectl exec -c gets mangled
# by the shell (|| , newlines, quotes).
kubectl exec -i deploy/postgres -- psql -U aigw -d aigw -v ON_ERROR_STOP=1 <<SQL
CREATE TABLE IF NOT EXISTS tenant_usage_rollup (
    tenant_id         TEXT        NOT NULL,
    period_hour       TIMESTAMPTZ NOT NULL,
    request_count     BIGINT      NOT NULL DEFAULT 0,
    cache_hit_count   BIGINT      NOT NULL DEFAULT 0,
    prompt_tokens     BIGINT      NOT NULL DEFAULT 0,
    completion_tokens BIGINT      NOT NULL DEFAULT 0,
    upstream_tokens   BIGINT      NOT NULL DEFAULT 0,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, period_hour)
);

-- Hits are DERIVED from requests, never drawn independently. Generating both
-- with separate random() calls produced more hits than requests and a 103.7%
-- hit ratio on the dashboard.
INSERT INTO tenant_usage_rollup
  (tenant_id, period_hour, request_count, cache_hit_count,
   prompt_tokens, completion_tokens, upstream_tokens)
SELECT '${TENANT}', period_hour, req, hits,
       (req * 108)::bigint,
       (req * 65)::bigint,
       ((req - hits) * 173)::bigint
  FROM (
    SELECT date_trunc('hour', now()) - make_interval(hours => g) AS period_hour,
           (420 + random() * 260)::bigint AS req
      FROM generate_series(0, ${HOURS} - 1) AS g
  ) base,
  LATERAL (
    SELECT (base.req * (0.58 + random() * 0.22))::bigint AS hits
  ) h
ON CONFLICT (tenant_id, period_hour) DO NOTHING;

SELECT count(*)                                                      AS hours,
       sum(request_count)                                            AS requests,
       round(sum(cache_hit_count)::numeric
             / nullif(sum(request_count), 0) * 100, 1)               AS hit_pct
  FROM tenant_usage_rollup WHERE tenant_id = '${TENANT}';
SQL
SQL_EXIT=$?

# A hit ratio above 100% means the derivation broke; fail loudly rather than
# letting an impossible number reach a screen recording.
BAD=$(kubectl exec -i deploy/postgres -- psql -U aigw -d aigw -t -A -c \
  "SELECT count(*) FROM tenant_usage_rollup WHERE cache_hit_count > request_count;" | tr -d '[:space:]')
if [[ "${BAD}" != "0" ]]; then
  echo "FAIL: ${BAD} rows have more cache hits than requests" >&2
  exit 1
fi
echo "seeded tenant '${TENANT}' over ${HOURS}h; no row exceeds 100% hit ratio"
exit ${SQL_EXIT}
