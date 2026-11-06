package io.vasanth.gateway.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import io.vasanth.gateway.embedding.Embedding;
import io.vasanth.gateway.shard.ShardedDatabase;
import reactor.core.publisher.Mono;

/**
 * Tier 2: approximate nearest-neighbour lookup over prompt embeddings. Target p99 under
 * 15 ms.
 *
 * <h2>The operator sign</h2>
 *
 * <p>pgvector's {@code <#>} returns the <b>negative</b> inner product, so that smaller
 * means closer and it behaves like a distance. Cosine similarity for our unit vectors is
 * therefore {@code -(embedding <#> query)}. Getting that sign wrong does not throw - it
 * silently returns the <em>least</em> similar row, which presents as "the model is bad"
 * rather than "the query is wrong", and can survive a long time in a codebase.
 *
 * <h2>Why the threshold is applied in Java, not SQL</h2>
 *
 * <p>The query always returns its best candidate and the decision to serve happens here.
 * That is what makes shadow mode possible: the near-misses are exactly the interesting
 * data - they tell you what a lower threshold <em>would</em> have served - and a SQL-side
 * filter would discard them before anyone could look.
 *
 * <h2>Correctness over hit rate</h2>
 *
 * <p>A semantic cache can return a plausible answer to a different question. "How do I
 * cancel my order" and "How do I cancel my subscription" are close in embedding space and
 * opposite in consequence. Three guards, in order of importance:
 *
 * <ol>
 *   <li>A high default threshold (0.95), enforced at startup - the application refuses to
 *       boot below 0.90.
 *   <li>Shadow mode on by default: query, log, serve nothing, until a real false-hit rate
 *       has been measured on real traffic.
 *   <li>Never serve from a non-semantic embedding model. The hashing fallback matches
 *       surface form, and a surface-form near-match is not an answer to the same question.
 * </ol>
 */
@Component
public class Tier2SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(Tier2SemanticCache.class);

    /**
     * Returns the single best candidate.
     *
     * <p>Partition pruning on {@code tenant_id} confines this to one partition and one
     * HNSW index. {@code embedding_model} is matched exactly because similarity scores
     * computed across different models are meaningless - plausible-looking numbers with no
     * basis.
     */
    /*
     * ON THE CAST SYNTAX.
     *
     * The vector parameter is bound as text, and Postgres has no `vector <#> text`
     * operator, so it needs an explicit cast or the statement fails with "No operator
     * matches the given name and argument types".
     *
     * The idiomatic pgvector cast is `:query::vector` - but that cannot be used here.
     * Spring's named-parameter parser treats `:` as the start of a parameter name, so
     * `::vector` is read as a parameter called "vector" and the SQL is mangled before
     * Postgres ever sees it. `CAST(:query AS vector)` is the equivalent that survives the
     * parser, and it is standard SQL besides.
     */
    private static final String NEAREST_NEIGHBOUR_SQL = """
            SELECT id,
                   response,
                   model_id,
                   prompt_tokens,
                   completion_tokens,
                   provider,
                   created_at,
                   prompt_text,
                   -(embedding <#> CAST(:query AS vector)) AS similarity
              FROM prompt_cache
             WHERE tenant_id = :tenantId
               AND model_id = :modelId
               AND embedding_model = :embeddingModel
               AND expires_at > now()
             ORDER BY embedding <#> CAST(:query AS vector)
             LIMIT 1
            """;

    private static final String INSERT_SQL = """
            INSERT INTO prompt_cache (
                tenant_id, cache_key, model_id, embedding_model, prompt_text,
                embedding, response, prompt_tokens, completion_tokens, provider,
                created_at, expires_at)
            VALUES (
                :tenantId, :cacheKey, :modelId, :embeddingModel, :promptText,
                CAST(:embedding AS vector), :response, :promptTokens, :completionTokens, :provider,
                :createdAt, :expiresAt)
            """;

    private static final String RECORD_HIT_SQL = """
            UPDATE prompt_cache
               SET hit_count = hit_count + 1,
                   last_hit_at = now()
             WHERE tenant_id = :tenantId
               AND id = :id
            """;

    /**
     * Routes each tenant to its shard, and reads from a replica where one exists.
     *
     * <p>This replaced a single injected {@code DatabaseClient}. The shard key is
     * {@code tenantId} and every query here already carries it, so a request touches
     * exactly ONE shard and there is never a cross-shard nearest-neighbour search -
     * which is the property that makes the vector search affordable at scale
     * (ADR-0006). With no shards configured this wraps the one auto-configured client,
     * so an unsharded deployment behaves exactly as before.
     */
    private final ShardedDatabase database;
    private final GatewayProperties.Cache config;
    private final Clock clock;

    private final Counter hits;
    private final Counter misses;
    private final Counter nearMisses;
    private final Counter suppressed;
    private final Counter shadowed;
    private final Counter shadowWouldHave;
    private final Counter errors;
    private final Timer searchTimer;

    public Tier2SemanticCache(
            ShardedDatabase database,
            GatewayProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.database = database;
        this.config = properties.cache();
        this.clock = clock;

        String tier = CacheTier.TIER2_SEMANTIC.label();
        this.hits = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "hit").register(meterRegistry);
        this.misses = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "miss").register(meterRegistry);
        this.nearMisses = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "below_threshold").register(meterRegistry);
        this.shadowWouldHave = Counter.builder("gateway.cache.shadow_would_have_served")
                .register(meterRegistry);
        this.errors = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "error").register(meterRegistry);
        // A candidate that cleared the threshold but was withheld. Each gets its own
        // outcome so that EVERY lookup increments exactly one gateway.cache.lookup
        // counter - see the note on the miss counter in lookup(). Without these, a
        // suppressed or shadowed lookup was recorded as a plain miss, which is
        // indistinguishable from "nothing similar exists" and hides the two states a
        // reader most needs to tell apart during a Tier-2 rollout.
        this.suppressed = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "suppressed").register(meterRegistry);
        this.shadowed = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "shadow").register(meterRegistry);
        this.searchTimer = Timer.builder("gateway.cache.lookup.duration")
                .tag("tier", tier).publishPercentileHistogram().register(meterRegistry);
    }

    /**
     * Searches for a semantically similar cached answer.
     *
     * @param queryVector the L2-normalised embedding of the incoming prompt
     * @param semanticallyMeaningful whether the active embedding model produces semantic
     *     vectors; when false this never serves, regardless of score
     * @return a response when a candidate clears the threshold and shadow mode is off;
     *     empty for a miss, a near miss, shadow mode, or any database failure
     */
    public Mono<CompletionResponse> lookup(
            TenantId tenant,
            ModelId model,
            Embedding queryVector,
            String embeddingModelId,
            boolean semanticallyMeaningful) {

        if (!config.tier2Enabled()) {
            return Mono.empty();
        }

        long startNanos = System.nanoTime();

        // A READ, so a replica is fine: replica lag here produces a cache MISS, never
        // a wrong answer - the search does not find a new entry, the request falls
        // through to the provider, and the response is re-cached (ADR-0007).
        return database.forRead(tenant).sql(NEAREST_NEIGHBOUR_SQL)
                .bind("query", queryVector.toPgVector())
                .bind("tenantId", tenant.value())
                .bind("modelId", model.value())
                .bind("embeddingModel", embeddingModelId)
                .map(Candidate::from)
                .one()
                // Counts "nothing similar exists" ONLY, and deliberately sits BEFORE
                // the flatMap so it observes the query result rather than the decision.
                //
                // As `switchIfEmpty` after the flatMap it also fired for every empty
                // decision, so a below-threshold candidate scored BOTH below_threshold
                // and miss. One lookup must increment exactly ONE gateway.cache.lookup
                // counter, because the hit ratio is hits divided by the sum of them -
                // and Tier 1 counts that way, so counting differently here would make
                // one PromQL expression mean two things depending on the tier.
                .doOnSuccess(candidate -> {
                    if (candidate == null) {
                        misses.increment();
                    }
                })
                .flatMap(candidate -> decide(
                        candidate, tenant, startNanos, semanticallyMeaningful))
                .onErrorResume(error -> {
                    // Tier 2 is an optimisation, never a dependency. A database failure
                    // degrades the system to "slower and more expensive", not "down".
                    errors.increment();
                    log.warn("tier-2 lookup failed for tenant {}: {}", tenant, error.toString());
                    return Mono.empty();
                });
    }

    private Mono<CompletionResponse> decide(
            Candidate candidate,
            TenantId tenant,
            long startNanos,
            boolean semanticallyMeaningful) {

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        searchTimer.record(elapsed);

        if (candidate.similarity < config.similarityThreshold()) {
            nearMisses.increment();
            log.debug(
                    "tier-2 near miss for tenant {}: best similarity {} below threshold {}",
                    tenant, candidate.similarity, config.similarityThreshold());
            return Mono.empty();
        }

        if (!semanticallyMeaningful) {
            // The hashing fallback is lexical. A surface-form match is not an answer to
            // the same question, so it must never be served however high it scores.
            suppressed.increment();
            log.debug("tier-2 candidate suppressed: active embedding model is not semantic");
            return Mono.empty();
        }

        if (config.shadowMode()) {
            // The whole point of shadow mode: record what would have been served so the
            // false-hit rate can be measured on real traffic before anyone relies on it.
            shadowWouldHave.increment();
            shadowed.increment();
            log.info(
                    "tier-2 SHADOW would have served tenant={} similarity={} prompt=\"{}\"",
                    tenant, String.format("%.4f", candidate.similarity),
                    abbreviate(candidate.promptText));
            return Mono.empty();
        }

        hits.increment();
        recordHit(tenant, candidate.id).subscribe();
        return Mono.just(candidate.toResponse(elapsed));
    }

    /** Stores a completion for future semantic lookup. */
    public Mono<Void> store(
            CacheKey cacheKey,
            ModelId model,
            String promptText,
            Embedding embedding,
            String embeddingModelId,
            CompletionResponse response) {

        if (!config.tier2Enabled()) {
            return Mono.empty();
        }

        // Injected rather than Instant.now(): the expiry this computes is the thing a
        // TTL test needs to control, and a test that has to sleep to verify expiry is
        // both slow and flaky. Caught by ArchitectureRulesTest.noStaticNowCalls.
        Instant now = Instant.now(clock);

        // A WRITE, so always the primary. A write issued to a replica simply fails -
        // which at least fails loudly rather than silently losing a cache entry.
        return database.forWrite(cacheKey.tenantId()).sql(INSERT_SQL)
                .bind("tenantId", cacheKey.tenantId().value())
                .bind("cacheKey", cacheKey.hex())
                .bind("modelId", model.value())
                .bind("embeddingModel", embeddingModelId)
                .bind("promptText", promptText)
                .bind("embedding", embedding.toPgVector())
                .bind("response", response.content())
                .bind("promptTokens", response.usage().promptTokens())
                .bind("completionTokens", response.usage().completionTokens())
                .bind("provider", response.provider().value())
                .bind("createdAt", response.createdAt())
                .bind("expiresAt", now.plus(config.tier2Ttl()))
                .fetch()
                .rowsUpdated()
                .doOnError(error ->
                        log.warn("tier-2 store failed for {}: {}",
                                cacheKey.shortForm(), error.toString()))
                // A failed write must not fail a request whose answer already exists. The
                // caller has a valid response; losing the chance to cache it is strictly
                // less bad than discarding work the user already paid for.
                .onErrorResume(error -> Mono.empty())
                .then();
    }

    private Mono<Void> recordHit(TenantId tenant, long id) {
        return database.forWrite(tenant).sql(RECORD_HIT_SQL)
                .bind("tenantId", tenant.value())
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .onErrorResume(error -> {
                    // Pure telemetry. Never allowed to affect serving.
                    log.debug("tier-2 hit counter update failed: {}", error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    /** One row from the nearest-neighbour query. */
    private record Candidate(
            long id,
            String response,
            String modelId,
            long promptTokens,
            long completionTokens,
            String provider,
            Instant createdAt,
            String promptText,
            double similarity) {

        /**
         * Maps a row, tolerating nulls rather than unboxing them.
         *
         * <p>{@code Row.get(..., Long.class)} returns a boxed value that is null when the
         * column is. Assigning that straight into a {@code long} component unboxes it and
         * throws NPE - and because the caller wraps this in {@code onErrorResume}, the NPE
         * would be swallowed and reported as a cache miss. The cache would quietly stop
         * working with nothing in the logs explaining why.
         *
         * <p>The columns are NOT NULL in the schema today, so this is defence against a
         * future migration rather than a live bug. Defaulting is the right response: a
         * malformed row should degrade to a miss, which is what zero similarity
         * guarantees.
         */
        static Candidate from(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
            return new Candidate(
                    orDefault(row.get("id", Long.class), 0L),
                    orDefault(row.get("response", String.class), ""),
                    orDefault(row.get("model_id", String.class), ""),
                    orDefault(row.get("prompt_tokens", Long.class), 0L),
                    orDefault(row.get("completion_tokens", Long.class), 0L),
                    orDefault(row.get("provider", String.class), "unknown"),
                    orDefault(row.get("created_at", Instant.class), Instant.EPOCH),
                    orDefault(row.get("prompt_text", String.class), ""),
                    // Zero similarity can never clear the >= 0.90 threshold, so an
                    // unreadable row is structurally incapable of being served.
                    orDefault(row.get("similarity", Double.class), 0.0d));
        }

        private static <T> T orDefault(T value, T fallback) {
            return value == null ? fallback : value;
        }

        CompletionResponse toResponse(Duration servedIn) {
            return new CompletionResponse(
                    response,
                    ModelId.of(modelId),
                    TokenUsage.cacheHit(promptTokens),
                    CacheTier.TIER2_SEMANTIC,
                    ProviderId.CACHE,
                    createdAt,
                    servedIn);
        }
    }
}
