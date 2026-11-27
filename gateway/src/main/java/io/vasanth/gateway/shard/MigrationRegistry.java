package io.vasanth.gateway.shard;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Which tenants are mid-migration, and how their traffic is being handled.
 *
 * <h2>The window this closes</h2>
 *
 * <p>A shard migration copies a tenant's rows while the source keeps serving. Anything
 * written between the copy starting and the cutover lands on the <b>old</b> shard and is
 * never copied - and after the cutover nothing reads there. For {@code prompt_cache} that
 * is a lost cache entry; for {@code usage_ledger} it is a billing record that silently
 * stops existing.
 *
 * <h2>Two strategies, and when each applies</h2>
 *
 * <p>{@link MigrationState.Mode#DUAL} is the production answer: the tenant is served
 * normally and every write goes to <b>both</b> shards, so the target is already current
 * when the ring changes. Nobody is refused.
 *
 * <p>{@link MigrationState.Mode#PAUSE} refuses the tenant for the seconds a copy takes.
 * It is cruder, and it stays because it needs nothing from the write path - which is
 * exactly the situation for the tables below.
 *
 * <h2>What dual-write does NOT cover, and why</h2>
 *
 * <p>Dual-write works wherever writes route through {@link ShardedDatabase} - today that
 * is {@code prompt_cache}, written by the gateway. It does <b>not</b> cover
 * {@code usage_ledger} or {@code audit_log}, because those are written by the metering and
 * audit services, which hold a single {@code DatabaseClient} and do not route by shard at
 * all. Giving them dual-write first requires giving them shard routing, which means
 * lifting {@link ShardedDatabase} into a module they can depend on. Until then, a
 * migration that must protect those tables still needs {@code PAUSE} - or the
 * copy-again-after-cutover procedure in the runbook.
 *
 * <h2>Why Redis, and why a poll</h2>
 *
 * <p>The state has to reach every pod within seconds; a config change needs a rollout,
 * which takes minutes and would outlast the copy it protects. One small {@code HGETALL}
 * runs on a background interval and swaps an immutable map, so the request path costs a
 * map lookup on a volatile field rather than a Redis round trip.
 *
 * <h2>Two independent safety valves</h2>
 *
 * <p><b>The Redis key expires.</b> A migration that is abandoned - or an operator whose
 * terminal died - stops affecting traffic rather than blocking a customer forever.
 *
 * <p><b>A stale local view expires too.</b> If refreshes stop succeeding, this registry
 * honours its last known state only up to {@link #MAX_STALENESS}; past that it reports
 * nothing. Holding a pause on an unreachable Redis would turn a cache outage into a tenant
 * outage - the same fail-open reasoning as the rate limiter. The counter and the WARN are
 * what stop it being silent.
 */
@Component
public class MigrationRegistry {

    private static final Logger log = LoggerFactory.getLogger(MigrationRegistry.class);

    /**
     * Redis hash of tenant id to state.
     *
     * <p>A hash rather than one key per tenant, so a refresh is one {@code HGETALL} of a
     * tiny structure instead of a {@code SCAN} across a keyspace holding millions of cache
     * entries. The TTL then applies to the whole hash, which is the behaviour wanted: one
     * expiry ends every migration left behind by an abandoned run.
     */
    public static final String STATE_KEY = "gateway:migrating";

    /** How often the state is re-read. Also the worst-case delay before it takes hold. */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(2);

    /**
     * How long state survives without a successful refresh.
     *
     * <p>Thirty refresh intervals. A copy takes seconds, so a view this old means Redis has
     * been unreachable for a minute - at which point refusing a tenant's traffic, or
     * writing every row twice, is worse than the window it was protecting.
     */
    static final Duration MAX_STALENESS = Duration.ofSeconds(60);

    private final ReactiveStringRedisTemplate redis;
    private final Counter refreshFailures;
    private final Counter staleExpiries;
    private final Counter malformedEntries;

    /** Immutable, swapped wholesale. Readers never synchronise. */
    private volatile Map<String, MigrationState> states = Map.of();

    private volatile long lastSuccessNanos = System.nanoTime();

    /** So the stale WARN is logged on the transition, not on every request. */
    private final AtomicBoolean staleLogged = new AtomicBoolean();

    private volatile Disposable subscription;

    public MigrationRegistry(
            ReactiveStringRedisTemplate redis, ShardedDatabase database, MeterRegistry meters) {
        this.redis = redis;

        Gauge.builder("gateway.migration.active_tenants", this, r -> r.states.size())
                .description("Tenants currently mid-migration")
                .register(meters);

        // A refresh that cannot run means this view is STALE, and a stale view behind a
        // healthy-looking gauge is the failure the replica lag monitor exists to warn about.
        this.refreshFailures = Counter.builder("gateway.migration.refresh_failures")
                .description("Failed reads of the migration state")
                .register(meters);

        // Each time state was abandoned because the view got too old. A real correctness
        // event: a tenant that should have been paused is writing, or a dual-write stopped.
        this.staleExpiries = Counter.builder("gateway.migration.stale_expiries")
                .description("Migration state abandoned because the view exceeded its bound")
                .register(meters);

        // An entry nobody can parse is an operator typo, and it silently does nothing.
        this.malformedEntries = Counter.builder("gateway.migration.malformed_entries")
                .description("Unparseable migration state entries")
                .register(meters);

        // Hands the database its lookup, the same way ReplicaLagMonitor hands it the
        // replica health predicate. A function rather than a back-reference, so the two
        // do not form a construction cycle.
        database.useMigrationState(this::stateFor);
    }

    /**
     * This tenant's migration state, or null when it is not migrating.
     *
     * <p>On the request path, so it does no I/O: a volatile read and, only when something
     * is actually migrating, a map lookup.
     */
    public MigrationState stateFor(TenantId tenant) {
        Map<String, MigrationState> current = states;
        if (current.isEmpty()) {
            // The normal case by an enormous margin - no migration running - and it costs
            // one volatile read with no staleness arithmetic.
            return null;
        }

        if (System.nanoTime() - lastSuccessNanos > MAX_STALENESS.toNanos()) {
            if (staleLogged.compareAndSet(false, true)) {
                staleExpiries.increment();
                log.warn(
                        "migration state is older than {} and is being ignored;"
                                + " {} tenant(s) are no longer paused or dual-writing."
                                + " Redis is unreachable. A migration in progress is now at"
                                + " risk of losing writes.",
                        MAX_STALENESS, current.size());
            }
            return null;
        }

        return current.get(tenant.value());
    }

    /** Whether this tenant's requests must be refused outright. */
    public boolean isPaused(TenantId tenant) {
        MigrationState state = stateFor(tenant);
        return state != null && state.isPause();
    }

    /**
     * Starts refreshing.
     *
     * <p>{@code @PostConstruct} rather than a call from the constructor, so a unit test can
     * construct this and drive {@link #refreshOnce()} by hand without a background thread
     * running underneath it. Spring calls this; {@code new MigrationRegistry(...)} does not.
     */
    @PostConstruct
    public void start() {
        subscription = Flux.interval(REFRESH_INTERVAL, Schedulers.boundedElastic())
                .concatMap(tick -> refreshOnce())
                .subscribe();
        log.info("migration registry polling {} every {}", STATE_KEY, REFRESH_INTERVAL);
    }

    @PreDestroy
    public void stop() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
    }

    /** Whether the refresh loop is live. Exists so a test can assert it, not infer it. */
    public boolean isRefreshing() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }

    /** One refresh pass. Never signals an error, so the interval cannot be killed by one. */
    Mono<Void> refreshOnce() {
        return redis.<String, String>opsForHash()
                .entries(STATE_KEY)
                .collectList()
                .doOnNext(this::accept)
                .onErrorResume(error -> {
                    refreshFailures.increment();
                    // WARN, not ERROR: the gateway is serving. The previous state is kept,
                    // because dropping it on a single failed read would reopen the window
                    // mid-copy. MAX_STALENESS is what bounds that.
                    log.warn("could not refresh migration state: {}", error.toString());
                    return Mono.empty();
                })
                .then();
    }

    private void accept(java.util.List<Map.Entry<String, String>> entries) {
        Map<String, MigrationState> next = new HashMap<>();
        for (Map.Entry<String, String> entry : entries) {
            MigrationState state = MigrationState.parse(entry.getValue());
            if (state == null) {
                malformedEntries.increment();
                // Named, because the symptom of a typo here is a migration that silently
                // behaves as though nothing was configured.
                log.warn("ignoring unparseable migration state for tenant {}: '{}'",
                        entry.getKey(), entry.getValue());
                continue;
            }
            next.put(entry.getKey(), state);
        }

        Map<String, MigrationState> previous = states;
        states = Map.copyOf(next);
        lastSuccessNanos = System.nanoTime();
        staleLogged.set(false);

        if (!previous.equals(states)) {
            // INFO on change only. A migration is an operator action, and the question
            // afterwards is always "when exactly did it start?".
            log.info("migration state changed: {} -> {}", previous, states);
        }
    }

    /** Visible for tests: the current view, without the staleness check. */
    Map<String, MigrationState> currentStates() {
        return states;
    }

    /** Visible for tests: pretend the last successful refresh was this long ago. */
    void ageViewBy(Duration age) {
        lastSuccessNanos = System.nanoTime() - age.toNanos();
    }
}
