package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * These tests are the scaling claim, in executable form.
 *
 * <p>Everything here is deterministic - fixed keys, fixed seed, no randomness - so a failure
 * is a real regression rather than an unlucky run.
 */
class ConsistentHashRingTest {

    private static final int SAMPLE_KEYS = 100_000;

    private static List<ShardId> shards(int count) {
        List<ShardId> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(ShardId.of("pg-shard-" + i));
        }
        return result;
    }

    private static List<String> sampleKeys() {
        List<String> keys = new ArrayList<>(SAMPLE_KEYS);
        for (int i = 0; i < SAMPLE_KEYS; i++) {
            keys.add("tenant-" + i);
        }
        return keys;
    }

    private static Map<String, ShardId> assign(ConsistentHashRing ring, List<String> keys) {
        Map<String, ShardId> assignment = new LinkedHashMap<>(keys.size());
        for (String key : keys) {
            assignment.put(key, ring.route(key));
        }
        return assignment;
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void sameKeyAlwaysRoutesToSameShard() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(8));
            ShardId first = ring.route("tenant-acme");

            for (int i = 0; i < 10_000; i++) {
                assertThat(ring.route("tenant-acme")).isEqualTo(first);
            }
        }

        /**
         * Two gateway replicas must build identical rings even if their configuration lists
         * the shards in different orders. If they do not, one tenant is routed to two
         * different shards and its cache silently splits in half - a bug that would surface
         * only as an unexplained drop in hit rate.
         */
        @Test
        void ringIsIndependentOfShardOrdering() {
            List<ShardId> forward = shards(6);
            List<ShardId> reversed = new ArrayList<>(forward);
            Collections.reverse(reversed);
            List<ShardId> shuffled = new ArrayList<>(forward);
            Collections.swap(shuffled, 0, 3);
            Collections.swap(shuffled, 1, 5);

            ConsistentHashRing a = ConsistentHashRing.of(forward);
            ConsistentHashRing b = ConsistentHashRing.of(reversed);
            ConsistentHashRing c = ConsistentHashRing.of(shuffled);

            for (String key : sampleKeys().subList(0, 5_000)) {
                ShardId expected = a.route(key);
                assertThat(b.route(key)).isEqualTo(expected);
                assertThat(c.route(key)).isEqualTo(expected);
            }
        }

        @Test
        void duplicateShardsAreCollapsed() {
            ShardId one = ShardId.of("pg-shard-0");
            ConsistentHashRing ring = ConsistentHashRing.of(List.of(one, one, one));

            assertThat(ring.shardCount()).isEqualTo(1);
            assertThat(ring.ringSize()).isEqualTo(ConsistentHashRing.DEFAULT_VIRTUAL_NODES);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("load balance")
    class Balance {

        /**
         * With 150 virtual nodes per shard the expected coefficient of variation is roughly
         * 1/sqrt(150), about 8%. A 25% tolerance is therefore around three standard
         * deviations - loose enough never to be flaky, tight enough that losing virtual
         * nodes entirely would fail it.
         */
        @Test
        void distributesWithinTwentyFivePercentOfFairShare() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(8));

            Map<ShardId, Integer> counts = new HashMap<>();
            for (String key : sampleKeys()) {
                counts.merge(ring.route(key), 1, Integer::sum);
            }

            double fairShare = (double) SAMPLE_KEYS / 8;
            assertThat(counts).hasSize(8);
            assertThat(counts.values())
                    .allSatisfy(count -> assertThat(count)
                            .isBetween((int) (fairShare * 0.75), (int) (fairShare * 1.25)));
        }

        @Test
        void singleVirtualNodeIsMeasurablyWorse() {
            // Demonstrates why virtual nodes exist: with one position per shard the arcs
            // between shards are wildly uneven.
            ConsistentHashRing poor = ConsistentHashRing.of(shards(8), 1);

            Map<ShardId, Integer> counts = new HashMap<>();
            for (String key : sampleKeys()) {
                counts.merge(poor.route(key), 1, Integer::sum);
            }

            int max = Collections.max(counts.values());
            int min = Collections.min(counts.values());

            assertThat((double) max / min)
                    .as("one vnode per shard should give a visibly skewed spread")
                    .isGreaterThan(2.0d);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("rebalancing - the property that makes this scale")
    class Rebalancing {

        @Test
        void addingAShardMovesRoughlyOneOverNPlusOneOfKeys() {
            List<String> keys = sampleKeys();
            ConsistentHashRing before = ConsistentHashRing.of(shards(4));
            ConsistentHashRing after = before.withShard(ShardId.of("pg-shard-4"));

            Map<String, ShardId> was = assign(before, keys);
            Map<String, ShardId> now = assign(after, keys);

            long moved = keys.stream().filter(k -> !was.get(k).equals(now.get(k))).count();
            double movedFraction = (double) moved / keys.size();

            // Theoretical optimum going from 4 shards to 5 is exactly 1/5 = 20%.
            assertThat(movedFraction)
                    .as("expected about 20%% of keys to move, got %.1f%%", movedFraction * 100)
                    .isBetween(0.12d, 0.30d);
        }

        /**
         * The defining property of consistent hashing, and an exact one rather than a
         * statistical one: adding a shard may only take keys <em>from</em> existing shards
         * <em>to</em> the new shard. No key may be shuffled between two shards that both
         * already existed.
         *
         * <p>If this fails, an online migration is impossible: data would need to move
         * between arbitrary pairs of shards rather than only onto the new one.
         */
        @Test
        void keysOnlyEverMoveToTheNewShard() {
            List<String> keys = sampleKeys();
            ShardId added = ShardId.of("pg-shard-4");

            ConsistentHashRing before = ConsistentHashRing.of(shards(4));
            ConsistentHashRing after = before.withShard(added);

            Map<String, ShardId> was = assign(before, keys);
            Map<String, ShardId> now = assign(after, keys);

            for (String key : keys) {
                if (!was.get(key).equals(now.get(key))) {
                    assertThat(now.get(key))
                            .as("key %s moved from %s to %s, but the only legal destination "
                                    + "is the newly added shard", key, was.get(key), now.get(key))
                            .isEqualTo(added);
                }
            }
        }

        /**
         * The mirror property for shrinking: removing a shard may only relocate the keys
         * that lived on it. Everything else must stay exactly where it was.
         */
        @Test
        void removingAShardOnlyRelocatesItsOwnKeys() {
            List<String> keys = sampleKeys();
            ShardId removed = ShardId.of("pg-shard-2");

            ConsistentHashRing before = ConsistentHashRing.of(shards(5));
            ConsistentHashRing after = before.withoutShard(removed);

            Map<String, ShardId> was = assign(before, keys);
            Map<String, ShardId> now = assign(after, keys);

            for (String key : keys) {
                if (was.get(key).equals(removed)) {
                    assertThat(now.get(key)).isNotEqualTo(removed);
                } else {
                    assertThat(now.get(key))
                            .as("key %s did not live on the removed shard and must not move", key)
                            .isEqualTo(was.get(key));
                }
            }
        }

        @Test
        void modulusShardingWouldMoveFarMore() {
            // The comparison that justifies the whole class. Going from 4 to 5 shards with
            // `hash % n`, roughly 80% of keys change owner.
            List<String> keys = sampleKeys();
            long moved = keys.stream()
                    .filter(k -> {
                        int h = Math.abs(k.hashCode());
                        return (h % 4) != (h % 5);
                    })
                    .count();

            assertThat((double) moved / keys.size())
                    .as("modulo sharding should move most of the key space")
                    .isGreaterThan(0.70d);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("replica placement")
    class Replicas {

        @Test
        void replicasAreDistinctPhysicalShards() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(6));

            for (String key : sampleKeys().subList(0, 5_000)) {
                List<ShardId> placement = ring.routeWithReplicas(key, 3);

                assertThat(placement).hasSize(3).doesNotHaveDuplicates();
                assertThat(placement.get(0))
                        .as("first replica must be the primary")
                        .isEqualTo(ring.route(key));
            }
        }

        @Test
        void requestingMoreReplicasThanShardsReturnsAllShards() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(3));

            assertThat(ring.routeWithReplicas("tenant-acme", 10))
                    .hasSize(3)
                    .containsExactlyInAnyOrderElementsOf(ring.shards());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("immutability and guards")
    class Guards {

        @Test
        void withShardReturnsANewRingAndLeavesTheOriginalAlone() {
            ConsistentHashRing original = ConsistentHashRing.of(shards(3));
            ConsistentHashRing grown = original.withShard(ShardId.of("pg-shard-9"));

            assertThat(original.shardCount()).isEqualTo(3);
            assertThat(grown.shardCount()).isEqualTo(4);
            assertThat(grown).isNotSameAs(original);
        }

        @Test
        void addingAnExistingShardIsANoOp() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(3));

            assertThat(ring.withShard(ShardId.of("pg-shard-1"))).isSameAs(ring);
        }

        @Test
        void removingAnAbsentShardIsANoOp() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(3));

            assertThat(ring.withoutShard(ShardId.of("pg-shard-99"))).isSameAs(ring);
        }

        @Test
        void shardSetIsUnmodifiable() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(3));

            assertThatThrownBy(() -> ring.shards().add(ShardId.of("pg-shard-9")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void routingOnAnEmptyRingFailsLoudly() {
            ConsistentHashRing empty = ConsistentHashRing.of(List.of());

            assertThatThrownBy(() -> empty.route("tenant-acme"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("empty ring");
        }

        @Test
        void rejectsNonPositiveVirtualNodes() {
            assertThatThrownBy(() -> ConsistentHashRing.of(shards(2), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void routesByTenantId() {
            ConsistentHashRing ring = ConsistentHashRing.of(shards(4));
            TenantId tenant = TenantId.of("acme");

            assertThat(ring.route(tenant)).isEqualTo(ring.route("acme"));
        }
    }
}
