package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Flux;

/**
 * Migration state: who is paused, who is dual-writing, and what happens when the view
 * cannot be refreshed.
 *
 * <p>The interesting cases are all about <b>staleness</b>. State held forever because
 * Redis went away is a tenant outage; state dropped on the first failed read reopens the
 * window mid-copy. The bound between those two is the behaviour worth pinning.
 */
class MigrationRegistryTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final TenantId GLOBEX = TenantId.of("globex");
    private static final ShardId SHARD_1 = ShardId.of("shard-1");
    private static final ShardId SHARD_4 = ShardId.of("shard-4");

    private ReactiveHashOperations<String, String, String> hashOps;
    private SimpleMeterRegistry meters;
    private ShardedDatabase database;
    private MigrationRegistry registry;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        hashOps = mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hashOps);
        meters = new SimpleMeterRegistry();
        database = mock(ShardedDatabase.class);
        // Constructed directly, so @PostConstruct never fires and no background interval
        // runs underneath these assertions.
        registry = new MigrationRegistry(redis, database, meters);
    }

    private void redisHolds(Map<String, String> entries) {
        when(hashOps.entries(anyString())).thenReturn(Flux.fromIterable(entries.entrySet()));
    }

    private static Map<String, String> entry(String tenant, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(tenant, value);
        return map;
    }

    private double counter(String name) {
        return meters.find(name).counter() == null ? 0d : meters.find(name).counter().count();
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("parsing what an operator wrote")
    class Parsing {

        @Test
        void pause() {
            assertThat(MigrationState.parse("pause")).isEqualTo(MigrationState.paused());
            assertThat(MigrationState.parse("  PAUSE ")).isEqualTo(MigrationState.paused());
        }

        @Test
        void dual() {
            assertThat(MigrationState.parse("dual:shard-1:shard-4"))
                    .isEqualTo(MigrationState.dualWrite(SHARD_1, SHARD_4));
        }

        @Test
        @DisplayName("anything unrecognised parses to null rather than throwing")
        void malformed() {
            // One operator typo must not stop the refresh for every other tenant.
            assertThat(MigrationState.parse("dual:shard-1")).isNull();
            assertThat(MigrationState.parse("dual::shard-4")).isNull();
            assertThat(MigrationState.parse("paws")).isNull();
            assertThat(MigrationState.parse(null)).isNull();
        }

        @Test
        @DisplayName("a dual-write to the same shard is refused, not silently degraded")
        void sameShardIsRejected() {
            // Would quietly become an ordinary single write while the operator believed
            // both shards were being kept current.
            assertThat(MigrationState.parse("dual:shard-1:shard-1")).isNull();
            assertThatThrownBy(() -> MigrationState.dualWrite(SHARD_1, SHARD_1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("reading the state")
    class Reading {

        @Test
        void nothingBeforeTheFirstRefresh() {
            assertThat(registry.stateFor(ACME)).isNull();
            assertThat(registry.isPaused(ACME)).isFalse();
        }

        @Test
        @DisplayName("a paused tenant is paused; another tenant is untouched")
        void pausesOnlyTheListedTenant() {
            redisHolds(entry("acme", "pause"));
            registry.refreshOnce().block();

            assertThat(registry.isPaused(ACME)).isTrue();
            assertThat(registry.isPaused(GLOBEX)).isFalse();
            assertThat(registry.stateFor(GLOBEX)).isNull();
        }

        @Test
        @DisplayName("a dual-writing tenant is NOT paused - it is served normally")
        void dualWritingTenantIsServed() {
            redisHolds(entry("acme", "dual:shard-1:shard-4"));
            registry.refreshOnce().block();

            // The entire point of dual-write: the customer notices nothing.
            assertThat(registry.isPaused(ACME)).isFalse();
            assertThat(registry.stateFor(ACME).isDual()).isTrue();
            assertThat(registry.stateFor(ACME).from()).isEqualTo(SHARD_1);
            assertThat(registry.stateFor(ACME).to()).isEqualTo(SHARD_4);
        }

        @Test
        @DisplayName("clearing the entry ends the migration")
        void clears() {
            redisHolds(entry("acme", "dual:shard-1:shard-4"));
            registry.refreshOnce().block();

            when(hashOps.entries(anyString())).thenReturn(Flux.empty());
            registry.refreshOnce().block();

            assertThat(registry.stateFor(ACME)).isNull();
        }

        @Test
        @DisplayName("a malformed entry is counted and skipped, not fatal")
        void malformedEntryIsSkipped() {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("acme", "dual:shard-1:shard-4");
            entries.put("globex", "duel:shard-2:shard-5");
            redisHolds(entries);

            registry.refreshOnce().block();

            assertThat(registry.stateFor(ACME)).isNotNull();
            assertThat(registry.stateFor(GLOBEX)).isNull();
            assertThat(counter("gateway.migration.malformed_entries")).isEqualTo(1d);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("when Redis is unreachable")
    class Staleness {

        @Test
        @DisplayName("a failed refresh keeps the state rather than reopening the window")
        void failedRefreshKeepsState() {
            redisHolds(entry("acme", "pause"));
            registry.refreshOnce().block();

            when(hashOps.entries(anyString()))
                    .thenReturn(Flux.error(new IllegalStateException("redis down")));
            registry.refreshOnce().block();

            // Dropping it here would let writes land on the source mid-copy - exactly the
            // window this exists to close. One failed read is not evidence of anything.
            assertThat(registry.isPaused(ACME)).isTrue();
            assertThat(counter("gateway.migration.refresh_failures")).isEqualTo(1d);
        }

        @Test
        @DisplayName("a refresh never signals an error, so the interval cannot die")
        void refreshSwallowsErrors() {
            when(hashOps.entries(anyString()))
                    .thenReturn(Flux.error(new IllegalStateException("redis down")));

            // If this propagated, the first Redis blip would terminate the Flux.interval
            // and state would stop refreshing for the life of the pod - silently.
            assertThat(registry.refreshOnce().block()).isNull();
        }

        @Test
        @DisplayName("a view past the staleness bound stops applying to anyone")
        void staleViewFailsOpen() {
            redisHolds(entry("acme", "pause"));
            registry.refreshOnce().block();
            assertThat(registry.isPaused(ACME)).isTrue();

            registry.ageViewBy(MigrationRegistry.MAX_STALENESS.plusSeconds(1));

            // Fail open, like the rate limiter and the budget gate: an unreachable Redis
            // must not become an indefinite outage for a paying customer. The counter is
            // what keeps it from being silent.
            assertThat(registry.isPaused(ACME)).isFalse();
            assertThat(counter("gateway.migration.stale_expiries")).isEqualTo(1d);
        }

        @Test
        @DisplayName("a successful refresh recovers from stale")
        void recovers() {
            redisHolds(entry("acme", "pause"));
            registry.refreshOnce().block();
            registry.ageViewBy(MigrationRegistry.MAX_STALENESS.plusSeconds(1));
            assertThat(registry.isPaused(ACME)).isFalse();

            registry.refreshOnce().block();

            assertThat(registry.isPaused(ACME)).isTrue();
        }

        @Test
        @DisplayName("an empty view short-circuits before any staleness arithmetic")
        void emptyIsTheFastPath() {
            when(hashOps.entries(anyString())).thenReturn(Flux.empty());
            registry.refreshOnce().block();
            registry.ageViewBy(Duration.ofHours(1));

            assertThat(registry.stateFor(ACME)).isNull();
            assertThat(counter("gateway.migration.stale_expiries")).isZero();
        }
    }

    // ------------------------------------------------------------------
    @Test
    @DisplayName("the gauge tracks how many tenants are migrating")
    void gaugeTracksTheMap() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("acme", "pause");
        entries.put("globex", "dual:shard-2:shard-5");
        redisHolds(entries);

        registry.refreshOnce().block();

        assertThat(meters.find("gateway.migration.active_tenants").gauge().value())
                .isEqualTo(2d);
        assertThat(registry.currentStates()).hasSize(2);
    }

    @Test
    @DisplayName("isRefreshing reports whether the loop is actually live")
    void startAndStopTrackTheSubscription() {
        when(hashOps.entries(anyString())).thenReturn(Flux.empty());

        assertThat(registry.isRefreshing()).isFalse();

        registry.start();
        assertThat(registry.isRefreshing()).isTrue();

        // Stopped before the first 2 s tick, so nothing polls Redis during this test.
        registry.stop();
        assertThat(registry.isRefreshing()).isFalse();
    }

    @Test
    @DisplayName("stopping twice is harmless")
    void stopIsIdempotent() {
        when(hashOps.entries(anyString())).thenReturn(Flux.empty());
        registry.start();
        registry.stop();

        // @PreDestroy can run after an explicit stop during a shutdown sequence, and a
        // second dispose must not throw its way out of bean destruction.
        registry.stop();

        assertThat(registry.isRefreshing()).isFalse();
    }

    @Test
    @DisplayName("Spring starts the refresh loop: start() carries @PostConstruct")
    void refreshStartsUnderSpring() throws NoSuchMethodException {
        // Without this, the registry would hold an empty map forever and every migration
        // would silently behave as though nothing had been configured - the same shape of
        // bug as ReplicaLagMonitor's missing @PostConstruct.
        assertThat(MigrationRegistry.class.getMethod("start")
                .isAnnotationPresent(jakarta.annotation.PostConstruct.class))
                .as("start() must be called by the container, or no migration ever applies")
                .isTrue();
    }
}
