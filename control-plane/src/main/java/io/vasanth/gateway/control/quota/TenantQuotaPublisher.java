package io.vasanth.gateway.control.quota;

import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Publishes {@code tenants.quota_tokens} into Redis, where the gateway reads it.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>The gateway resolves a tenant's token budget from {@code {tenant:x}:quota} in Redis
 * and falls back to one configured default when the key is absent. Nothing wrote that key,
 * so <b>every tenant got the same hard-coded number</b> no matter what the control plane's
 * database said - which made per-tenant budgets a feature in name only.
 *
 * <h2>Why a periodic reconcile rather than only an event</h2>
 *
 * <p>A change event is the fast path and belongs in the provisioning saga. It is not
 * sufficient on its own: Redis is a cache with no durability guarantee here, so a restart,
 * an eviction or a failover silently drops keys, and a tenant would then quietly revert to
 * the default limit with nothing reporting it. A full reconcile makes the steady state
 * self-healing - the database is the system of record and Redis converges on it.
 *
 * <p>The cost is trivial at any plausible tenant count: one indexed scan and one SET per
 * tenant, once a minute, off the request path entirely.
 *
 * <h2>Why the keys expire</h2>
 *
 * <p>Each key is written with a TTL comfortably longer than the reconcile interval. A
 * tenant deleted from the database therefore stops being republished and its key lapses on
 * its own, so a deletion cannot leave a stale entitlement behind. Without the TTL, removing
 * a tenant would need an explicit Redis delete that could be missed - and the failure mode
 * is a deleted customer keeping their budget.
 */
@Component
public class TenantQuotaPublisher {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaPublisher.class);

    private static final String SELECT_QUOTAS = """
            SELECT tenant_id, quota_tokens
              FROM tenants
             WHERE status = 'ACTIVE'
            """;

    /**
     * How long a published limit survives without being refreshed.
     *
     * <p>Four times the reconcile interval, so three consecutive failed reconciles are
     * survivable before a tenant reverts to the default. Shorter would make a brief
     * control-plane outage change every tenant's entitlement; much longer would leave a
     * deleted tenant entitled for too long.
     */
    static final Duration KEY_TTL = Duration.ofMinutes(4);

    static final Duration RECONCILE_INTERVAL = Duration.ofMinutes(1);

    private final DatabaseClient db;
    private final ReactiveStringRedisTemplate redis;
    private final Counter published;
    private final Counter failures;

    public TenantQuotaPublisher(
            DatabaseClient db, ReactiveStringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.db = db;
        this.redis = redis;
        this.published = Counter.builder("control_plane.quota.published")
                .register(meterRegistry);
        // Separate, because a reconcile that stops working is invisible otherwise: the
        // gateway keeps serving on keys that are still within their TTL, and the first
        // symptom is every tenant silently reverting to the default minutes later.
        this.failures = Counter.builder("control_plane.quota.publish_failures")
                .register(meterRegistry);
    }

    /**
     * Publishes one tenant's limit immediately.
     *
     * <p>For the provisioning saga and for a quota change, so a new or updated limit takes
     * effect without waiting for the next reconcile.
     */
    public Mono<Void> publish(String tenantId, long quotaTokens) {
        return redis.opsForValue()
                .set(quotaKey(tenantId), Long.toString(quotaTokens), KEY_TTL)
                .doOnSuccess(stored -> published.increment())
                .onErrorResume(error -> {
                    failures.increment();
                    // Never fails the caller. A saga step that could not warm a cache must
                    // not roll back a tenant that was successfully created - the reconcile
                    // below will publish it within the minute.
                    log.warn("failed to publish quota for tenant {}: {}",
                            tenantId, error.toString());
                    return Mono.empty();
                })
                .then();
    }

    /** Republishes every active tenant. Never signals an error. */
    public Mono<Long> reconcile() {
        return db.sql(SELECT_QUOTAS)
                .map((row, metadata) -> Map.entry(
                        row.get("tenant_id", String.class),
                        row.get("quota_tokens", Long.class)))
                .all()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .concatMap(entry -> publish(entry.getKey(), entry.getValue()).thenReturn(1L))
                .count()
                .doOnNext(count -> log.debug("republished {} tenant quota(s)", count))
                .onErrorResume(error -> {
                    failures.increment();
                    log.warn("quota reconcile failed: {}", error.toString());
                    return Mono.just(0L);
                });
    }

    /** Starts the periodic reconcile. */
    public reactor.core.Disposable start() {
        return Flux.interval(Duration.ZERO, RECONCILE_INTERVAL,
                        reactor.core.scheduler.Schedulers.boundedElastic())
                .concatMap(tick -> reconcile())
                .subscribe();
    }

    /**
     * The key the gateway reads.
     *
     * <p>Must match {@code TenantQuotaRegistry.quotaKey} exactly, including the cluster
     * hash tag. Two services agreeing on a key by coincidence is a bug waiting to happen,
     * so the shape is asserted on both sides.
     */
    static String quotaKey(String tenantId) {
        return "{tenant:" + tenantId + "}:quota";
    }
}
