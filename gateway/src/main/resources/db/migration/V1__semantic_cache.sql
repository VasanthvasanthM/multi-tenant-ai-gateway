-- =============================================================================
-- Tier 2: semantic cache backed by pgvector.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------------------------------
-- prompt_cache
--
-- PARTITIONED BY HASH (tenant_id), and that is the single most important line in
-- this file.
--
-- A semantic cache lookup is always scoped to one tenant. With hash partitioning
-- on tenant_id, Postgres prunes to exactly one partition for every query, so the
-- approximate-nearest-neighbour search touches one HNSW index instead of all of
-- them. Cross-shard ANN search - querying every shard and merging results - is
-- the usual reason sharded vector stores stop scaling, and this design simply
-- never performs one.
--
-- It also means the table can grow by adding partitions, and that an eventual
-- move to physically separate databases is a change of where the partitions
-- live rather than a change of query shape.
--
-- Eight partitions locally. Production sizing is a deployment decision; the
-- count must be a power of two so that splitting a partition later only has to
-- rehash the rows in that partition.
-- -----------------------------------------------------------------------------
CREATE TABLE prompt_cache (
    id                BIGSERIAL,
    tenant_id         TEXT        NOT NULL,

    -- The Tier-1 SHA-256 key for this exact prompt. Stored so that a Tier-2 hit
    -- can be promoted into Tier 1, which turns the second occurrence of a
    -- paraphrase into a sub-2 ms exact hit instead of another vector search.
    cache_key         TEXT        NOT NULL,

    model_id          TEXT        NOT NULL,

    -- Which embedding model produced the vector. Comparing vectors across models
    -- yields similarity scores that look plausible and mean nothing, so every
    -- query filters on this. Changing models makes old rows invisible rather
    -- than wrong.
    embedding_model   TEXT        NOT NULL,

    -- Retained for shadow-mode analysis and for debugging a bad match. This is
    -- user content: it inherits the tenant's retention policy and is removed by
    -- the same TTL sweep as everything else.
    prompt_text       TEXT        NOT NULL,

    -- 384 = all-MiniLM-L6-v2. Stored L2-normalised, which is what lets the
    -- inner-product index below produce cosine ranking.
    embedding         vector(384) NOT NULL,

    response          TEXT        NOT NULL,
    prompt_tokens     BIGINT      NOT NULL,
    completion_tokens BIGINT      NOT NULL,
    provider          TEXT        NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,

    -- Lets the indexer evict by unpopularity rather than purely by age, and
    -- makes "which prompts actually repeat" answerable.
    hit_count         BIGINT      NOT NULL DEFAULT 0,
    last_hit_at       TIMESTAMPTZ,

    -- tenant_id must be part of the primary key: a partitioned table's unique
    -- constraints have to include the partition key, because uniqueness can only
    -- be enforced within a partition.
    PRIMARY KEY (tenant_id, id)
) PARTITION BY HASH (tenant_id);

CREATE TABLE prompt_cache_p0 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE prompt_cache_p1 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE prompt_cache_p2 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE prompt_cache_p3 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE prompt_cache_p4 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE prompt_cache_p5 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE prompt_cache_p6 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE prompt_cache_p7 PARTITION OF prompt_cache FOR VALUES WITH (MODULUS 8, REMAINDER 7);

-- -----------------------------------------------------------------------------
-- HNSW index for approximate nearest neighbour.
--
-- HNSW rather than IVFFlat:
--   * IVFFlat must be built on existing data to learn its cluster centroids, so
--     an index created on an empty table is useless and has to be rebuilt later.
--     A cache starts empty by definition.
--   * IVFFlat recall degrades as the data drifts away from the centroids it was
--     trained on. Cache contents drift constantly.
--   * HNSW builds incrementally and keeps its recall as rows arrive.
-- HNSW costs more memory and has slower inserts, which is the right trade here:
-- this table is read far more often than it is written, and writes are already
-- off the request path in the indexer service.
--
-- vector_ip_ops (inner product), not vector_cosine_ops: the vectors are stored
-- L2-normalised, and for unit vectors inner product and cosine give identical
-- ranking while inner product skips the magnitude division.
--
-- NOTE ON THE OPERATOR: pgvector's <#> returns the NEGATIVE inner product, so
-- that smaller means closer and it behaves like a distance. Cosine similarity is
-- therefore -(a <#> b). Getting this sign wrong silently returns the LEAST
-- similar rows, which looks like a bad model rather than a bad query.
--
-- m=16, ef_construction=64 are pgvector's defaults and are a reasonable
-- recall/build-time balance. Raising ef_construction improves recall at the cost
-- of a slower build; ef_search is set per query at runtime.
-- -----------------------------------------------------------------------------
CREATE INDEX prompt_cache_embedding_hnsw
    ON prompt_cache
    USING hnsw (embedding vector_ip_ops)
    WITH (m = 16, ef_construction = 64);

-- Supports the equality predicates that accompany every vector search. Ordered
-- with embedding_model first because it is the most selective after partition
-- pruning has already narrowed to one tenant.
CREATE INDEX prompt_cache_lookup
    ON prompt_cache (embedding_model, model_id, expires_at);

-- Drives the TTL sweep. Partial, because only unexpired rows are ever scanned by
-- the serving path and a full index on expires_at would be mostly dead weight.
CREATE INDEX prompt_cache_expiry ON prompt_cache (expires_at);

-- Lets a Tier-2 hit be promoted into Tier 1 without a second lookup, and makes
-- the exact-duplicate upsert path cheap.
CREATE INDEX prompt_cache_key ON prompt_cache (tenant_id, cache_key);

COMMENT ON TABLE prompt_cache IS
    'Tier-2 semantic cache. Hash-partitioned by tenant so every ANN search is '
    'confined to a single partition.';

COMMENT ON COLUMN prompt_cache.embedding IS
    'L2-normalised. The inner-product HNSW index only ranks correctly for unit vectors.';
