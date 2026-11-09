package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Replica lag probing: what the gauge reports, and what happens when a probe fails.
 *
 * <p>The failure mode worth guarding is not a wrong number but a <b>stale</b> one. If a
 * probe errors and the gauge simply keeps its last value, a dashboard shows a flat line
 * under the alert threshold - which is indistinguishable from a healthy replica. That is why
 * {@code gateway.replica.lag_probe_failures} exists alongside the gauge, and why it is
 * asserted here rather than left as a log line.
 */
class ReplicaLagMonitorTest {

    private static final ShardId SHARD_A = ShardId.of("shard-a");
    private static final ShardId SHARD_B = ShardId.of("shard-b");
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private SimpleMeterRegistry meters;

    /** Lag each shard's probe should report, or an error if the function throws. */
    private Function<ShardId, Mono<Double>> lagSource;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        lagSource = shard -> Mono.just(0.0d);
    }

    /** A client whose single query resolves through {@link #lagSource}. */
    @SuppressWarnings("unchecked")
    private DatabaseClient clientFor(ShardId shard) {
        DatabaseClient db = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Double> rows = mock(RowsFetchSpec.class);

        when(db.sql(anyString())).thenReturn(spec);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn(rows);
        when(rows.one()).thenAnswer(invocation -> lagSource.apply(shard));
        return db;
    }

    private ReplicaLagMonitor monitor(Duration interval, ShardId... shards) {
        Map<ShardId, ShardedDatabase.ShardClients> clients = new java.util.LinkedHashMap<>();
        for (ShardId shard : shards) {
            // One replica, so hasReplicas() is true and the probe reads the replica
            // rather than the primary - measuring lag on a primary always reads zero.
            clients.put(shard, new ShardedDatabase.ShardClients(
                    clientFor(shard), List.of(clientFor(shard))));
        }
        ShardedDatabase database = new ShardedDatabase(
                ShardRouter.over(clients.keySet()), clients);

        ShardProperties properties = new ShardProperties(
                List.of(), 150, interval, Duration.ofSeconds(2));
        return new ReplicaLagMonitor(database, properties, meters);
    }

    private ReplicaLagMonitor monitor(ShardId... shards) {
        return monitor(Duration.ofSeconds(30), shards);
    }

    private double gauge(ShardId shard) {
        Gauge found = meters.find("gateway.replica.lag_seconds")
                .tag("shard", shard.value())
                .gauge();
        assertThat(found).as("gauge for %s", shard).isNotNull();
        return found.value();
    }

    private double failures() {
        return Optional.ofNullable(
                        meters.find("gateway.replica.lag_probe_failures").counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    @Test
    @DisplayName("a gauge exists for every shard from construction, before any probe")
    void gaugesRegisteredUpFront() {
        monitor(SHARD_A, SHARD_B);

        // Registered eagerly on purpose. A gauge that only appears after its first
        // successful probe cannot alert on "this shard stopped reporting", which is the
        // symptom of the worst case - the probe itself being broken.
        assertThat(gauge(SHARD_A)).isZero();
        assertThat(gauge(SHARD_B)).isZero();
    }

    @Test
    @DisplayName("the gauge reports the measured lag per shard")
    void reportsMeasuredLag() {
        ReplicaLagMonitor monitor = monitor(SHARD_A, SHARD_B);
        lagSource = shard -> Mono.just(SHARD_A.equals(shard) ? 0.4d : 12.5d);

        StepVerifier.create(monitor.probeAll()).verifyComplete();

        assertThat(gauge(SHARD_A)).isEqualTo(0.4d);
        assertThat(gauge(SHARD_B)).isEqualTo(12.5d);
        assertThat(monitor.lagSecondsFor(SHARD_B)).isEqualTo(12.5d);
        assertThat(failures()).isZero();
    }

    @Test
    @DisplayName("a failing probe is counted and does NOT fail the pass")
    void failingProbeIsCountedAndContained() {
        ReplicaLagMonitor monitor = monitor(SHARD_A, SHARD_B);
        lagSource = shard -> SHARD_A.equals(shard)
                ? Mono.error(new IllegalStateException("replica unreachable"))
                : Mono.just(3.0d);

        // The pass must complete: it runs on an interval, and an error would terminate
        // the Flux.interval chain permanently - the probe would never run again and the
        // only evidence would be one log line at startup.
        StepVerifier.create(monitor.probeAll()).verifyComplete();

        assertThat(failures()).isEqualTo(1.0d);
        // Shard B was still probed, so one bad replica does not blind the others.
        assertThat(gauge(SHARD_B)).isEqualTo(3.0d);
    }

    @Test
    @DisplayName("a failed probe leaves the previous value, which is why failures are counted")
    void staleValueIsWhyTheCounterExists() {
        ReplicaLagMonitor monitor = monitor(SHARD_A);

        lagSource = shard -> Mono.just(7.0d);
        StepVerifier.create(monitor.probeAll()).verifyComplete();
        assertThat(gauge(SHARD_A)).isEqualTo(7.0d);

        lagSource = shard -> Mono.error(new IllegalStateException("gone"));
        StepVerifier.create(monitor.probeAll()).verifyComplete();

        // Still 7.0 - the gauge holds its last reading rather than resetting. On its own
        // that is indistinguishable from a replica that stopped lagging, so the failure
        // counter is the only thing that tells the two apart. An alert on lag alone is
        // not sufficient; it has to be paired with one on this counter.
        assertThat(gauge(SHARD_A)).isEqualTo(7.0d);
        assertThat(failures()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a null lag reads as zero rather than breaking the gauge")
    void nullLagIsZero() {
        ReplicaLagMonitor monitor = monitor(SHARD_A);
        // pg_last_xact_replay_timestamp() is null on a primary and on a replica that has
        // replayed nothing yet. The SQL coalesces, but the mapper defends too, because a
        // null would otherwise unbox into an NPE inside the probe.
        lagSource = shard -> Mono.empty();

        StepVerifier.create(monitor.probeAll()).verifyComplete();

        assertThat(gauge(SHARD_A)).isZero();
        assertThat(failures()).isZero();
    }

    @Test
    @DisplayName("the gauge is tagged with whether the shard actually has a replica")
    void taggedWithReplicaPresence() {
        monitor(SHARD_A);

        // Without the tag, a single-node deployment reporting a flat zero looks like a
        // perfectly healthy replica rather than no replica at all.
        assertThat(meters.find("gateway.replica.lag_seconds")
                .tag("shard", SHARD_A.value())
                .tag("replicas", "yes")
                .gauge()).isNotNull();
    }

    @Test
    @DisplayName("a zero interval disables probing instead of spinning")
    void zeroIntervalDisables() {
        ReplicaLagMonitor monitor = monitor(Duration.ZERO, SHARD_A);

        // Flux.interval(ZERO) would busy-loop. Treating it as "off" gives operators a
        // kill switch that does not require removing the bean.
        monitor.start();
        monitor.stop();

        assertThat(failures()).isZero();
    }
}
