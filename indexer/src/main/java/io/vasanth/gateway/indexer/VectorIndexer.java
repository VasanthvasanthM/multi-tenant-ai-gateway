package io.vasanth.gateway.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Mono;

/**
 * Writes completions into the Tier-2 vector store.
 *
 * <h2>Why this is a separate service</h2>
 *
 * <p>HNSW insertion is expensive and write-amplifying. If the gateway did it inline, every
 * cache <em>miss</em> would pay index-maintenance cost on the user's critical path - so a
 * miss gets slower precisely when the system is already missing, which is when it can
 * least afford it. Moving writes here means the read path never pays for the write path,
 * and the two scale independently.
 *
 * <h2>The embedding arrives with the event</h2>
 *
 * <p>This service does not recompute it. The gateway produced the vector during its
 * lookup, so recomputing would spend the same ~6 ms of CPU twice for an identical result.
 * The embedding module is still a dependency, but only for offline backfill after a model
 * change.
 *
 * <h2>Schema ownership</h2>
 *
 * <p>This service owns writes to {@code prompt_cache}; the gateway only reads it. The
 * migration currently lives in the gateway module because that is the service which
 * cannot start without the table. In a deployment where the two are released
 * independently, the migration belongs here and the gateway becomes a pure reader - see
 * {@code docs/adr/0010-schema-ownership.md}.
 */
@Component
public class VectorIndexer {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexer.class);

    /**
     * Upsert, not insert.
     *
     * <p>Kafka redelivery would otherwise create a duplicate row with the same prompt and
     * the same vector. Duplicates are not merely wasteful: nearest-neighbour search would
     * return several copies of one answer, and any later analysis of what the cache
     * contains would be inflated.
     *
     * <p>The CAST syntax is required - {@code ::vector} cannot be used because Spring's
     * named-parameter parser reads {@code :} as the start of a parameter name and mangles
     * the statement before Postgres sees it.
     */
    private static final String UPSERT = """
            INSERT INTO prompt_cache (
                tenant_id, cache_key, model_id, embedding_model, prompt_text,
                embedding, response, prompt_tokens, completion_tokens, provider,
                created_at, expires_at)
            VALUES (
                :tenantId, :cacheKey, :modelId, :embeddingModel, :promptText,
                CAST(:embedding AS vector), :response, :promptTokens, :completionTokens,
                :provider, :createdAt, :expiresAt)
            ON CONFLICT DO NOTHING
            """;

    private final DatabaseClient db;
    private final Counter indexed;
    private final Counter skipped;
    private final Timer writeTimer;

    public VectorIndexer(DatabaseClient db, MeterRegistry meterRegistry) {
        this.db = db;
        this.indexed = Counter.builder("indexer.vectors.written").register(meterRegistry);
        this.skipped = Counter.builder("indexer.vectors.skipped").register(meterRegistry);
        // Watched closely: HNSW insert time grows with index size, and this is the metric
        // that reveals when the index needs partitioning or rebuilding - long before the
        // read path shows any symptom.
        this.writeTimer = Timer.builder("indexer.vectors.write.duration")
                .publishPercentileHistogram().register(meterRegistry);
    }

    public Mono<Void> index(GatewayEvents.CacheWriteEvent event) {
        long startNanos = System.nanoTime();

        return db.sql(UPSERT)
                .bind("tenantId", event.tenantId())
                .bind("cacheKey", event.cacheKey())
                .bind("modelId", event.modelId())
                .bind("embeddingModel", event.embeddingModel())
                .bind("promptText", event.promptText())
                .bind("embedding", event.embedding())
                .bind("response", event.response())
                .bind("promptTokens", event.promptTokens())
                .bind("completionTokens", event.completionTokens())
                .bind("provider", event.provider())
                .bind("createdAt", event.createdAt())
                .bind("expiresAt", event.expiresAt())
                .fetch()
                .rowsUpdated()
                .doOnNext(rows -> {
                    writeTimer.record(
                            java.time.Duration.ofNanos(System.nanoTime() - startNanos));
                    if (rows == 0L) {
                        skipped.increment();
                    } else {
                        indexed.increment();
                    }
                })
                .then();
    }
}
