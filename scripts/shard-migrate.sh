#!/usr/bin/env bash
#
# Move one tenant's data from one shard to another, and prove it arrived.
#
# Re-sharding is the operation most likely to lose data quietly, because the
# failure is invisible: a tenant whose rows were left behind reads as a customer
# with no history, not as an error. So every step here is separate, and the
# cutover is gated on a verification that has to pass first.
#
# THE ORDER MATTERS, and each step is deliberately its own command:
#
#   1. copy     source -> target, idempotently. The source keeps serving.
#   2. verify   row counts AND content checksums must match. Read-only.
#   3. cutover  change the ring config and reload. Done outside this script.
#   4. cleanup  delete from the source, only once you trust the cutover.
#
# Nothing here deletes anything until `cleanup` is run explicitly, so an
# abandoned migration costs disk and never data.
#
# WHAT THIS DOES NOT DO, and why:
#
#   - It does not compute WHICH tenants move. ShardMigrationPlan does that, and
#     it must be run first - see docs/runbooks/shard-migration.md.
#   - It does not copy `prompt_cache`. That is a cache: dropping it costs one
#     day of extra provider calls as it refills, and copying several KiB of
#     vector per row to save that is the wrong trade. Pass --include-cache to
#     override.
#   - It does not handle writes arriving DURING the copy. See the runbook: that
#     needs dual-write or a brief per-tenant pause, and this script is the data
#     movement half, not the traffic half.
#
# Usage:
#   ./scripts/shard-migrate.sh plan-check   --tenants-file tenants.txt
#   ./scripts/shard-migrate.sh copy    --tenant acme --from "$FROM" --to "$TO"
#   ./scripts/shard-migrate.sh verify  --tenant acme --from "$FROM" --to "$TO"
#   ./scripts/shard-migrate.sh cleanup --tenant acme --from "$FROM" --confirm
#
# And if verify fails, because a retry cannot repair an already-present row:
#   ./scripts/shard-migrate.sh reset-target --tenant acme --to "$TO" --confirm
#
# FROM and TO are psql connection strings, e.g.
#   postgresql://aigw:pw@localhost:5432/shard_a
#
set -euo pipefail

# Durable, per-tenant data. Deliberately NOT prompt_cache - see the header.
TABLES=(usage_ledger audit_log)
CACHE_TABLES=(prompt_cache)

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
info() { printf '%s==>%s %s\n' "$DIM" "$OFF" "$*"; }
ok()   { printf '%s ok %s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%swarn%s %s\n' "$YELLOW" "$OFF" "$*"; }
die()  { printf '%sFAIL%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

COMMAND="${1:-}"; shift || true
TENANT=""; FROM=""; TO=""; TENANTS_FILE=""; CONFIRM=0; INCLUDE_CACHE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --tenant)        TENANT="${2:-}"; shift 2 ;;
    --from)          FROM="${2:-}"; shift 2 ;;
    --to)            TO="${2:-}"; shift 2 ;;
    --tenants-file)  TENANTS_FILE="${2:-}"; shift 2 ;;
    --confirm)       CONFIRM=1; shift ;;
    --include-cache) INCLUDE_CACHE=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

if [ "$INCLUDE_CACHE" = 1 ]; then
  TABLES+=("${CACHE_TABLES[@]}")
fi

command -v psql >/dev/null 2>&1 || die "psql is not on PATH"

# -X ignores ~/.psqlrc, which can set formatting that breaks -t -A parsing.
# ON_ERROR_STOP turns a mid-script SQL error into a non-zero exit instead of a
# partially applied migration that reports success.
pg() { psql -X -v ON_ERROR_STOP=1 -q -t -A "$@"; }

require_tenant() { [ -n "$TENANT" ] || die "--tenant is required"; }
require_from()   { [ -n "$FROM" ]   || die "--from is required"; }
require_to()     { [ -n "$TO" ]     || die "--to is required"; }

table_exists() {
  local url="$1" table="$2"
  [ "$(pg "$url" -c "SELECT to_regclass('public.${table}') IS NOT NULL;")" = "t" ]
}

row_count() {
  local url="$1" table="$2" tenant="$3"
  pg "$url" -c "SELECT count(*) FROM ${table} WHERE tenant_id = '${tenant}';"
}

# A content checksum, not just a count.
#
# Counts alone pass when the right NUMBER of wrong rows arrived - a truncated
# TEXT column, a timestamp that lost its zone, a numeric that round-tripped
# through a float. md5 over the whole row catches all of those.
#
# ORDER BY is essential: md5 of a concatenation depends on order, and two
# databases have no reason to return rows in the same physical order.
row_checksum() {
  local url="$1" table="$2" tenant="$3"
  pg "$url" -c "
    SELECT coalesce(md5(string_agg(row_md5, '' ORDER BY row_md5)), 'empty')
      FROM (
        SELECT md5(t::text) AS row_md5
          FROM ${table} t
         WHERE tenant_id = '${tenant}'
      ) s;"
}

case "$COMMAND" in

  # -------------------------------------------------------------------------
  plan-check)
    # A guard, not a planner: confirms the tenant list is usable before anyone
    # starts copying. An empty or malformed list is the difference between
    # "migrated 400 tenants" and "migrated 0 tenants and reported success".
    [ -n "$TENANTS_FILE" ] || die "--tenants-file is required"
    [ -f "$TENANTS_FILE" ] || die "no such file: $TENANTS_FILE"

    total=$(grep -cve '^[[:space:]]*$' "$TENANTS_FILE" || true)
    [ "$total" -gt 0 ] || die "$TENANTS_FILE contains no tenants"

    dupes=$(sort "$TENANTS_FILE" | uniq -d | grep -cve '^[[:space:]]*$' || true)
    [ "$dupes" = "0" ] || die "$TENANTS_FILE has $dupes duplicate tenant(s); a tenant copied twice is a tenant whose rows may be duplicated"

    if grep -qE "[^A-Za-z0-9._-]" "$TENANTS_FILE"; then
      # These values are interpolated into SQL. Anything outside this set is
      # either a quoting bug or an injection attempt, and both stop here.
      die "$TENANTS_FILE contains characters outside [A-Za-z0-9._-]; refusing to interpolate into SQL"
    fi

    ok "$total tenant(s), no duplicates, all safely quotable"
    ;;

  # -------------------------------------------------------------------------
  copy)
    require_tenant; require_from; require_to
    info "copying tenant '$TENANT'"
    printf '%s     tables: %s%s\n' "$DIM" "${TABLES[*]}" "$OFF"

    for table in "${TABLES[@]}"; do
      if ! table_exists "$FROM" "$table"; then
        warn "$table absent on source, skipping"
        continue
      fi
      table_exists "$TO" "$table" \
        || die "$table exists on the source but NOT on the target - run migrations on the target first"

      before=$(row_count "$FROM" "$table" "$TENANT")
      already=$(row_count "$TO" "$table" "$TENANT")

      if [ "$already" != "0" ]; then
        # Not an error: a retried copy is normal, and ON CONFLICT makes it safe.
        warn "$table: target already has $already row(s) for this tenant; copy is idempotent, continuing"
      fi

      # Streamed through a pipe rather than materialised in a temp file. At a
      # few GiB per tenant, a file needs disk that the migration host may not
      # have, and a half-written file looks exactly like a complete one.
      #
      # ON CONFLICT DO NOTHING is what makes a retry safe after a network drop:
      # rows that already arrived are skipped rather than duplicated.
      psql -X -v ON_ERROR_STOP=1 -q "$FROM" \
        -c "\copy (SELECT * FROM ${table} WHERE tenant_id = '${TENANT}') TO STDOUT" \
      | psql -X -v ON_ERROR_STOP=1 -q "$TO" \
        -c "CREATE TEMP TABLE _incoming (LIKE ${table} INCLUDING DEFAULTS);" \
        -c "\copy _incoming FROM STDIN" \
        -c "INSERT INTO ${table} SELECT * FROM _incoming ON CONFLICT DO NOTHING;" \
        -c "DROP TABLE _incoming;"

      after=$(row_count "$TO" "$table" "$TENANT")
      printf '      %-14s source %-8s target %s\n' "$table" "$before" "$after"
    done

    ok "copy finished - now run: verify"
    ;;

  # -------------------------------------------------------------------------
  verify)
    require_tenant; require_from; require_to
    info "verifying tenant '$TENANT'"
    failed=0

    for table in "${TABLES[@]}"; do
      table_exists "$FROM" "$table" || { warn "$table absent on source, skipping"; continue; }

      src_count=$(row_count "$FROM" "$table" "$TENANT")
      dst_count=$(row_count "$TO" "$table" "$TENANT")
      src_sum=$(row_checksum "$FROM" "$table" "$TENANT")
      dst_sum=$(row_checksum "$TO" "$table" "$TENANT")

      if [ "$src_count" != "$dst_count" ]; then
        printf '%sFAIL%s %-14s count  source=%s target=%s\n' \
          "$RED" "$OFF" "$table" "$src_count" "$dst_count"
        failed=1
      elif [ "$src_sum" != "$dst_sum" ]; then
        # The case counts cannot see: the right number of rows, with different
        # content. Usually a type that did not survive the round trip.
        printf '%sFAIL%s %-14s checksum differs (%s rows each)\n' \
          "$RED" "$OFF" "$table" "$src_count"
        printf '      source %s\n      target %s\n' "$src_sum" "$dst_sum"
        failed=1
      else
        printf '%s ok %s %-14s %s rows, checksum %s\n' \
          "$GREEN" "$OFF" "$table" "$src_count" "${src_sum:0:12}"
      fi
    done

    if [ "$failed" != 0 ]; then
      # NOT "re-run copy". Measured: a retry does not repair a row that is
      # already present but wrong, because ON CONFLICT DO NOTHING skips it -
      # which is exactly what makes a retry safe after a network drop, and
      # exactly what makes it useless against corruption. The target has to be
      # emptied for this tenant first.
      printf '
%sdo NOT cut over.%s To recover:
' "$RED" "$OFF" >&2
      printf '  %s reset-target --tenant %s --to "$TO" --confirm
' "$0" "$TENANT" >&2
      printf '  %s copy         --tenant %s --from "$FROM" --to "$TO"
' "$0" "$TENANT" >&2
      printf '  %s verify       --tenant %s --from "$FROM" --to "$TO"
' "$0" "$TENANT" >&2
      exit 1
    fi
    ok "verified - safe to cut over. Cleanup only AFTER the cutover is trusted."
    ;;

  # -------------------------------------------------------------------------
  reset-target)
    # Empties the TARGET for one tenant so a clean copy can be made. Needed
    # because `copy` is idempotent by skipping, not by overwriting: a row that
    # arrived corrupted stays corrupted through any number of retries.
    #
    # Deliberately a separate command rather than a --force flag on copy. This
    # deletes rows on the shard that traffic is about to be pointed AT, and if
    # the cutover has already happened those rows are live. It must be a
    # decision, not a convenience.
    require_tenant; require_to
    [ "$CONFIRM" = 1 ] || die "reset-target deletes this tenant's rows from the TARGET. Re-run with --confirm if the cutover has NOT happened yet."

    info "emptying target for tenant '$TENANT' so a clean copy can be made"
    for table in "${TABLES[@]}"; do
      table_exists "$TO" "$table" || continue
      deleted=$(pg "$TO" -c         "WITH d AS (DELETE FROM ${table} WHERE tenant_id = '${TENANT}' RETURNING 1)
         SELECT count(*) FROM d;")
      printf '      %-14s deleted %s row(s)
' "$table" "$deleted"
    done
    ok "target reset - now run: copy, then verify"
    ;;

  # -------------------------------------------------------------------------
  cleanup)
    require_tenant; require_from
    [ "$CONFIRM" = 1 ] || die "cleanup deletes rows from the SOURCE. Re-run with --confirm once the cutover is trusted."

    # Verification is re-run here rather than assumed, because cleanup may be
    # hours after the copy and the only irreversible step must not depend on a
    # result from a previous shell session.
    if [ -n "$TO" ]; then
      info "re-verifying before deleting anything"
      "$0" verify --tenant "$TENANT" --from "$FROM" --to "$TO" \
        ${INCLUDE_CACHE:+--include-cache} >/dev/null \
        || die "re-verification failed; refusing to delete from the source"
      ok "re-verified"
    else
      warn "--to not given, so the copy cannot be re-verified. Deleting on trust."
    fi

    info "deleting tenant '$TENANT' from the source"
    for table in "${TABLES[@]}"; do
      table_exists "$FROM" "$table" || continue
      deleted=$(pg "$FROM" -c \
        "WITH d AS (DELETE FROM ${table} WHERE tenant_id = '${TENANT}' RETURNING 1)
         SELECT count(*) FROM d;")
      printf '      %-14s deleted %s row(s)\n' "$table" "$deleted"
    done

    ok "source cleaned"
    ;;

  # -------------------------------------------------------------------------
  *)
    sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
