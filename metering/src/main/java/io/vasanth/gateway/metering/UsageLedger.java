package io.vasanth.gateway.metering;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Mono;

/**
 * Writes usage to the ledger and maintains the hourly rollup.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Kafka delivers at least once. A consumer that crashes between processing an event and
 * committing its offset will see that event again - which is normal operation, not an edge
 * case. The {@code (tenant_id, request_id)} unique constraint plus
 * {@code ON CONFLICT DO NOTHING} makes redelivery a no-op.
 *
 * <p>Crucially, the rollup is only incremented when the ledger insert actually inserted.
 * Incrementing unconditionally would make the aggregate drift upward on every redelivery
 * while the ledger stayed correct - and a rollup that disagrees with the ledger it
 * summarises is worse than no rollup, because it looks authoritative.
 */
@Component
public class UsageLedger {

    private static final Logger log = LoggerFactory.getLogger(UsageLedger.class);

    private static final String INSERT_LEDGER = """
            INSERT INTO usage_ledger (
                tenant_id, request_id, model_id, served_by, provider,
                prompt_tokens, completion_tokens, total_tokens,
                billable_upstream, latency_millis, occurred_at)
            VALUES (
                :tenantId, :requestId, :modelId, :servedBy, :provider,
                :promptTokens, :completionTokens, :totalTokens,
                :billableUpstream, :latencyMillis, :occurredAt)
            ON CONFLICT (tenant_id, request_id) DO NOTHING
            """;

    /**
     * Upsert into the rollup.
     *
     * <p>{@code ON CONFLICT DO UPDATE} with {@code +} rather than read-modify-write: two
     * consumer instances processing different events for the same tenant in the same hour
     * would otherwise read the same value and one increment would be lost. The database
     * does the arithmetic under its own row lock.
     */
    private static final String UPSERT_ROLLUP = """
            INSERT INTO tenant_usage_rollup (
                tenant_id, period_hour, request_count, cache_hit_count,
                prompt_tokens, completion_tokens, upstream_tokens, updated_at)
            VALUES (
                :tenantId, :periodHour, 1, :cacheHit,
                :promptTokens, :completionTokens, :upstreamTokens, now())
            ON CONFLICT (tenant_id, period_hour) DO UPDATE SET
                request_count     = tenant_usage_rollup.request_count + 1,
                cache_hit_count   = tenant_usage_rollup.cache_hit_count + :cacheHit,
                prompt_tokens     = tenant_usage_rollup.prompt_tokens + :promptTokens,
                completion_tokens = tenant_usage_rollup.completion_tokens + :completionTokens,
                upstream_tokens   = tenant_usage_rollup.upstream_tokens + :upstreamTokens,
                updated_at        = now()
            """;

    private final DatabaseClient db;
    private final TransactionalOperator transactions;
    private final Counter recorded;
    private final Counter duplicates;

    public UsageLedger(
            DatabaseClient db, TransactionalOperator transactions, MeterRegistry meterRegistry) {
        this.db = db;
        this.transactions = transactions;
        this.recorded = Counter.builder("metering.usage.recorded").register(meterRegistry);
        // Tracked explicitly. A steady trickle is healthy - it proves idempotency is
        // doing its job. A sudden spike means a consumer is crash-looping and
        // reprocessing, which is worth investigating before the lag becomes visible.
        this.duplicates = Counter.builder("metering.usage.duplicate").register(meterRegistry);
    }

    /**
     * Records one usage event.
     *
     * <p>Ledger insert and rollup update share a transaction, so the aggregate can never
     * reflect a request the ledger does not contain.
     */
    public Mono<Void> record(GatewayEvents.UsageEvent event) {
        long upstreamTokens = event.billableUpstream() ? event.totalTokens() : 0L;
        int cacheHit = event.billableUpstream() ? 0 : 1;
        Instant periodHour = event.occurredAt().truncatedTo(ChronoUnit.HOURS);

        Mono<Void> work = db.sql(INSERT_LEDGER)
                .bind("tenantId", event.tenantId())
                .bind("requestId", event.requestId())
                .bind("modelId", event.modelId())
                .bind("servedBy", event.servedBy())
                .bind("provider", event.provider())
                .bind("promptTokens", event.promptTokens())
                .bind("completionTokens", event.completionTokens())
                .bind("totalTokens", event.totalTokens())
                .bind("billableUpstream", event.billableUpstream())
                .bind("latencyMillis", event.latencyMillis())
                .bind("occurredAt", event.occurredAt())
                .fetch()
                .rowsUpdated()
                .flatMap(rows -> {
                    if (rows == 0L) {
                        // ON CONFLICT DO NOTHING fired: this event has already been
                        // recorded. Skipping the rollup here is what keeps the aggregate
                        // consistent with the ledger.
                        duplicates.increment();
                        log.debug("duplicate usage event ignored: tenant={} request={}",
                                event.tenantId(), event.requestId());
                        return Mono.empty();
                    }
                    recorded.increment();
                    return updateRollup(event, periodHour, cacheHit, upstreamTokens);
                });

        return transactions.transactional(work).then();
    }

    private Mono<Void> updateRollup(
            GatewayEvents.UsageEvent event, Instant periodHour, int cacheHit, long upstreamTokens) {

        return db.sql(UPSERT_ROLLUP)
                .bind("tenantId", event.tenantId())
                .bind("periodHour", periodHour)
                .bind("cacheHit", cacheHit)
                .bind("promptTokens", event.promptTokens())
                .bind("completionTokens", event.completionTokens())
                .bind("upstreamTokens", upstreamTokens)
                .fetch()
                .rowsUpdated()
                .then();
    }
}
