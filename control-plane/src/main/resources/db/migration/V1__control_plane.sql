-- =============================================================================
-- Control plane: tenants, API keys, quotas, provisioning sagas, outbox.
-- =============================================================================

CREATE TABLE tenants (
    tenant_id            TEXT        PRIMARY KEY,
    display_name         TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'ACTIVE'
                                     CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','DELETED')),

    -- Per-tenant overrides of the gateway defaults.
    quota_tokens         BIGINT      NOT NULL DEFAULT 1000000 CHECK (quota_tokens >= 0),
    rate_limit_capacity  INT         NOT NULL DEFAULT 100 CHECK (rate_limit_capacity > 0),
    rate_limit_refill    INT         NOT NULL DEFAULT 20 CHECK (rate_limit_refill > 0),

    -- Tier-2 is opt-in per tenant and the floor is enforced here as well as in
    -- application config. A semantic cache serving a 0.80 match returns a
    -- confident wrong answer, and a CHECK constraint is the one place that
    -- cannot be bypassed by a misconfigured deployment.
    semantic_cache_enabled BOOLEAN   NOT NULL DEFAULT false,
    similarity_threshold   NUMERIC(4,3) NOT NULL DEFAULT 0.950
                                     CHECK (similarity_threshold >= 0.900
                                            AND similarity_threshold <= 1.000),

    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- api_keys
--
-- Only the SHA-256 of a key is ever stored. An attacker who reads this table or
-- a backup of it obtains hashes, not credentials.
--
-- A plain digest is correct here, unlike for passwords. API keys are 256 bits of
-- random data, so there is no dictionary to attack and a slow KDF such as bcrypt
-- would add milliseconds to a 2 ms authentication budget for no security gain.
-- -----------------------------------------------------------------------------
CREATE TABLE api_keys (
    key_hash     TEXT        PRIMARY KEY,
    tenant_id    TEXT        NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,

    -- First 16 hex characters, stored separately so a human can identify a key
    -- in a list or an audit export without the full hash being displayed.
    fingerprint  TEXT        NOT NULL,
    label        TEXT        NOT NULL DEFAULT '',

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

-- Partial: only live keys are ever looked up, and a full index would carry every
-- key ever revoked forever.
CREATE INDEX api_keys_active ON api_keys (tenant_id) WHERE revoked_at IS NULL;

-- -----------------------------------------------------------------------------
-- saga_instances
--
-- Tenant provisioning spans Postgres, Redis and Kafka. Those three share no
-- transaction, so a failure partway through leaves the system inconsistent
-- unless the earlier steps are undone.
--
-- ORCHESTRATION, NOT CHOREOGRAPHY. Choreography - each service reacting to the
-- previous one's event - is fashionable and scatters one business process across
-- several codebases, where no single place can answer "where is tenant X stuck?".
-- An orchestrator with persisted state can answer exactly that with a SELECT,
-- which is worth the coupling it costs.
-- -----------------------------------------------------------------------------
CREATE TABLE saga_instances (
    saga_id        UUID        PRIMARY KEY,
    saga_type      TEXT        NOT NULL,
    aggregate_id   TEXT        NOT NULL,

    state          TEXT        NOT NULL
                               CHECK (state IN ('STARTED','COMPLETED','COMPENSATING',
                                                'COMPENSATED','FAILED')),
    -- Index of the last step that succeeded. Compensation walks backwards from
    -- here, so it must be durable: a crash mid-saga has to resume correctly
    -- rather than compensate steps that never ran.
    completed_step INT         NOT NULL DEFAULT 0,

    payload        JSONB       NOT NULL,
    last_error     TEXT,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Finds sagas needing recovery after a crash.
CREATE INDEX saga_instances_unfinished
    ON saga_instances (created_at)
    WHERE state IN ('STARTED', 'COMPENSATING');

-- -----------------------------------------------------------------------------
-- control_outbox
--
-- The dual-write problem, again. A tenant row must be written AND an event
-- published so the data plane learns about it. Write-then-publish loses the
-- event if the publish fails - the gateway never learns the key exists.
-- Publish-then-write announces a tenant that does not exist.
--
-- The event row is written in the SAME transaction as the tenant change, so
-- either both happen or neither does. A relay then polls and publishes.
--
-- Publishing may happen more than once: the relay can crash after publishing and
-- before marking the row done. That is why every consumer of these events is
-- idempotent. At-least-once with idempotent consumers is achievable; exactly-once
-- across two systems is not.
-- -----------------------------------------------------------------------------
CREATE TABLE control_outbox (
    id            BIGSERIAL   PRIMARY KEY,
    aggregate_id  TEXT        NOT NULL,
    event_type    TEXT        NOT NULL,
    topic         TEXT        NOT NULL,
    partition_key TEXT        NOT NULL,
    payload       JSONB       NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT
);

-- Exactly the relay's query: unpublished rows, oldest first. Partial, because
-- published rows are the overwhelming majority and indexing them would be dead
-- weight that grows without bound.
CREATE INDEX control_outbox_unpublished
    ON control_outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE control_outbox IS
    'Transactional outbox. Written in the same transaction as the business change, '
    'then relayed to Kafka by a poller using FOR UPDATE SKIP LOCKED.';

COMMENT ON TABLE saga_instances IS
    'Orchestrated sagas. completed_step is durable so a crash resumes or compensates '
    'from the correct point rather than replaying steps that never ran.';
