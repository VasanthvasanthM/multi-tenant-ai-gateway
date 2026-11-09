package io.vasanth.gateway.shard;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.shard.ShardId;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes per-shard replica lag, because ADR-0007 requires it.
 *
 * <h2>Why this had to exist before read replicas were trustworthy</h2>
 *
 * <p>[ADR-0007](../../../../../../docs/adr/0007-read-replicas-are-safe.md) argues that
 * replica lag on this data produces a cache <b>miss</b>, never a wrong answer - a stale
 * replica simply does not find a new entry, the request falls through to the provider, and
 * the response is correct. That argument is what permits far more aggressive read fan-out
 * than a normal OLTP system allows.
 *
 * <p>It also states the condition attached to that permission: <b>monitoring must alarm on
 * lag</b>. The failure is silent by construction. Unbounded lag does not break anything
 * visible - it degrades the hit ratio, which shows up as a larger upstream bill weeks
 * later. Without this gauge the ADR's safety argument was resting on a mitigation that did
 * not exist, which is the worst kind of documented decision.
 *
 * <h2>Why Flux.interval and not @Scheduled</h2>
 *
 * <p>The gateway has no {@code @EnableScheduling}, deliberately: it is a WebFlux service
 * and a Spring scheduler would add a thread pool to a data plane that otherwise has only
 * event loops. {@code Flux.interval} on {@code boundedElastic} keeps the probe in the
 * reactive model, and the blocking-free query shares the connection pool the service
 * already has.
 *
 * <p>Nothing on the request path reads this gauge. A probe that failed would cost a log
 * line, never a request.
 */
@Component
public class ReplicaLagMonitor {

    private static final Logger log = LoggerFactory.getLogger(ReplicaLagMonitor.class);

    /**
     * Seconds a replica is behind its primary.
     *
     * <p>{@code pg_last_xact_replay_timestamp()} is null on a primary and on a replica that
     * has not yet replayed anything, so {@code coalesce} reports zero rather than null -
     * null would make the gauge disappear from Prometheus, and a missing series cannot fire
     * an alert. Zero is also the truthful answer for a primary.
     *
     * <p>{@code GREATEST(..., 0)} guards a replica whose clock is ahead of the primary's,
     * which otherwise yields a negative lag and reads as healthy to any {@code >} alert.
     */
    private static final String LAG_SQL = """
            SELECT GREATEST(
                     COALESCE(
                       EXTRACT(EPOCH FROM (now() - pg_last_xact_replay_timestamp())),
                       0),
                     0) AS lag_seconds
            """;

    private final ShardedDatabase database;
    private final Duration interval;
    private final Duration timeout;
    private final Duration maxLag;

    /** Last observed lag per shard, in seconds. The gauge reads from here. */
    private final Map<ShardId, AtomicReference<Double>> lagByShard = new ConcurrentHashMap<>();

    private final Counter probeFailures;

    /** Shards currently reading from their primary, so the log fires once per transition. */
    private final java.util.Set<ShardId> failingOver =
            ConcurrentHashMap.newKeySet();

    private final Counter failovers;
    private final MeterRegistry meterRegistry;

    private volatile Disposable subscription;

    public ReplicaLagMonitor(
            ShardedDatabase database, ShardProperties properties, MeterRegistry meterRegistry) {
        this.database = database;
        this.interval = properties.lagProbeInterval();
        this.timeout = properties.lagProbeTimeout();
        this.maxLag = properties.maxReplicaLag();
        this.meterRegistry = meterRegistry;
        // Distinguished from "lag is high": a probe that cannot run means the gauge is
        // STALE, and a stale gauge under an alert threshold looks exactly like health.
        this.probeFailures = Counter.builder("gateway.replica.lag_probe_failures")
                .register(meterRegistry);
        // Each transition into reading from the primary. Distinct from the lag gauge:
        // this is the moment load MOVED, which is what explains a step change in primary
        // CPU that would otherwise look unexplained.
        this.failovers = Counter.builder("gateway.replica.read_failovers")
                .register(meterRegistry);

        registerGauges();

        // Hands the database its health check. A predicate rather than a reference back
        // to this object, because the monitor already depends on the database to reach
        // the replicas it measures and a mutual dependency would not construct.
        database.useReplicaHealth(this::replicaTooFarBehind);
    }

    /**
     * Whether a shard's replica is too far behind to be worth reading.
     *
     * <p>Consulted on the read path, so it must be cheap and must never block: it reads a
     * volatile cell the probe updates, and nothing else.
     *
     * <p><b>A stale gauge reads as healthy here, deliberately.</b> If probes are failing
     * the last value persists, and this will keep sending reads to the replica. The
     * alternative - treating "unknown" as unhealthy - would move an entire shard's reads
     * onto its primary because of a monitoring failure, which is a bigger outage than the
     * one it guards against. `gateway_replica_lag_probe_failures_total` is what makes that
     * state visible instead.
     */
    public boolean replicaTooFarBehind(ShardId shard) {
        if (maxLag.isZero() || maxLag.isNegative()) {
            return false;
        }
        AtomicReference<Double> holder = lagByShard.get(shard);
        if (holder == null) {
            return false;
        }
        boolean unhealthy = holder.get() > maxLag.toSeconds();
        if (unhealthy && failingOver.add(shard)) {
            log.warn("shard {} replica is {}s behind (limit {}s); reads fall back to the "
                            + "PRIMARY until it catches up",
                    shard, holder.get(), maxLag.toSeconds());
            failovers.increment();
        } else if (!unhealthy) {
            failingOver.remove(shard);
        }
        return unhealthy;
    }

    /**
     * One gauge per shard, registered up front.
     *
     * <p>Registered at construction rather than on first successful probe, so the series
     * exists from startup. A gauge that only appears once it has a value cannot alert on
     * "this shard has stopped reporting", which is the symptom of the worst case.
     */
    private void registerGauges() {
        // A stream rather than a loop, so the one-holder-per-shard allocation is not a
        // `new` inside a loop body. The allocation itself is unavoidable: each gauge
        // needs its own cell to read from.
        database.shards().forEach(shard ->
                lagByShard.computeIfAbsent(shard, key -> new AtomicReference<>(0.0d)));

        lagByShard.forEach((shard, holder) ->
                Gauge.builder("gateway.replica.lag_seconds", holder, AtomicReference::get)
                        .tag("shard", shard.value())
                        .tag("replicas", database.hasReplicas(shard) ? "yes" : "no")
                        .description(
                                "Seconds this shard's read replica is behind its primary")
                        .register(meterRegistry));
    }

    /** Starts probing. Separate from the constructor so a test can drive one pass. */
    public void start() {
        if (interval.isZero() || interval.isNegative()) {
            log.info("replica lag probing disabled (interval {})", interval);
            return;
        }
        subscription = Flux.interval(interval, Schedulers.boundedElastic())
                .concatMap(tick -> probeAll())
                .subscribe();
        log.info("replica lag probing every {} across {} shard(s)",
                interval, database.shardCount());
    }

    @PreDestroy
    public void stop() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
    }

    /** One probe pass over every shard. Never signals an error. */
    public Mono<Void> probeAll() {
        return Flux.fromIterable(database.shards())
                .concatMap(this::probe)
                .then();
    }

    private Mono<Void> probe(ShardId shard) {
        return database.readClientFor(shard)
                .sql(LAG_SQL)
                .map((row, metadata) -> {
                    Number value = row.get("lag_seconds", Number.class);
                    return value == null ? 0.0d : value.doubleValue();
                })
                .one()
                .timeout(timeout)
                .doOnNext(seconds -> lagByShard.get(shard).set(seconds))
                .doOnError(error -> {
                    probeFailures.increment();
                    // WARN, not ERROR: nothing is broken for a caller. The gauge simply
                    // stops advancing, which is why the failure counter exists alongside
                    // it - a flat gauge and a rising failure count mean "not measuring",
                    // and a flat gauge alone means "not lagging".
                    log.warn("replica lag probe failed for shard {}: {}",
                            shard, error.toString());
                })
                .onErrorResume(error -> Mono.empty())
                .then();
    }

    /** Last observed lag for a shard, in seconds. For tests and the startup log. */
    public double lagSecondsFor(ShardId shard) {
        AtomicReference<Double> holder = lagByShard.get(shard);
        return holder == null ? 0.0d : holder.get();
    }
}
