#!/usr/bin/env bash
#
# Drives a whole migration plan: one tenant at a time, in order, stopping dead
# on the first failure.
#
# This is the step that was missing. ShardMigrationPlan says WHICH tenants move;
# shard-migrate.sh moves ONE tenant. Without something in between, an operator
# reads 3,904 moves off a screen and types commands by hand - which is exactly
# how a tenant gets skipped, because there is then no record of what was
# attempted to diff against what was planned.
#
# INPUT is the plan as tab-separated lines, from ShardMigrationPlan.asTsv():
#
#   acme<TAB>shard-1<TAB>shard-4
#   stark<TAB>shard-1<TAB>shard-4
#
# SHARD URLS come from a second file, one per line, so connection strings with
# passwords are never typed on a command line where they reach shell history:
#
#   shard-0<TAB>postgresql://aigw:pw@shard-0-host:5432/aigw
#   shard-1<TAB>postgresql://aigw:pw@shard-1-host:5432/aigw
#
# Usage:
#   ./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase copy
#   ./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase verify
#   ./scripts/shard-migrate-all.sh --plan plan.tsv --shards shards.tsv --phase cleanup --confirm
#
# PHASES ARE SEPARATE RUNS, deliberately. Copy every tenant, verify every
# tenant, then cut over, then clean up. Doing copy-then-verify-then-cleanup per
# tenant would delete the first tenant's source rows before the last tenant had
# even been copied - so a failure halfway leaves some tenants migrated, some
# not, and some with no source to go back to.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIGRATE="${HERE}/shard-migrate.sh"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; OFF=$'\033[0m'
info() { printf '%s==>%s %s\n' "$DIM" "$OFF" "$*"; }
ok()   { printf '%s ok %s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%swarn%s %s\n' "$YELLOW" "$OFF" "$*"; }
die()  { printf '%sFAIL%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

PLAN=""; SHARDS=""; PHASE=""; CONFIRM=0; LIMIT=0; INCLUDE_CACHE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --plan)          PLAN="${2:-}"; shift 2 ;;
    --shards)        SHARDS="${2:-}"; shift 2 ;;
    --phase)         PHASE="${2:-}"; shift 2 ;;
    --limit)         LIMIT="${2:-0}"; shift 2 ;;
    --confirm)       CONFIRM=1; shift ;;
    --include-cache) INCLUDE_CACHE=1; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

[ -n "$PLAN" ]   || die "--plan is required (ShardMigrationPlan.asTsv() output)"
[ -n "$SHARDS" ] || die "--shards is required (shard id -> connection string)"
[ -f "$PLAN" ]   || die "no such file: $PLAN"
[ -f "$SHARDS" ] || die "no such file: $SHARDS"
[ -x "$MIGRATE" ] || die "not executable: $MIGRATE"

case "$PHASE" in
  copy|verify|cleanup) ;;
  *) die "--phase must be copy, verify or cleanup (got '${PHASE}')" ;;
esac

if [ "$PHASE" = cleanup ] && [ "$CONFIRM" != 1 ]; then
  die "cleanup deletes source rows for EVERY tenant in the plan. Re-run with --confirm."
fi

# ---------------------------------------------------------------------------
# Load the shard -> URL map.
# ---------------------------------------------------------------------------
declare -A SHARD_URL=()
while IFS=$'\t' read -r shard url || [ -n "$shard" ]; do
  case "$shard" in ''|'#'*) continue ;; esac
  [ -n "$url" ] || die "shard '$shard' has no URL in $SHARDS"
  SHARD_URL["$shard"]="$url"
done < "$SHARDS"

[ "${#SHARD_URL[@]}" -gt 0 ] || die "$SHARDS defines no shards"
info "${#SHARD_URL[@]} shard(s) known"

# ---------------------------------------------------------------------------
# Validate the WHOLE plan before touching anything.
#
# A missing URL discovered at tenant 2,000 of 3,904 leaves a half-migrated
# system. Every shard the plan references has to be reachable before the first
# row is copied.
# ---------------------------------------------------------------------------
total=0; missing=""
while IFS=$'\t' read -r tenant from to || [ -n "$tenant" ]; do
  case "$tenant" in ''|'#'*) continue ;; esac
  total=$((total + 1))

  [ -n "$from" ] && [ -n "$to" ] || die "malformed line $total: expected tenant<TAB>from<TAB>to"
  [ "$from" != "$to" ] || die "line $total: tenant '$tenant' has from == to ('$from')"

  case "$tenant" in
    *[!A-Za-z0-9._-]*) die "line $total: tenant '$tenant' has characters outside [A-Za-z0-9._-]" ;;
  esac

  [ -n "${SHARD_URL[$from]:-}" ] || missing="${missing} ${from}"
  [ -n "${SHARD_URL[$to]:-}"   ] || missing="${missing} ${to}"
done < "$PLAN"

[ "$total" -gt 0 ] || die "$PLAN contains no moves"
if [ -n "$missing" ]; then
  die "shard(s) in the plan have no URL in ${SHARDS}:$(printf '%s' "$missing" | tr ' ' '\n' | sort -u | tr '\n' ' ')"
fi
ok "plan validated: $total move(s), every shard resolvable"

if [ "$LIMIT" -gt 0 ] && [ "$LIMIT" -lt "$total" ]; then
  warn "--limit $LIMIT: processing the first $LIMIT of $total (rehearsal)"
fi

# ---------------------------------------------------------------------------
# Run the phase.
# ---------------------------------------------------------------------------
info "phase: ${PHASE}"
printf '\n'

done_count=0; index=0; started="$(date +%s)"

while IFS=$'\t' read -r tenant from to || [ -n "$tenant" ]; do
  case "$tenant" in ''|'#'*) continue ;; esac
  index=$((index + 1))
  [ "$LIMIT" -eq 0 ] || [ "$index" -le "$LIMIT" ] || break

  printf '%s[%d/%d]%s %s  %s -> %s\n' \
    "$DIM" "$index" "$total" "$OFF" "$tenant" "$from" "$to"

  args=(--tenant "$tenant")
  [ "$INCLUDE_CACHE" = 0 ] || args+=(--include-cache)

  case "$PHASE" in
    copy)    args+=(--from "${SHARD_URL[$from]}" --to "${SHARD_URL[$to]}") ;;
    verify)  args+=(--from "${SHARD_URL[$from]}" --to "${SHARD_URL[$to]}") ;;
    cleanup) args+=(--from "${SHARD_URL[$from]}" --to "${SHARD_URL[$to]}" --confirm) ;;
  esac

  # No `|| true`. The first failure stops the run, with the tenant named, so the
  # operator fixes that one and resumes rather than discovering at the end that
  # an unknown subset failed. Everything already done is still done: copy is
  # idempotent and nothing was deleted.
  if ! "$MIGRATE" "$PHASE" "${args[@]}" 2>&1 | sed 's/^/      /'; then
    printf '\n'
    die "stopped at tenant '${tenant}' (${index} of ${total}). ${done_count} completed. Fix this tenant, then re-run the same phase - completed tenants are skipped safely because copy is idempotent."
  fi

  done_count=$((done_count + 1))
  printf '\n'
done < "$PLAN"

elapsed=$(( $(date +%s) - started ))
ok "${PHASE}: ${done_count} tenant(s) in ${elapsed}s"

case "$PHASE" in
  copy)
    printf '%s     next: --phase verify (every tenant, before any cutover)%s\n' "$DIM" "$OFF" ;;
  verify)
    printf '%s     next: cut over the ring config, reload the gateways, then soak%s\n' "$DIM" "$OFF" ;;
  cleanup)
    printf '%s     done. The source shards no longer hold these tenants.%s\n' "$DIM" "$OFF" ;;
esac
