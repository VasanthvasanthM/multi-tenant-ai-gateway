package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.tenant.TenantId;

/** The router that owns shard membership while requests are in flight. */
class ShardRouterTest {

    private static final ShardId A = ShardId.of("shard-a");
    private static final ShardId B = ShardId.of("shard-b");
    private static final ShardId C = ShardId.of("shard-c");

    private static List<TenantId> tenants(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> TenantId.of("tenant-" + i))
                .toList();
    }

    @Nested
    @DisplayName("routing")
    class Routing {

        @Test
        @DisplayName("a tenant always routes to the same shard")
        void routingIsStable() {
            ShardRouter router = ShardRouter.over(Set.of(A, B, C));
            TenantId tenant = TenantId.of("acme");

            ShardId first = router.route(tenant);
            for (int i = 0; i < 100; i++) {
                assertThat(router.route(tenant)).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("routing by tenant and by the tenant's value agree")
        void tenantAndStringRoutingAgree() {
            ShardRouter router = ShardRouter.over(Set.of(A, B, C));

            // The string overload exists for sub-shard keys like "acme#3". It must hash
            // the same way, or a whale tenant's buckets would land on a different ring
            // than the tenant itself.
            for (TenantId tenant : tenants(200)) {
                assertThat(router.route(tenant)).isEqualTo(router.route(tenant.value()));
            }
        }

        @Test
        @DisplayName("a single-shard router sends everything to that shard")
        void singleShardRoutesEverything() {
            ShardRouter router = ShardRouter.single(A);

            assertThat(router.isSingleShard()).isTrue();
            assertThat(router.shardCount()).isEqualTo(1);
            for (TenantId tenant : tenants(500)) {
                assertThat(router.route(tenant)).isEqualTo(A);
            }
        }

        @Test
        @DisplayName("replica routing returns distinct shards")
        void replicaRoutingIsDistinct() {
            ShardRouter router = ShardRouter.over(Set.of(A, B, C));

            List<ShardId> targets = router.routeWithReplicas(TenantId.of("acme"), 2);

            assertThat(targets).hasSize(2).doesNotHaveDuplicates();
            // The first is always the primary owner, so a replica-aware write still
            // agrees with a replica-unaware read.
            assertThat(targets.get(0)).isEqualTo(router.route(TenantId.of("acme")));
        }
    }

    @Nested
    @DisplayName("membership changes")
    class Membership {

        @Test
        @DisplayName("adding a shard moves only some tenants, and only to the new shard")
        void addingAShardMovesOnlySomeTenants() {
            ShardRouter router = ShardRouter.over(Set.of(A, B));
            List<TenantId> all = tenants(2_000);
            Map<TenantId, ShardId> before = new ConcurrentHashMap<>();
            all.forEach(t -> before.put(t, router.route(t)));

            assertThat(router.addShard(C)).isTrue();

            int moved = 0;
            for (TenantId tenant : all) {
                ShardId now = router.route(tenant);
                if (!now.equals(before.get(tenant))) {
                    moved++;
                    // The defining property of consistent hashing: a key only ever moves
                    // TO the new shard, never between two existing ones. If keys shuffled
                    // between A and B as well, adding capacity would invalidate far more
                    // of the cache than it needs to.
                    assertThat(now).isEqualTo(C);
                }
            }

            // Roughly 1/3 for 2 -> 3 shards. Generous bounds: this is a statistical
            // property of the hash, not an exact one.
            assertThat(moved).isBetween(400, 1_000);
        }

        @Test
        @DisplayName("adding a shard twice reports no change the second time")
        void addingTwiceIsIdempotent() {
            ShardRouter router = ShardRouter.over(Set.of(A, B));

            assertThat(router.addShard(C)).isTrue();
            // false, not an exception: a topology reconciler re-applying desired state
            // must be able to call this repeatedly without special-casing.
            assertThat(router.addShard(C)).isFalse();
            assertThat(router.shardCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("removing a shard relocates only its own tenants")
        void removingRelocatesOnlyItsOwn() {
            ShardRouter router = ShardRouter.over(Set.of(A, B, C));
            List<TenantId> all = tenants(2_000);
            Map<TenantId, ShardId> before = new ConcurrentHashMap<>();
            all.forEach(t -> before.put(t, router.route(t)));

            assertThat(router.removeShard(C)).isTrue();

            for (TenantId tenant : all) {
                if (!before.get(tenant).equals(C)) {
                    // Tenants that were not on the removed shard must not move at all.
                    assertThat(router.route(tenant)).isEqualTo(before.get(tenant));
                } else {
                    assertThat(router.route(tenant)).isIn(A, B);
                }
            }
        }

        @Test
        @DisplayName("removing an absent shard reports no change")
        void removingAbsentIsNoOp() {
            ShardRouter router = ShardRouter.over(Set.of(A, B));

            assertThat(router.removeShard(C)).isFalse();
            assertThat(router.shardCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("removing the LAST shard is refused")
        void refusesToRemoveTheLastShard() {
            ShardRouter router = ShardRouter.single(A);

            // An empty ring cannot route, so this would turn a decommission into a total
            // Tier-2 outage - presenting as an exception on every request rather than
            // anything naming the cause.
            assertThatThrownBy(() -> router.removeShard(A))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("last shard");

            assertThat(router.shardCount()).isEqualTo(1);
            assertThat(router.route(TenantId.of("acme"))).isEqualTo(A);
        }

        @Test
        @DisplayName("an empty ring is rejected at construction, not at first request")
        void emptyRingIsRejectedEarly() {
            assertThatThrownBy(() -> ShardRouter.over(Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one shard");
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("routing never fails or returns null while membership changes")
        void routingSurvivesConcurrentTopologyChanges() throws Exception {
            ShardRouter router = ShardRouter.over(Set.of(A, B));
            ExecutorService pool = Executors.newFixedThreadPool(5);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger routed = new AtomicInteger();
            AtomicInteger failures = new AtomicInteger();

            // Four readers routing continuously...
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int n = 0; n < 20_000; n++) {
                            ShardId shard = router.route(TenantId.of("tenant-" + (n % 97)));
                            if (shard == null) {
                                failures.incrementAndGet();
                            }
                            routed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                });
            }

            // ...while one writer churns the topology.
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 200; n++) {
                        router.addShard(C);
                        router.removeShard(C);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });

            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // The point of the AtomicReference swap: a reader sees the ring before the
            // change or after it, never a half-updated one, and never blocks.
            assertThat(failures.get()).isZero();
            assertThat(routed.get()).isEqualTo(80_000);
        }

        @Test
        @DisplayName("concurrent adds all take effect - no update is lost")
        void concurrentAddsAllApply() throws Exception {
            ShardRouter router = ShardRouter.single(A);
            List<ShardId> toAdd = IntStream.range(0, 32)
                    .mapToObj(i -> ShardId.of("shard-" + i))
                    .toList();

            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            for (ShardId shard : toAdd) {
                pool.submit(() -> {
                    start.await();
                    return router.addShard(shard);
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // A plain get-then-set would lose updates here. The compare-and-set loop is
            // what makes 32 concurrent additions all land.
            assertThat(router.shardCount()).isEqualTo(33);
            assertThat(router.shards()).contains(A).containsAll(toAdd);
        }
    }

    @Test
    @DisplayName("the exposed ring is the live one and is immutable")
    void exposedRingIsImmutable() {
        ShardRouter router = ShardRouter.over(Set.of(A, B));
        ConsistentHashRing snapshot = router.currentRing();

        router.addShard(C);

        // The snapshot is unaffected, because rings are immutable and the router swapped
        // in a new one rather than mutating this.
        assertThat(snapshot.shardCount()).isEqualTo(2);
        assertThat(router.currentRing().shardCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("toString names the shards, for a startup log that is worth reading")
    void toStringIsUseful() {
        assertThat(ShardRouter.over(Set.of(A, B)).toString())
                .contains("shard-a")
                .contains("shard-b");
    }
}
