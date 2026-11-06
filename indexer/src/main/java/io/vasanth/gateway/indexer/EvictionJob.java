package io.vasanth.gateway.indexer;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

/**
 * Removes expired cache entries.
 *
 * <h2>Why a sweep rather than TTL at read time</h2>
 *
 * <p>Filtering on {@code expires_at > now()} in the read query - which the gateway also
 * does - keeps stale rows from being served, but never removes them. The table would grow
 * without bound, and HNSW insert and search cost both grow with index size, so the whole
 * Tier-2 latency budget degrades over time for data nobody can use.
 *
 * <h2>Why deletes are batched</h2>
 *
 * <p>A single {@code DELETE WHERE expires_at < now()} on a large table takes a lock for
 * the duration and writes one enormous WAL record, which stalls replication and competes
 * with live reads. Deleting in bounded batches with a pause between them keeps each
 * transaction short, so the sweep is invisible to serving traffic even when it has a lot
 * to remove.
 */
@Component
public class EvictionJob {

    private static final Logger log = LoggerFactory.getLogger(EvictionJob.class);

    /**
     * Rows removed per statement.
     *
     * <p>Small enough that each transaction is brief and holds few locks; large enough
     * that a backlog is cleared in a reasonable number of rounds. The subquery with
     * {@code LIMIT} is what bounds it - a plain DELETE has no such facility.
     */
    private static final int BATCH_SIZE = 500;

    /** Caps one run, so a huge backlog cannot monopolise the database indefinitely. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    private static final String DELETE_BATCH = """
            DELETE FROM prompt_cache
             WHERE (tenant_id, id) IN (
                   SELECT tenant_id, id
                     FROM prompt_cache
                    WHERE expires_at < now()
                    LIMIT :batchSize)
            """;

    private final DatabaseClient db;
    private final Counter evicted;
    private final boolean enabled;

    public EvictionJob(
            DatabaseClient db,
            MeterRegistry meterRegistry,
            @Value("${gateway.indexer.eviction-enabled:true}") boolean enabled) {
        this.db = db;
        this.enabled = enabled;
        this.evicted = Counter.builder("indexer.entries.evicted").register(meterRegistry);
    }

    /**
     * Runs every five minutes.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next run starts five minutes after
     * the previous one <em>finishes</em>. With fixedRate, a sweep that takes longer than
     * the interval would have runs overlapping and competing for the same rows.
     */
    @Scheduled(fixedDelay = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES,
            initialDelay = 1)
    public void sweep() {
        if (!enabled) {
            return;
        }

        // Long, not long: block() returns null if the stream completes empty, and
        // unboxing that would NPE inside a scheduled job where nothing catches it.
        Long total = deleteBatches()
                .reduce(0L, Long::sum)
                // The scheduler calls this on a plain thread, not from a reactive
                // pipeline, so blocking here is correct - this is not an event loop. It
                // is also why the job lives in its own service rather than the gateway,
                // where no such thread exists.
                .block(Duration.ofMinutes(4));

        if (total != null && total > 0L) {
            evicted.increment(total);
            log.info("evicted {} expired cache entries", total);
        }
    }

    private reactor.core.publisher.Flux<Long> deleteBatches() {
        return reactor.core.publisher.Flux.range(0, MAX_BATCHES_PER_RUN)
                .concatMap(round -> deleteOneBatch())
                // Stops as soon as a batch removes nothing: the backlog is clear.
                .takeWhile(removed -> removed > 0L);
    }

    private Mono<Long> deleteOneBatch() {
        return db.sql(DELETE_BATCH)
                .bind("batchSize", BATCH_SIZE)
                .fetch()
                .rowsUpdated()
                .onErrorResume(error -> {
                    // Eviction failing is a capacity problem, not a correctness one -
                    // stale rows are still filtered out at read time. It must never take
                    // the service down.
                    log.warn("eviction batch failed: {}", error.toString());
                    return Mono.just(0L);
                });
    }
}
