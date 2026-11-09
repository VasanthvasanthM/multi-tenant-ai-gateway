package io.vasanth.gateway.control.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Publishing per-tenant quotas into Redis.
 *
 * <p>The gateway reads {@code {tenant:x}:quota} and falls back to a configured default when
 * the key is absent. Nothing wrote that key before this class, so every tenant silently got
 * the same hard-coded number regardless of its row in {@code tenants} - a feature that
 * existed on both ends and was connected at neither.
 *
 * <p>The assertion that matters most is the key SHAPE: two services agreeing on a key by
 * coincidence is a bug waiting to happen, so it is pinned here and in the gateway's own
 * test. A mismatch would not fail anything - the gateway would simply never find a value
 * and quietly use the default forever.
 */
class TenantQuotaPublisherTest {

    private Map<String, String> written;
    private Map<String, Duration> ttls;
    private SimpleMeterRegistry meters;
    private TenantQuotaPublisher publisher;
    private List<Map.Entry<String, Long>> rows;
    private boolean redisFails;
    private boolean queryFails;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        written = new LinkedHashMap<>();
        ttls = new LinkedHashMap<>();
        meters = new SimpleMeterRegistry();
        rows = List.of();
        redisFails = false;
        queryFails = false;

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.set(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    if (redisFails) {
                        return Mono.error(new IllegalStateException("redis down"));
                    }
                    written.put(invocation.getArgument(0), invocation.getArgument(1));
                    ttls.put(invocation.getArgument(0), invocation.getArgument(2));
                    return Mono.just(true);
                });

        DatabaseClient db = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        RowsFetchSpec<Map.Entry<String, Long>> fetch = mock(RowsFetchSpec.class);
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.map(any(java.util.function.BiFunction.class))).thenReturn(fetch);
        when(fetch.all()).thenAnswer(invocation -> queryFails
                ? Flux.error(new IllegalStateException("database down"))
                : Flux.fromIterable(rows));

        publisher = new TenantQuotaPublisher(db, redis, meters);
    }

    private double counter(String name) {
        return Optional.ofNullable(meters.find(name).counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    @Test
    @DisplayName("the key shape matches what the gateway reads, hash tag included")
    void keyShape() {
        // The gateway's TenantQuotaRegistry builds {tenant:acme}:quota. A mismatch fails
        // nothing loudly - the gateway just never finds a value and uses the default
        // forever, which is exactly the bug this class exists to fix.
        assertThat(TenantQuotaPublisher.quotaKey("acme")).isEqualTo("{tenant:acme}:quota");
    }

    @Test
    @DisplayName("publishing writes the limit as a plain number")
    void publishesLimit() {
        StepVerifier.create(publisher.publish("acme", 2_500_000L)).verifyComplete();

        // Plain decimal, because the gateway parses it with Long.parseLong and treats
        // anything unparseable as "no override published".
        assertThat(written).containsEntry("{tenant:acme}:quota", "2500000");
        assertThat(counter("control_plane.quota.published")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("every key carries a TTL, so a deleted tenant loses its entitlement")
    void keysExpire() {
        StepVerifier.create(publisher.publish("acme", 1L)).verifyComplete();

        // Without the TTL, removing a tenant would need an explicit Redis delete that
        // could be missed - and the failure mode is a deleted customer keeping a budget.
        assertThat(ttls.get("{tenant:acme}:quota")).isEqualTo(TenantQuotaPublisher.KEY_TTL);
        // Longer than the reconcile interval, or a brief control-plane outage would
        // change every tenant's entitlement.
        assertThat(TenantQuotaPublisher.KEY_TTL)
                .isGreaterThan(TenantQuotaPublisher.RECONCILE_INTERVAL);
    }

    @Test
    @DisplayName("a reconcile republishes every active tenant")
    void reconcilePublishesAll() {
        rows = List.of(Map.entry("acme", 1_000_000L), Map.entry("globex", 5_000_000L));

        assertThat(publisher.reconcile().block(Duration.ofSeconds(5))).isEqualTo(2L);

        assertThat(written)
                .containsEntry("{tenant:acme}:quota", "1000000")
                .containsEntry("{tenant:globex}:quota", "5000000");
    }

    @Test
    @DisplayName("a reconcile is what makes the state self-healing after a Redis flush")
    void reconcileRepublishesAfterLoss() {
        rows = List.of(Map.entry("acme", 1_000_000L));
        publisher.reconcile().block(Duration.ofSeconds(5));

        // Redis is a cache with no durability guarantee here: a restart, an eviction or a
        // failover drops keys and the tenant silently reverts to the default.
        written.clear();

        publisher.reconcile().block(Duration.ofSeconds(5));
        assertThat(written).containsEntry("{tenant:acme}:quota", "1000000");
    }

    @Test
    @DisplayName("a row with a null id or quota is skipped rather than published as garbage")
    void skipsIncompleteRows() {
        rows = java.util.Arrays.asList(
                Map.entry("acme", 1L),
                new java.util.AbstractMap.SimpleEntry<>("globex", (Long) null),
                new java.util.AbstractMap.SimpleEntry<>((String) null, 5L));

        assertThat(publisher.reconcile().block(Duration.ofSeconds(5))).isEqualTo(1L);
        assertThat(written).hasSize(1).containsKey("{tenant:acme}:quota");
    }

    @Test
    @DisplayName("a Redis failure is counted and never fails the caller")
    void redisFailureIsContained() {
        redisFails = true;

        // A saga step that could not warm a cache must not roll back a tenant that was
        // successfully created. The reconcile publishes it within the minute.
        StepVerifier.create(publisher.publish("acme", 1L)).verifyComplete();

        assertThat(counter("control_plane.quota.publish_failures")).isEqualTo(1.0d);
        assertThat(counter("control_plane.quota.published")).isZero();
    }

    @Test
    @DisplayName("a database failure ends the reconcile at zero rather than erroring")
    void queryFailureIsContained() {
        queryFails = true;

        // Runs on an interval: an error would terminate the chain permanently and the
        // reconcile would never run again.
        assertThat(publisher.reconcile().block(Duration.ofSeconds(5))).isZero();
        assertThat(counter("control_plane.quota.publish_failures")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an empty tenant table publishes nothing and is not an error")
    void emptyTableIsFine() {
        assertThat(publisher.reconcile().block(Duration.ofSeconds(5))).isZero();
        assertThat(written).isEmpty();
        assertThat(counter("control_plane.quota.publish_failures")).isZero();
    }
}
