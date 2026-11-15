package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.tenant.TenantId;

@DisplayName("ShardMigrationPlan")
class ShardMigrationPlanTest {

    /**
     * Enough tenants that proportions mean something. With 150 virtual nodes per shard the
     * spread is good but not perfect, so a sample of 50 would produce percentages that swing
     * several points either side and make the bounds below either vacuous or flaky.
     */
    private static final int TENANTS = 20_000;

    private static List<TenantId> tenants(int count) {
        List<TenantId> all = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // Varied prefixes rather than tenant-0..N. A sequential list differs only in its
            // trailing digits, and a hash that happened to weight those lightly would look
            // far better distributed than it is.
            all.add(TenantId.of("tenant-" + Integer.toHexString(i) + "-" + (i % 7)));
        }
        return all;
    }

    private static ConsistentHashRing ringOf(String... ids) {
        List<ShardId> shards = new ArrayList<>();
        for (String id : ids) {
            shards.add(ShardId.of(id));
        }
        return ConsistentHashRing.of(shards);
    }

    @Nested
    @DisplayName("adding a shard")
    class AddingAShard {

        @Test
        @DisplayName("moves only ONTO the new shard, never between existing shards")
        void movesOnlyOntoTheNewShard() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1", "shard-2", "shard-3");
            ConsistentHashRing after = before.withShard(ShardId.of("shard-4"));

            ShardMigrationPlan plan =
                    ShardMigrationPlan.between(before, after, tenants(TENANTS));

            // THE property that makes re-sharding affordable. If tenants also moved between
            // shard-1 and shard-2, the migration would touch far more data than the new
            // shard's share, and every claim about bounded movement would be false.
            assertThat(plan.lateralMoves()).isEmpty();
            assertThat(plan.moves())
                    .allSatisfy(move -> assertThat(move.to()).isEqualTo(ShardId.of("shard-4")));
            assertThat(plan.gainsByShard()).containsOnlyKeys(ShardId.of("shard-4"));
        }

        @Test
        @DisplayName("moves roughly 1/N of tenants, where N is the new shard count")
        void movesRoughlyOneOverN() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1", "shard-2", "shard-3");
            ConsistentHashRing after = before.withShard(ShardId.of("shard-4"));

            ShardMigrationPlan plan =
                    ShardMigrationPlan.between(before, after, tenants(TENANTS));

            // 150 new ring positions out of 750 total, so about 20%. The bound is generous
            // because virtual nodes make the spread good, not exact - the point being proved
            // is the ORDER of magnitude: a fifth, not four fifths as `hash % N` would give.
            assertThat(plan.movedFraction()).isBetween(0.15d, 0.25d);
        }

        @Test
        @DisplayName("every tenant still routes somewhere after the change")
        void everyTenantStillRoutes() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1");
            ConsistentHashRing after = before.withShard(ShardId.of("shard-2"));

            ShardMigrationPlan plan = ShardMigrationPlan.between(before, after, tenants(5_000));

            // The accounting has to close: a tenant that appears in neither list has been
            // dropped by the planner, and its data would simply be left behind.
            assertThat(plan.tenantCount()).isEqualTo(5_000);
            assertThat(plan.moveCount() + plan.unmoved().size()).isEqualTo(5_000);
        }
    }

    @Nested
    @DisplayName("removing a shard")
    class RemovingAShard {

        @Test
        @DisplayName("moves ONLY the removed shard's tenants")
        void movesOnlyTheRemovedShardsTenants() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1", "shard-2", "shard-3");
            ConsistentHashRing after = before.withoutShard(ShardId.of("shard-3"));

            ShardMigrationPlan plan =
                    ShardMigrationPlan.between(before, after, tenants(TENANTS));

            assertThat(plan.removedShards()).containsExactly(ShardId.of("shard-3"));
            assertThat(plan.lateralMoves()).isEmpty();
            assertThat(plan.moves())
                    .allSatisfy(move -> assertThat(move.from()).isEqualTo(ShardId.of("shard-3")));
        }

        @Test
        @DisplayName("spreads them over every surviving shard, not onto one")
        void spreadsOverTheSurvivors() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1", "shard-2", "shard-3");
            ConsistentHashRing after = before.withoutShard(ShardId.of("shard-3"));

            ShardMigrationPlan plan =
                    ShardMigrationPlan.between(before, after, tenants(TENANTS));

            // If the orphaned tenants all landed on whichever shard follows the removed one,
            // decommissioning a shard would overload a single survivor - the failure that
            // makes people avoid shrinking a cluster at all.
            assertThat(plan.gainsByShard()).containsOnlyKeys(
                    ShardId.of("shard-0"), ShardId.of("shard-1"), ShardId.of("shard-2"));
        }
    }

    @Nested
    @DisplayName("the plan itself")
    class PlanProperties {

        @Test
        @DisplayName("an unchanged ring produces an empty plan")
        void unchangedRingProducesNothing() {
            ConsistentHashRing ring = ringOf("shard-0", "shard-1", "shard-2");

            ShardMigrationPlan plan = ShardMigrationPlan.between(ring, ring, tenants(1_000));

            assertThat(plan.moves()).isEmpty();
            assertThat(plan.movedFraction()).isZero();
            assertThat(plan.unmoved()).hasSize(1_000);
        }

        @Test
        @DisplayName("is byte-identical across runs, so an approved plan can be replayed")
        void isDeterministic() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1");
            ConsistentHashRing after = before.withShard(ShardId.of("shard-2"));
            List<TenantId> sample = tenants(2_000);

            // Shuffled input, because a migration reads its tenant list from a query whose
            // row order is not guaranteed. The plan has to be a function of the SET.
            List<TenantId> shuffled = new ArrayList<>(sample);
            java.util.Collections.shuffle(shuffled, new java.util.Random(42));

            String first = ShardMigrationPlan.between(before, after, sample).summary();
            String second = ShardMigrationPlan.between(before, after, shuffled).summary();

            assertThat(second).isEqualTo(first);
        }

        @Test
        @DisplayName("a Move from a shard to itself is rejected at construction")
        void rejectsNonMove() {
            assertThatThrownBy(() -> new ShardMigrationPlan.Move(
                            TenantId.of("acme"), ShardId.of("shard-0"), ShardId.of("shard-0")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same shard");
        }

        @Test
        @DisplayName("an empty ring on either side is rejected rather than planned")
        void rejectsEmptyRing() {
            ConsistentHashRing populated = ringOf("shard-0");
            ConsistentHashRing empty = ConsistentHashRing.of(Set.of());

            // An empty "after" would otherwise mean "every tenant moves to nowhere", which
            // is a plan that reads as enormous rather than as invalid.
            assertThatThrownBy(() ->
                            ShardMigrationPlan.between(populated, empty, tenants(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one shard");

            assertThatThrownBy(() ->
                            ShardMigrationPlan.between(empty, populated, tenants(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one shard");
        }

        @Test
        @DisplayName("summary names the added shard, the percentage, and the lateral verdict")
        void summaryIsUsableAsAChangeRecord() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1");
            ConsistentHashRing after = before.withShard(ShardId.of("shard-2"));

            String summary = ShardMigrationPlan.between(before, after, tenants(1_000)).summary();

            assertThat(summary)
                    .contains("added:")
                    .contains("shard-2")
                    .contains("move (")
                    .contains("lateral: none");
        }
    }

    @Nested
    @DisplayName("the lateral-move guard")
    class LateralGuard {

        @Test
        @DisplayName("passes for a ring that only gained a shard")
        void passesForAHonestChange() {
            ConsistentHashRing before = ringOf("shard-0", "shard-1", "shard-2");
            ConsistentHashRing after = before.withShard(ShardId.of("shard-3"));

            ShardMigrationPlan plan =
                    ShardMigrationPlan.between(before, after, tenants(TENANTS));

            plan.assertNoLateralMoves();
        }

        @Test
        @DisplayName("catches rings built with different virtual-node counts")
        void catchesMismatchedVirtualNodes() {
            // The real-world mistake this guard exists for: someone adds a shard while also
            // changing the vnode count, perhaps because a newer config file has a different
            // default. The ring is then laid out completely differently, tenants move
            // between pre-existing shards, and the migration silently needs to touch most of
            // the data rather than a fifth of it.
            List<ShardId> three = List.of(
                    ShardId.of("shard-0"), ShardId.of("shard-1"), ShardId.of("shard-2"));
            ConsistentHashRing before = ConsistentHashRing.of(three, 150);
            ConsistentHashRing after = ConsistentHashRing.of(
                    List.of(ShardId.of("shard-0"), ShardId.of("shard-1"),
                            ShardId.of("shard-2"), ShardId.of("shard-3")),
                    64);

            ShardMigrationPlan plan =
                    ShardMigrationPlan.between(before, after, tenants(TENANTS));

            assertThat(plan.lateralMoves()).isNotEmpty();
            assertThatThrownBy(plan::assertNoLateralMoves)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lateral move")
                    .hasMessageContaining("virtual-node");
        }

        @Test
        @DisplayName("the mismatch moves far more data than the honest change would")
        void mismatchMovesFarMore() {
            List<ShardId> four = List.of(
                    ShardId.of("shard-0"), ShardId.of("shard-1"),
                    ShardId.of("shard-2"), ShardId.of("shard-3"));
            List<ShardId> five = new ArrayList<>(four);
            five.add(ShardId.of("shard-4"));

            ConsistentHashRing before = ConsistentHashRing.of(four, 150);
            ShardMigrationPlan honest = ShardMigrationPlan.between(
                    before, ConsistentHashRing.of(five, 150), tenants(TENANTS));
            ShardMigrationPlan mismatched = ShardMigrationPlan.between(
                    before, ConsistentHashRing.of(five, 64), tenants(TENANTS));

            // This is the number that justifies the guard existing at all: the mismatched
            // change is not slightly worse, it is multiples worse, and without the check it
            // looks like an ordinary migration right up to the point of running it.
            assertThat(mismatched.moveCount()).isGreaterThan(honest.moveCount() * 2);
        }
    }
}
