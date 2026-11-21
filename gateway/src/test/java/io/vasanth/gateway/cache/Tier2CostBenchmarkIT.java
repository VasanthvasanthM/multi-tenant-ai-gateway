package io.vasanth.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.r2dbc.spi.ConnectionFactories;
import io.vasanth.gateway.embedding.Embedding;
import io.vasanth.gateway.embedding.OnnxEmbeddingModel;

/**
 * Measures the five numbers the capacity plan assumes.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code B10} sizes a fleet for two billion requests a day from <b>one</b> measured
 * input - 271.3 rps on a single pod - and nine assumptions. Five of those nine are
 * measurable in minutes against real containers, and until this file nobody had:
 *
 * <table>
 *   <caption>What this replaces</caption>
 *   <tr><th>assumption</th><th>what it drives</th></tr>
 *   <tr><td>6 ms per embedding</td><td>all 242 embedding vCPU</td></tr>
 *   <tr><td>~100 ms Tier-2 lookup</td><td>the Tier-2 latency budget</td></tr>
 *   <tr><td>2 KiB per Redis entry</td><td>the whole Redis fleet (A16)</td></tr>
 *   <tr><td>4 KiB per Postgres row</td><td>763 GiB of data</td></tr>
 *   <tr><td>2.3 KiB per HNSW entry</td><td>439 GiB of index, and the shard count</td></tr>
 * </table>
 *
 * <h2>Why it does not run in CI</h2>
 *
 * <p>Every figure here is a property of the machine it ran on, so asserting a threshold
 * would make the build fail on a slower runner for no defect. It is enabled by a system
 * property and <b>prints</b> its results; the assertions are only sanity bounds wide
 * enough that they can fail for a real reason and nothing else.
 *
 * <pre>
 *   ./mvnw -pl gateway -am verify -Dit.test=Tier2CostBenchmarkIT -Dbenchmark=true
 * </pre>
 *
 * <p>It also needs the real model on disk - {@code scripts/fetch-model.sh} - and skips
 * itself rather than measuring the lexical fallback, which would report a speed that has
 * nothing to do with the thing being sized.
 */
@Testcontainers
@EnabledIfSystemProperty(named = "benchmark", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Tier2CostBenchmarkIT {

    /** Prompts embedded per sample. Large enough that JIT warm-up is not the measurement. */
    private static final int EMBED_SAMPLES = 500;

    /** Rows written before storage is measured, and searched against. */
    private static final int ROWS = 500;

    /** Vector searches timed. */
    private static final int SEARCHES = 100;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("bench")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379);

    private static DatabaseClient db;
    private static ReactiveStringRedisTemplate redis;
    private static OnnxEmbeddingModel model;
    private static final List<String> RESULTS = new ArrayList<>();

    /** Set by the first test, so the Tier-2 total uses the MEASURED cost. */
    private static double embeddingMeanMillis;

    /**
     * Prompts shaped like real support traffic rather than lorem.
     *
     * <p>Length matters more than content here: the embedding cost scales with token
     * count, and the row size is dominated by the prompt and response text alongside the
     * fixed 1,536-byte vector.
     */
    private static String prompt(int i) {
        return "Customer " + i + " asks: how do I reset the password on my account, "
                + "and why did the link in the email expire before I could use it?";
    }

    private static String response(int i) {
        return "Click 'Forgot password' on the sign-in page and request a new link. "
                + "Reset links expire after 30 minutes for security. If link " + i
                + " also fails, clear your browser cache and try an incognito window.";
    }

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        db = DatabaseClient.create(ConnectionFactories.get(String.format(
                "r2dbc:pool:postgresql://%s:%s@%s:%d/%s?initialSize=4&maxSize=8",
                POSTGRES.getUsername(), POSTGRES.getPassword(),
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(),
                POSTGRES.getDatabaseName())));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getFirstMappedPort());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);

        Path modelDir = Path.of("..", "models", "all-MiniLM-L6-v2");
        // Skipping beats measuring the lexical fallback: it would report a speed that has
        // nothing to do with the model the capacity plan is sized around.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(modelDir) && Files.exists(modelDir.resolve("model.onnx")),
                "real ONNX model not on disk - run scripts/fetch-model.sh");

        model = OnnxEmbeddingModel.load(modelDir, "all-MiniLM-L6-v2", 1);
    }

    private static void record(String line) {
        RESULTS.add(line);
        System.out.println("  BENCH  " + line);
    }

    private static double percentile(List<Long> sortedNanos, double p) {
        int index = (int) Math.ceil(p / 100.0 * sortedNanos.size()) - 1;
        return sortedNanos.get(Math.max(0, Math.min(index, sortedNanos.size() - 1))) / 1e6;
    }

    // ------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("1. embedding cost per prompt - replaces the ASSUMED 6 ms")
    void embeddingCost() {
        // Warm-up, excluded. The first few hundred calls are interpreted bytecode and a
        // cold ONNX arena; including them would measure start-up, not steady state.
        for (int i = 0; i < 100; i++) {
            model.embed(prompt(i));
        }

        List<Long> timings = new ArrayList<>(EMBED_SAMPLES);
        for (int i = 0; i < EMBED_SAMPLES; i++) {
            long start = System.nanoTime();
            Embedding embedding = model.embed(prompt(i));
            timings.add(System.nanoTime() - start);
            assertThat(embedding.dimension()).isEqualTo(384);
        }
        Collections.sort(timings);

        double mean = timings.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        embeddingMeanMillis = mean;
        record(String.format(Locale.ROOT,
                "embedding: mean %.2f ms, p50 %.2f ms, p95 %.2f ms, p99 %.2f ms  (n=%d, 1 predictor)",
                mean, percentile(timings, 50), percentile(timings, 95),
                percentile(timings, 99), EMBED_SAMPLES));
        record(String.format(Locale.ROOT,
                "embedding throughput: %.0f per second per core", 1000.0 / mean));

        // A sanity bound, not a threshold: anything over a second means the model fell
        // back or the machine is pathological, and the number should not be recorded.
        assertThat(mean).isLessThan(1000.0);
    }

    // ------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("2. storage per row and per index entry - replaces the ASSUMED 4 KiB and 2.3 KiB")
    void storageCost() {
        Instant now = Instant.now();
        for (int i = 0; i < ROWS; i++) {
            Embedding embedding = model.embed(prompt(i));
            db.sql("""
                    INSERT INTO prompt_cache (tenant_id, cache_key, model_id, embedding_model,
                                              prompt_text, embedding, response, prompt_tokens,
                                              completion_tokens, provider, created_at, expires_at)
                    VALUES (:tenant, :key, :model, :embModel, :prompt, CAST(:embedding AS vector),
                            :response, 22, 48, 'openai', :created, :expires)
                    """)
                    .bind("tenant", "acme")
                    .bind("key", String.format("%064x", i))
                    .bind("model", "gpt-4o-mini")
                    .bind("embModel", "all-MiniLM-L6-v2")
                    .bind("prompt", prompt(i))
                    .bind("embedding", embedding.toPgVector())
                    .bind("response", response(i))
                    .bind("created", now)
                    .bind("expires", now.plus(Duration.ofHours(24)))
                    .fetch().rowsUpdated().block(Duration.ofMinutes(2));
        }

        // ANALYZE first: relation sizes are accurate immediately, but the planner stats
        // this query reads are not, and a VACUUM also settles the visibility map so the
        // numbers do not drift between runs.
        db.sql("VACUUM ANALYZE prompt_cache").fetch().rowsUpdated().block(Duration.ofMinutes(2));

        Long rows = db.sql("SELECT count(*) FROM prompt_cache")
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(30));

        // prompt_cache is PARTITIONED, so pg_total_relation_size on the parent returns the
        // parent own (empty) size. Every figure here has to be summed across the eight
        // partitions - the first version of this reported a NEGATIVE row size, which is
        // what sent me looking.
        Long tableBytes = db.sql("""
                        SELECT COALESCE(SUM(pg_table_size(c.oid)), 0)::bigint
                          FROM pg_class c
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind = 'r'
                           AND c.relname LIKE 'prompt_cache%'
                        """)
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(30));

        // Matched on the index DEFINITION, not its name. On a partitioned table the
        // per-partition indexes are auto-named (prompt_cache_p3_embedding_idx), so only
        // the parent carries 'hnsw' - and the parent is a partitioned index with a size
        // of zero. The first version matched on name and reported 0 bytes for the index
        // that drives 439 GiB and the entire shard count.
        Long hnswBytes = db.sql("""
                        SELECT COALESCE(SUM(pg_relation_size(i.indexrelid)), 0)::bigint
                          FROM pg_index i
                          JOIN pg_class c ON c.oid = i.indexrelid
                         WHERE c.relkind = 'i'
                           AND pg_get_indexdef(i.indexrelid) ILIKE '%USING hnsw%'
                        """)
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(30));

        Long indexBytes = db.sql("""
                        SELECT COALESCE(SUM(pg_indexes_size(c.oid)), 0)::bigint
                          FROM pg_class c
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind = 'r'
                           AND c.relname LIKE 'prompt_cache%'
                        """)
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(30));

        long totalBytes = tableBytes + indexBytes;

        assertThat(rows).isEqualTo((long) ROWS);
        record(String.format(Locale.ROOT,
                "postgres row (heap + toast, no indexes): %.0f bytes = %.2f KiB   [plan ASSUMES 4.00 KiB]",
                tableBytes / (double) rows, tableBytes / (double) rows / 1024));
        record(String.format(Locale.ROOT,
                "postgres HNSW index entry: %.0f bytes = %.2f KiB   [plan ASSUMES 2.30 KiB]",
                hnswBytes / (double) rows, hnswBytes / (double) rows / 1024));
        record(String.format(Locale.ROOT,
                "postgres all indexes per row: %.0f bytes = %.2f KiB  (HNSW is %.0f%% of it)",
                indexBytes / (double) rows, indexBytes / (double) rows / 1024,
                100.0 * hnswBytes / Math.max(1, indexBytes)));
        record(String.format(Locale.ROOT,
                "postgres TOTAL per row incl. every index: %.0f bytes = %.2f KiB",
                totalBytes / (double) rows, totalBytes / (double) rows / 1024));
    }

    // ------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("3. Tier-2 vector search latency - replaces the ASSUMED ~100 ms")
    void searchLatency() {
        List<Long> timings = new ArrayList<>(SEARCHES);
        for (int i = 0; i < SEARCHES; i++) {
            Embedding query = model.embed("I forgot my password, how do I get back in? " + i);
            long start = System.nanoTime();
            db.sql("""
                    SELECT id, response, -(embedding <#> CAST(:q AS vector)) AS similarity
                      FROM prompt_cache
                     WHERE tenant_id = 'acme' AND model_id = 'gpt-4o-mini'
                       AND embedding_model = 'all-MiniLM-L6-v2' AND expires_at > now()
                     ORDER BY embedding <#> CAST(:q AS vector)
                     LIMIT 1
                    """)
                    .bind("q", query.toPgVector())
                    .fetch().one().block(Duration.ofSeconds(30));
            timings.add(System.nanoTime() - start);
        }
        Collections.sort(timings);

        double mean = timings.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        Long actualRows = db.sql("SELECT count(*) FROM prompt_cache")
                .map(row -> row.get(0, Long.class)).one().block(Duration.ofSeconds(30));
        record(String.format(Locale.ROOT,
                "vector search over %d rows: mean %.2f ms, p50 %.2f ms, p95 %.2f ms  (SQL only, no embedding)",
                actualRows, mean, percentile(timings, 50), percentile(timings, 95)));
        record(String.format(Locale.ROOT,
                "Tier-2 end to end (measured embed + measured search): ~%.2f ms",
                mean + embeddingMeanMillis));
    }

    // ------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("4. Redis entry size - replaces the ASSUMED 2 KiB, and closes A16")
    void redisEntrySize() {
        // The exact shape Tier1Cache writes: a hash-tagged key, and the CachedCompletion
        // JSON with its abbreviated field names.
        for (int i = 0; i < ROWS; i++) {
            String key = "{tenant:acme}:c1:" + String.format("%064x", i);
            String value = String.format(
                    "{\"v\":1,\"c\":\"%s\",\"m\":\"gpt-4o-mini\",\"pt\":22,\"ct\":48,"
                    + "\"p\":\"openai\",\"ts\":1763049600000}", response(i));
            redis.opsForValue().set(key, value, Duration.ofHours(1)).block(Duration.ofSeconds(10));
        }

        Long dbSize = redis.execute(c -> c.serverCommands().dbSize())
                .blockFirst(Duration.ofSeconds(10));
        // INFO comes back as Properties, not as the newline-delimited text redis-cli
        // prints. The first version called toString() and split on newlines, matched
        // nothing, and reported zero - the assertion below is what caught it.
        java.util.Properties info = redis.execute(c -> c.serverCommands().info("memory"))
                .blockFirst(Duration.ofSeconds(10));

        long usedMemory = Long.parseLong(info.getProperty("used_memory", "0"));
        long usedDataset = Long.parseLong(info.getProperty("used_memory_dataset", "0"));

        assertThat(dbSize).isNotNull();
        assertThat(usedMemory).isPositive();
        record(String.format(Locale.ROOT,
                "redis: used_memory %d bytes over %d keys = %.0f bytes = %.2f KiB per entry"
                + "   [plan ASSUMES 2.00 KiB]",
                usedMemory, dbSize, usedMemory / (double) dbSize,
                usedMemory / (double) dbSize / 1024));
        if (usedDataset > 0) {
            // used_memory_dataset excludes the fixed server overhead, so it is the figure
            // that actually scales with key count - the one a capacity plan needs.
            record(String.format(Locale.ROOT,
                    "redis: used_memory_dataset %d bytes over %d keys = %.0f bytes = %.2f KiB"
                    + " per entry  <- EXCLUDES server baseline, use THIS one",
                    usedDataset, dbSize, usedDataset / (double) dbSize,
                    usedDataset / (double) dbSize / 1024));
        }
    }

    // ------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("5. print everything, for pasting into the capacity plan")
    void summary() {
        System.out.println();
        System.out.println("=".repeat(78));
        System.out.println("  MEASURED on " + Instant.now() + " - "
                + Runtime.getRuntime().availableProcessors() + " available processors");
        System.out.println("=".repeat(78));
        RESULTS.forEach(line -> System.out.println("  " + line));
        System.out.println("=".repeat(78));
        System.out.println();

        assertThat(RESULTS).as("every measurement ran").hasSizeGreaterThanOrEqualTo(7);
    }
}
