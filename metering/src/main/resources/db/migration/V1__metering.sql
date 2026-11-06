-- =============================================================================
-- Metering: usage ledger, rolling aggregates, and the transactional outbox.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- usage_ledger
--
-- Append-only record of every billable request.
--
-- THE UNIQUE CONSTRAINT IS THE WHOLE DESIGN. Kafka delivers at least once: a
-- consumer that crashes after processing but before committing its offset will
-- see the same event again on restart. That is not an edge case, it is normal
-- operation. Without (tenant_id, request_id) UNIQUE, every rebalance and every
-- restart silently double-bills.
--
-- Inserts therefore use ON CONFLICT DO NOTHING, which makes redelivery a no-op
-- rather than something the application has to detect.
-- -----------------------------------------------------------------------------
CREATE TABLE usage_ledger (
    id                BIGSERIAL,
    tenant_id         TEXT        NOT NULL,
    request_id        TEXT        NOT NULL,

    model_id          TEXT        NOT NULL,
    served_by         TEXT        NOT NULL,
    provider          TEXT        NOT NULL,

    prompt_tokens     BIGINT      NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens BIGINT      NOT NULL CHECK (completion_tokens >= 0),

    -- Denormalised so the common "spend this period" query needs no arithmetic
    -- over two columns and can be answered from an index alone.
    total_tokens      BIGINT      NOT NULL CHECK (total_tokens >= 0),

    -- False for a cache hit. The tenant still consumed quota, but no vendor was
    -- paid - and without this column the whole value of the cache is invisible
    -- on the bill.
    billable_upstream BOOLEAN     NOT NULL,

    latency_millis    BIGINT      NOT NULL,
    occurred_at       TIMESTAMPTZ NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, id),
    CONSTRAINT usage_ledger_idempotency UNIQUE (tenant_id, request_id)
) PARTITION BY HASH (tenant_id);

CREATE TABLE usage_ledger_p0 PARTITION OF usage_ledger FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE usage_ledger_p1 PARTITION OF usage_ledger FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE usage_ledger_p2 PARTITION OF usage_ledger FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE usage_ledger_p3 PARTITION OF usage_ledger FOR VALUES WITH (MODULUS 4, REMAINDER 3);

-- Serves "what has this tenant spent this period", the single most frequent query.
CREATE INDEX usage_ledger_tenant_period ON usage_ledger (tenant_id, occurred_at DESC);

-- -----------------------------------------------------------------------------
-- tenant_usage_rollup
--
-- Pre-aggregated spend per tenant per hour.
--
-- Scanning the raw ledger to answer "spend today" means reading every row a busy
-- tenant produced. At a thousand requests a second that is 86 million rows for a
-- single dashboard panel. The rollup is maintained incrementally on each insert
-- so the query is a handful of rows instead.
-- -----------------------------------------------------------------------------
CREATE TABLE tenant_usage_rollup (
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

-- -----------------------------------------------------------------------------
-- outbox
--
-- Solves the dual-write problem. This service writes a ledger row AND publishes
-- a billing event. Writing then publishing loses the event if the publish fails;
-- publishing then writing bills for work that was never recorded. Neither is
-- acceptable for money.
--
-- The event row is written in the SAME transaction as the ledger row, so either
-- both exist or neither does. A relay then polls this table and publishes,
-- marking rows done. Publishing may therefore happen more than once - the relay
-- can crash after publishing and before marking - which is why consumers of
-- these events must be idempotent too. At-least-once with idempotent consumers
-- is achievable; exactly-once across two systems is not.
--
-- Debezium reading the WAL is the production-grade relay. It needs Kafka Connect,
-- which does not fit in this laptop's RAM, so the relay here is an in-process
-- poller using FOR UPDATE SKIP LOCKED. See docs/adr/0007-outbox-and-saga.md
-- -----------------------------------------------------------------------------
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    aggregate_id  TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,
    topic         TEXT        NOT NULL,

    -- Kafka partition key. Always the tenant id, so per-tenant ordering holds.
    partition_key TEXT        NOT NULL,
    payload       JSONB       NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- Partial index covering exactly the relay's query: unpublished rows, oldest
-- first. Partial because published rows are the overwhelming majority and
-- indexing them would be dead weight that grows forever.
CREATE INDEX outbox_unpublished
    ON outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS
    'Transactional outbox. Rows are written in the same transaction as the '
    'business change, then relayed to Kafka by a poller using SKIP LOCKED.';

COMMENT ON CONSTRAINT usage_ledger_idempotency ON usage_ledger IS
    'Makes Kafka at-least-once redelivery a no-op instead of a double-bill.';
