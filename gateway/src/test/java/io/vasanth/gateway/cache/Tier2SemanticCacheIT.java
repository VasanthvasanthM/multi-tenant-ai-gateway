package io.vasanth.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.r2dbc.spi.ConnectionFactories;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import io.vasanth.gateway.embedding.Embedding;
import io.vasanth.gateway.shard.ShardedDatabase;
import reactor.test.StepVerifier;

/**
 * Tier 2 against real pgvector, on the real schema.
 *
 * <h2>Why this is an IT and not a unit test</h2>
 *
 * <p>This was the one class in the gateway left below the coverage gate, and deliberately
 * so. Its behaviour is almost entirely <em>in the SQL</em>: the sign of the {@code <#>}
 * operator, the {@code CAST(:query AS vector)} syntax that survives Spring's
 * named-parameter parser, the {@code vector_ip_ops} operator class, and the partition
 * pruning on {@code tenant_id}. Mocking {@code DatabaseClient} needs roughly a dozen stubs
 * per query and then asserts that the code called the methods the test expected - a
 * restatement of the implementation that exercises none of the four things above. The
 * first time any of them would be caught is in production.
 *
 * <h2>The schema is the real one</h2>
 *
 * <p>Flyway applies {@code V1__semantic_cache.sql} rather than a hand-written
 * {@code CREATE TABLE} in this file. A hand-written copy drifts: the first draft of this
 * test invented a {@code response_text} column (the real one is {@code response}) and
 * would have passed happily against its own wrong schema while the production query was
 * broken. Running the migration also means this test is the only thing in the build that
 * proves that migration actually applies.
 *
 * <h2>No Spring context</h2>
 *
 * <p>The cache is constructed directly over {@link ShardedDatabase#single}. A
 * {@code @SpringBootTest} here would pull in Redis and Kafka - two more containers and a
 * lot of startup - to test a class that touches neither.
 */
@Testcontainers
class Tier2SemanticCacheIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tier2")
            .withUsername("test")
            .withPassword("test");

    private static final TenantId ACME = TenantId.of("acme");
    private static final TenantId GLOBEX = TenantId.of("globex");
    private static final ModelId MODEL = ModelId.of("gpt-4o-mini");
    private static final String EMBEDDING_MODEL = "all-MiniLM-L6-v2";
    private static final String PROMPT = "how do I reset my password?";

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final Instant CREATED = NOW.minusSeconds(60);
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    private static DatabaseClient db;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void migrateAndConnect() {
        // baselineVersion 0 for the same reason production sets it: Flyway's default of 1
        // would mark V1 as already applied and silently skip it, leaving a healthy-looking
        // start with no tables.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        db = DatabaseClient.create(ConnectionFactories.get(String.format(
                "r2dbc:postgresql://%s:%s@%s:%d/%s",
                POSTGRES.getUsername(), POSTGRES.getPassword(),
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(),
                POSTGRES.getDatabaseName())));
    }

    private static void exec(String sql) {
        db.sql(sql).fetch().rowsUpdated().block(PATIENCE);
    }

    @BeforeEach
    void reset() {
        exec("TRUNCATE prompt_cache");
        meters = new SimpleMeterRegistry();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Tier2SemanticCache cacheWith(double threshold, boolean shadowMode) {
        return new Tier2SemanticCache(
                ShardedDatabase.single(db), properties(true, threshold, shadowMode),
                meters, FIXED);
    }

    private static GatewayProperties properties(
            boolean tier2Enabled, double threshold, boolean shadowMode) {
        return new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), tier2Enabled, Duration.ofHours(24),
                        threshold, shadowMode),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(4000, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
    }

    /**
     * A unit vector in the plane of the first two axes, at angle {@code radians}.
     *
     * <p>The angle is the point: two of these have a cosine similarity of exactly
     * {@code cos(a - b)}, so a test can place a candidate on a chosen side of the
     * threshold instead of hand-writing vectors and hoping. The vectors are unit length,
     * so their inner product <em>is</em> their cosine - which is why the index uses
     * {@code vector_ip_ops} rather than {@code vector_cosine_ops}.
     *
     * <p>{@code normalizing} rather than {@code of}: {@code cos}/{@code sin} rounded to
     * float need not land inside the 1e-4 norm tolerance, and a test that fails on
     * floating-point housekeeping teaches nobody anything.
     */
    private static Embedding at(double radians) {
        float[] values = new float[Embedding.MINILM_DIMENSION];
        values[0] = (float) Math.cos(radians);
        values[1] = (float) Math.sin(radians);
        return Embedding.normalizing(values);
    }

    private static CompletionResponse response(String content) {
        return new CompletionResponse(
                content, MODEL, new TokenUsage(11L, 4L), CacheTier.UPSTREAM,
                ProviderId.of("mock"), CREATED, Duration.ofMillis(800));
    }

    private void store(Tier2SemanticCache cache, TenantId tenant, String prompt, double radians) {
        StepVerifier.create(cache.store(
                        CacheKey.derive(CompletionRequest.of(tenant, MODEL, prompt)),
                        MODEL, prompt, at(radians), EMBEDDING_MODEL,
                        response("answer to " + prompt)))
                .verifyComplete();
    }

    private double counter(String result) {
        return Optional.ofNullable(meters.find("gateway.cache.lookup")
                        .tag("tier", CacheTier.TIER2_SEMANTIC.label())
                        .tag("result", result)
                        .counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    /** Every outcome summed. Exactly one must fire per lookup. */
    private double totalOutcomes() {
        return counter("hit") + counter("miss") + counter("below_threshold")
                + counter("suppressed") + counter("shadow") + counter("error");
    }

    private Long scalar(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, Long.class)).one().block(PATIENCE);
    }

    // ------------------------------------------------------------------
    // The round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stored entry is served to a near-identical vector")
    void storeThenServe() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        // 0.02 rad away: cos(0.02) = 0.9998, comfortably over 0.95.
        CompletionResponse served = cache.lookup(ACME, MODEL, at(0.02), EMBEDDING_MODEL, true)
                .block(PATIENCE);

        assertThat(served).isNotNull();
        assertThat(served.content()).isEqualTo("answer to " + PROMPT);
        assertThat(served.servedBy()).isEqualTo(CacheTier.TIER2_SEMANTIC);
        assertThat(served.provider()).isEqualTo(ProviderId.CACHE);
        // The original timestamp survives, not the time it was served. A client that
        // caches on createdAt must not be told this answer is new.
        assertThat(served.createdAt()).isEqualTo(CREATED);
        // TokenUsage.cacheHit zeroes the completion tokens deliberately: nothing was
        // generated, so nothing may be billed as generation.
        assertThat(served.usage().promptTokens()).isEqualTo(11L);
        assertThat(served.usage().completionTokens()).isZero();

        assertThat(counter("hit")).isEqualTo(1.0d);
        assertThat(totalOutcomes()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the nearest entry is returned, not the farthest: the operator sign is right")
    void returnsTheNearestNotTheFarthest() {
        Tier2SemanticCache cache = cacheWith(0.90, false);

        store(cache, ACME, "nearest", 0.0);
        store(cache, ACME, "middle", 0.30);
        store(cache, ACME, "farthest", 0.60);

        CompletionResponse served = cache.lookup(ACME, MODEL, at(0.01), EMBEDDING_MODEL, true)
                .block(PATIENCE);

        // pgvector's <#> returns the NEGATIVE inner product, so smaller sorts first and it
        // behaves like a distance. Dropping the negation does not throw: it silently ranks
        // the LEAST similar row first and the cache answers a different question. Here that
        // would surface the 0.83-similarity "farthest" entry, which the 0.90 threshold then
        // refuses - so the symptom is a cache that never hits and a metric that reads as
        // "the embedding model is bad". This is the test that names the real cause.
        assertThat(served).isNotNull();
        assertThat(served.content()).isEqualTo("answer to nearest");
    }

    @Test
    @DisplayName("a similarity below the threshold is refused, and counted as such")
    void belowThresholdIsRefused() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        // 0.45 rad: cos(0.45) = 0.9004, under 0.95.
        StepVerifier.create(cache.lookup(ACME, MODEL, at(0.45), EMBEDDING_MODEL, true))
                .verifyComplete();

        assertThat(counter("below_threshold")).isEqualTo(1.0d);
        // The invariant the counter-placement bug broke: a below-threshold candidate used
        // to increment below_threshold AND miss, because the miss counter sat in a
        // switchIfEmpty after the decision. The hit ratio is hits over the sum of these,
        // so double-counting deflated the headline number - and Tier 1 counted correctly,
        // which made one PromQL expression mean two different things depending on tier.
        assertThat(totalOutcomes()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an empty cache is one miss and nothing else")
    void emptyCacheIsOneMiss() {
        Tier2SemanticCache cache = cacheWith(0.95, false);

        StepVerifier.create(cache.lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, true))
                .verifyComplete();

        assertThat(counter("miss")).isEqualTo(1.0d);
        assertThat(totalOutcomes()).isEqualTo(1.0d);
    }

    // ------------------------------------------------------------------
    // Isolation - the properties that make the product shippable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one tenant never sees another tenant's entry, even for an identical vector")
    void tenantsAreIsolated() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        // The SAME vector, a different tenant. If the WHERE clause lost its tenant
        // predicate this would return acme's answer, and the product would not be
        // shippable at any scale.
        StepVerifier.create(cache.lookup(GLOBEX, MODEL, at(0.0), EMBEDDING_MODEL, true))
                .verifyComplete();
        assertThat(counter("miss")).isEqualTo(1.0d);

        // And the entry is genuinely there, so the assertion above is isolation rather
        // than an empty table.
        assertThat(cache.lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, true).block(PATIENCE))
                .isNotNull();
    }

    @Test
    @DisplayName("a different model does not match at identical similarity")
    void modelsAreIsolated() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        StepVerifier.create(cache.lookup(
                        ACME, ModelId.of("claude-haiku-4-5"), at(0.0), EMBEDDING_MODEL, true))
                .verifyComplete();
        assertThat(counter("miss")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a different embedding model does not match at identical similarity")
    void embeddingModelsAreIsolated() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        // Vectors from two different models live in incomparable spaces: a cosine of 0.99
        // between them is a plausible-looking number that means nothing. Matching across
        // them serves answers chosen essentially at random, so changing embedding model
        // must make old rows invisible rather than wrong.
        StepVerifier.create(cache.lookup(
                        ACME, MODEL, at(0.0), "text-embedding-3-small", true))
                .verifyComplete();
        assertThat(counter("miss")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an expired entry is not served")
    void expiredEntriesAreNotServed() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        // Expired behind the cache's back, which is what a TTL does in reality. The query
        // filters on expires_at > now(), so this must read as "nothing similar exists".
        exec("UPDATE prompt_cache SET expires_at = now() - interval '1 hour'");

        StepVerifier.create(cache.lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, true))
                .verifyComplete();
        assertThat(counter("miss")).isEqualTo(1.0d);
    }

    // ------------------------------------------------------------------
    // The two paths that must never serve
    // ------------------------------------------------------------------

    @Test
    @DisplayName("shadow mode records what it would have served, and serves nothing")
    void shadowModeDoesNotServe() {
        Tier2SemanticCache cache = cacheWith(0.95, true);
        store(cache, ACME, PROMPT, 0.0);

        StepVerifier.create(cache.lookup(ACME, MODEL, at(0.01), EMBEDDING_MODEL, true))
                .verifyComplete();

        // Shadow mode is the whole rollout plan: query, log, serve nothing, until a real
        // false-hit rate has been measured on real traffic. It is on by default, so this
        // path is what a fresh deployment actually runs.
        assertThat(meters.find("gateway.cache.shadow_would_have_served").counter().count())
                .isEqualTo(1.0d);
        assertThat(counter("shadow")).isEqualTo(1.0d);
        assertThat(counter("hit")).isZero();
        assertThat(totalOutcomes()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a non-semantic embedding model never serves, however high the score")
    void nonSemanticModelNeverServes() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        // An identical vector, so similarity is 1.0 - and it must still be refused. The
        // hashing fallback the gateway uses when ONNX fails to load matches surface form,
        // and a surface-form near-match is not an answer to the same question.
        StepVerifier.create(cache.lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, false))
                .verifyComplete();

        assertThat(counter("suppressed")).isEqualTo(1.0d);
        assertThat(counter("hit")).isZero();
        assertThat(totalOutcomes()).isEqualTo(1.0d);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stored row keeps the token counts, the provider and the original timestamp")
    void storedRowKeepsItsProvenance() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        record Provenance(
                long promptTokens, long completionTokens, String provider, Instant createdAt) {
        }
        Provenance row = db.sql("""
                        SELECT prompt_tokens, completion_tokens, provider, created_at
                          FROM prompt_cache
                         WHERE tenant_id = 'acme'
                        """)
                .map((r, meta) -> new Provenance(
                        r.get("prompt_tokens", Long.class),
                        r.get("completion_tokens", Long.class),
                        r.get("provider", String.class),
                        r.get("created_at", Instant.class)))
                .one()
                .block(PATIENCE);

        assertThat(row).isNotNull();
        // The completion tokens stored are the FULL original cost, not the zeroed
        // cache-hit figure the response carries. They are what makes the saving
        // measurable: the economics in scaling-model.md are this number times hit_count.
        assertThat(row.promptTokens()).isEqualTo(11L);
        assertThat(row.completionTokens()).isEqualTo(4L);
        assertThat(row.provider()).isEqualTo("mock");
        assertThat(row.createdAt()).isEqualTo(CREATED);
    }

    @Test
    @DisplayName("expires_at comes from the injected clock, not wall time")
    void expiryUsesTheInjectedClock() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        Instant expiresAt = db.sql("SELECT expires_at FROM prompt_cache WHERE tenant_id = 'acme'")
                .map((r, meta) -> r.get("expires_at", Instant.class))
                .one()
                .block(PATIENCE);

        // Exactly NOW + 24h. The clock is injected precisely so a TTL test can assert this
        // instead of sleeping, which would be both slow and flaky. Enforced by
        // ArchitectureRulesTest.noStaticNowCalls.
        assertThat(expiresAt).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("a hit increments the row's hit counter")
    void hitIsRecorded() {
        Tier2SemanticCache cache = cacheWith(0.95, false);
        store(cache, ACME, PROMPT, 0.0);

        assertThat(cache.lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, true).block(PATIENCE))
                .isNotNull();

        // recordHit is fire-and-forget - subscribe() with no composition - so the response
        // returns without waiting for it. Poll rather than sleep a fixed amount. It drives
        // the indexer's evict-by-unpopularity ordering, which is the only thing keeping the
        // working set bounded.
        assertThat(awaitHitCount()).isEqualTo(1L);
    }

    private Long awaitHitCount() {
        Long hits = 0L;
        for (int attempt = 0; attempt < 50; attempt++) {
            hits = scalar("SELECT hit_count FROM prompt_cache WHERE tenant_id = 'acme'");
            if (hits != null && hits > 0L) {
                return hits;
            }
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return hits;
            }
        }
        return hits;
    }

    @Test
    @DisplayName("a disabled tier 2 does not touch the database at all")
    void disabledTierSkipsTheDatabase() {
        Tier2SemanticCache cache = new Tier2SemanticCache(
                ShardedDatabase.single(db), properties(false, 0.95, false), meters, FIXED);

        StepVerifier.create(cache.lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, true))
                .verifyComplete();
        StepVerifier.create(cache.store(
                        CacheKey.derive(CompletionRequest.of(ACME, MODEL, PROMPT)),
                        MODEL, PROMPT, at(0.0), EMBEDDING_MODEL, response("a")))
                .verifyComplete();

        // Nothing written and nothing counted. A disabled tier must not cost a round trip
        // on every request, and must not pollute the hit ratio with zeroes either.
        assertThat(scalar("SELECT count(*) FROM prompt_cache")).isZero();
        assertThat(totalOutcomes()).isZero();
    }

    // ------------------------------------------------------------------
    // Degradation - Tier 2 is an optimisation, never a dependency
    // ------------------------------------------------------------------

    /**
     * A cache pointed at nothing. Port 1 has no listener, so every statement fails to
     * connect.
     *
     * <p>Cheaper and more honest than killing the container mid-test: the failure is
     * immediate, deterministic, and does not leave the shared container in a state the
     * next test inherits.
     */
    private Tier2SemanticCache brokenCache() {
        // connectTimeout is not optional here. A refused connection usually fails at
        // once, but a port that blackholes rather than resets leaves the attempt hanging,
        // and `verifyComplete()` has no timeout of its own - the test would hang until
        // the CI job's own limit and read as an infrastructure problem.
        DatabaseClient broken = DatabaseClient.create(ConnectionFactories.get(
                "r2dbc:postgresql://test:test@localhost:1/nothing?connectTimeout=2s"));
        return new Tier2SemanticCache(
                ShardedDatabase.single(broken), properties(true, 0.95, false), meters, FIXED);
    }

    @Test
    @DisplayName("a database failure degrades to a miss, not a failed request")
    void lookupSurvivesADatabaseFailure() {
        // The claim this pins: Tier 2 going down makes the system slower and more
        // expensive, not unavailable. The request falls through to the provider and the
        // user sees an answer. If this ever propagated the error instead, a pgvector
        // outage would become a total outage - turning a cost optimisation into the least
        // reliable component in the request path.
        StepVerifier.create(brokenCache()
                        .lookup(ACME, MODEL, at(0.0), EMBEDDING_MODEL, true))
                .expectComplete()
                .verify(PATIENCE);

        // Counted as an error, NOT as a miss. The difference matters on a dashboard: a
        // miss means the cache worked and found nothing, and if a broken database showed
        // up as a wall of misses the first conclusion would be that the cache is
        // ineffective rather than that it is down.
        assertThat(counter("error")).isEqualTo(1.0d);
        assertThat(counter("miss")).isZero();
        assertThat(totalOutcomes()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a failed store does not fail the request that produced the answer")
    void storeSurvivesADatabaseFailure() {
        // The caller already has a valid upstream response and the user has already paid
        // for it. Losing the chance to cache it is strictly less bad than discarding work
        // that has been done, so this completes empty rather than erroring.
        StepVerifier.create(brokenCache().store(
                        CacheKey.derive(CompletionRequest.of(ACME, MODEL, PROMPT)),
                        MODEL, PROMPT, at(0.0), EMBEDDING_MODEL, response("an answer")))
                .expectComplete()
                .verify(PATIENCE);
    }

    // ------------------------------------------------------------------
    // The schema all of the above depends on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the HNSW index uses vector_ip_ops, which is what <#> needs")
    void indexOperatorClassMatchesTheQuery() {
        String definition = db.sql("""
                        SELECT indexdef FROM pg_indexes
                         WHERE indexname = 'prompt_cache_embedding_hnsw'
                        """)
                .map((r, meta) -> r.get("indexdef", String.class))
                .one()
                .block(PATIENCE);

        // The realistic failure is not a missing index but a mismatched operator class: an
        // index built with vector_cosine_ops is never usable by a <#> query, and Postgres
        // says nothing - it falls back to a sequential scan plus a sort. Every test above
        // still passes, because the answers stay correct; only the latency changes. That is
        // invisible at 50 rows and fatal at 50 million, which is exactly the kind of thing
        // a test should hold still.
        //
        // This asserts the index DEFINITION, not that the planner chose it: on a table this
        // small Postgres legitimately prefers a scan, so asserting the plan would be
        // asserting the row count.
        assertThat(definition).contains("USING hnsw").contains("vector_ip_ops");
    }

    @Test
    @DisplayName("prompt_cache is hash-partitioned by tenant_id, into eight partitions")
    void tableIsPartitionedByTenant() {
        Long partitions = scalar("""
                SELECT count(*) FROM pg_class parent
                  JOIN pg_inherits ON pg_inherits.inhparent = parent.oid
                 WHERE parent.relname = 'prompt_cache'
                """);

        // Partitioning on tenant_id is what confines every ANN search to one partition and
        // one HNSW index. A cross-shard nearest-neighbour search - query every partition,
        // merge the results - is the usual reason sharded vector stores stop scaling, and
        // this design must never perform one.
        assertThat(partitions).isEqualTo(8L);

        String strategy = db.sql("""
                        SELECT pg_get_partkeydef(oid) FROM pg_class
                         WHERE relname = 'prompt_cache'
                        """)
                .map((r, meta) -> r.get(0, String.class))
                .one()
                .block(PATIENCE);
        assertThat(strategy).isEqualTo("HASH (tenant_id)");
    }
}
