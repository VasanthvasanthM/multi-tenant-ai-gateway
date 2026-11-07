package io.vasanth.gateway.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.shard.ShardedDatabase;
import reactor.core.publisher.Mono;

/**
 * Reads {@code tenant_usage_rollup}.
 *
 * <h2>A cross-service read, and the reasoning for it</h2>
 *
 * <p>The metering service OWNS this table - it is the only writer. The gateway queries
 * it read-only, which is a boundary worth being explicit about rather than quiet.
 *
 * <p>The alternative was a second authenticated HTTP surface on the metering service,
 * and that was judged worse: the gateway is already the only service tenants
 * authenticate against, and a second tenant-facing edge means a second place to get API
 * key handling, tenant resolution and error mapping right. Keeping the tenant-facing
 * edge singular is the actual argument; the read-only query against an aggregate table
 * is the price.
 *
 * <p>It is also read through {@link ShardedDatabase#forRead(TenantId)}, so it follows
 * the same shard key and the same replica preference as the Tier-2 cache. A dashboard
 * query is exactly the workload a read replica is for: nobody is harmed by a figure
 * that is a second stale.
 */
@Component
public class SqlUsageQueryService implements UsageQueryService {

    private static final Logger log = LoggerFactory.getLogger(SqlUsageQueryService.class);

    /**
     * Upper bound on the window.
     *
     * <p>90 days at one row per tenant per hour is 2,160 rows - trivial. The bound
     * exists because the parameter is caller-controlled: without it, {@code ?hours=1e9}
     * is a full scan of the table on a request anyone can make, which is a
     * denial-of-service with a query string.
     */
    public static final int MAX_HOURS = 24 * 90;

    private static final String SUMMARY_SQL = """
            SELECT coalesce(sum(request_count), 0)     AS requests,
                   coalesce(sum(cache_hit_count), 0)   AS cache_hits,
                   coalesce(sum(prompt_tokens), 0)     AS prompt_tokens,
                   coalesce(sum(completion_tokens), 0) AS completion_tokens,
                   coalesce(sum(upstream_tokens), 0)   AS upstream_tokens
              FROM tenant_usage_rollup
             WHERE tenant_id = :tenantId
               AND period_hour >= :from
            """;

    private static final String SERIES_SQL = """
            SELECT period_hour,
                   request_count,
                   cache_hit_count,
                   prompt_tokens,
                   completion_tokens,
                   upstream_tokens
              FROM tenant_usage_rollup
             WHERE tenant_id = :tenantId
               AND period_hour >= :from
             ORDER BY period_hour
            """;

    private final ShardedDatabase database;
    private final Clock clock;

    public SqlUsageQueryService(ShardedDatabase database, Clock clock) {
        this.database = database;
        this.clock = clock;
    }

    @Override
    public Mono<UsageSummary> summary(TenantId tenant, int hours) {
        int window = clampWindow(hours);
        Instant to = Instant.now(clock);
        Instant from = to.minus(Duration.ofHours(window));

        return database.forRead(tenant)
                .sql(SUMMARY_SQL)
                .bind("tenantId", tenant.value())
                .bind("from", from)
                .map(row -> new UsageSummary(
                        from,
                        to,
                        // coalesce in the SQL means these are never null, but sum()
                        // returns numeric and the driver may hand back a BigDecimal or
                        // a Long depending on the column - asNumber keeps that from
                        // being a ClassCastException at runtime.
                        asLong(row.get("requests")),
                        asLong(row.get("cache_hits")),
                        asLong(row.get("prompt_tokens")),
                        asLong(row.get("completion_tokens")),
                        asLong(row.get("upstream_tokens"))))
                .one()
                // A tenant with no rows at all: an empty window, not an error. A new
                // tenant opening the dashboard before their first request is the
                // common case here.
                .defaultIfEmpty(UsageSummary.empty(from, to))
                .onErrorResume(error -> {
                    // The dashboard is not the request path. A failure here must not
                    // produce a 500 that looks like the gateway is down - it shows an
                    // empty window and logs the cause.
                    log.warn("usage summary query failed for tenant {}: {}",
                            tenant, error.toString());
                    return Mono.just(UsageSummary.empty(from, to));
                });
    }

    @Override
    public Mono<List<UsagePoint>> series(TenantId tenant, int hours) {
        int window = clampWindow(hours);
        Instant from = Instant.now(clock).minus(Duration.ofHours(window));

        return database.forRead(tenant)
                .sql(SERIES_SQL)
                .bind("tenantId", tenant.value())
                .bind("from", from)
                .map(row -> new UsagePoint(
                        row.get("period_hour", Instant.class),
                        asLong(row.get("request_count")),
                        asLong(row.get("cache_hit_count")),
                        asLong(row.get("prompt_tokens")),
                        asLong(row.get("completion_tokens")),
                        asLong(row.get("upstream_tokens"))))
                .all()
                .collectList()
                .onErrorResume(error -> {
                    log.warn("usage series query failed for tenant {}: {}",
                            tenant, error.toString());
                    return Mono.just(List.of());
                });
    }

    /**
     * Bounds a caller-supplied window.
     *
     * <p>Clamps rather than rejecting. A dashboard asking for more history than exists
     * is not an error - it should get everything there is, which is what a clamp does.
     * Rejecting would mean the UI has to know the limit.
     */
    private static int clampWindow(int hours) {
        if (hours < 1) {
            return 1;
        }
        return Math.min(hours, MAX_HOURS);
    }

    /** Null-safe numeric widening; sum() returns BigDecimal on some drivers. */
    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
