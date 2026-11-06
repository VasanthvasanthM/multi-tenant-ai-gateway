-- =============================================================================
-- Audit: append-only record of who did what.
-- =============================================================================

-- Separate from the usage ledger because the requirements are opposites. Audit
-- data is write-once and retained for years under a compliance policy; usage
-- aggregates are mutable and kept for a billing cycle. Conflating them forces
-- the strictest rule of both onto all of it - years of retention on data that
-- changes, and immutability on data that must be corrected.
CREATE TABLE audit_log (
    id                  BIGSERIAL,
    tenant_id           TEXT        NOT NULL,
    request_id          TEXT        NOT NULL,

    -- First 16 hex characters of the SHA-256 of the API key. Enough to identify
    -- which credential was used, without storing anything that could
    -- authenticate. An audit table is exactly the kind of thing exported to a
    -- compliance reviewer, and it must not contain usable secrets.
    api_key_fingerprint TEXT        NOT NULL,

    model_id            TEXT        NOT NULL,
    served_by           TEXT        NOT NULL,
    outcome             TEXT        NOT NULL,
    source_address      TEXT,

    occurred_at         TIMESTAMPTZ NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- PARTITION KEY MUST APPEAR IN EVERY UNIQUE CONSTRAINT.
    --
    -- Postgres can only enforce uniqueness within a partition, so a unique
    -- constraint on a partitioned table has to include all partition key
    -- columns. Omitting occurred_at fails outright with
    -- "unique constraint on partitioned table must include all partitioning
    -- columns" - at migration time, which is the good case.
    --
    -- Including it does NOT weaken idempotency here: occurred_at comes from the
    -- event payload, not from the clock at insert time, so a redelivered event
    -- carries the identical value and the constraint still catches it. It would
    -- weaken it if the column defaulted to now().
    PRIMARY KEY (occurred_at, tenant_id, id),
    CONSTRAINT audit_log_idempotency UNIQUE (occurred_at, tenant_id, request_id)
) PARTITION BY RANGE (occurred_at);

-- Range-partitioned by time, not by tenant - the opposite choice to every other
-- table here, and deliberately so. Audit is queried as "everything in this
-- window" far more often than "everything for one tenant", and time
-- partitioning turns retention into dropping an old partition rather than
-- deleting millions of rows. On an append-only table a mass DELETE leaves bloat
-- that autovacuum then chases indefinitely.
CREATE TABLE audit_log_2026q4 PARTITION OF audit_log
    FOR VALUES FROM ('2026-10-01') TO ('2027-01-01');
CREATE TABLE audit_log_2027q1 PARTITION OF audit_log
    FOR VALUES FROM ('2027-01-01') TO ('2027-04-01');

-- Catch-all, so an event outside the provisioned windows is never rejected.
-- Without it a clock skew or a late backfill fails the insert outright and the
-- record is lost rather than merely misfiled. Losing audit records to a
-- partitioning detail would be indefensible.
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;

CREATE INDEX audit_log_tenant_time ON audit_log (tenant_id, occurred_at DESC);
CREATE INDEX audit_log_key ON audit_log (api_key_fingerprint, occurred_at DESC);

COMMENT ON TABLE audit_log IS
    'Append-only. Range-partitioned by time so retention is a partition drop.';
