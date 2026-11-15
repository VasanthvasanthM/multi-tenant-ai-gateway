package io.vasanth.gateway.common.shard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Which tenants change shard between two ring configurations, and nothing else.
 *
 * <p>Adding or removing a shard is the one operation on a sharded system that is routinely
 * done wrong, and the reason is that the interesting part is invisible: you cannot see which
 * tenants moved, so you cannot verify that you copied the right data, and a tenant whose
 * rows were left behind reads as an empty history rather than as an error.
 *
 * <p>This class makes that set explicit and checkable <em>before</em> anything is copied.
 * It performs no I/O, holds no connection, and is therefore cheap to run against production
 * ring configurations as a dry run.
 *
 * <h2>What it guarantees, and why that matters operationally</h2>
 *
 * <p>With a consistent hash ring, adding a shard moves tenants <strong>only onto the new
 * shard</strong> - never between two shards that both already existed. Removing one moves
 * <strong>only that shard's tenants</strong>, spread over the survivors. Those two
 * properties are what make the migration bounded, and {@link #assertNoLateralMoves()} turns
 * them from a claim into a check: a lateral move means the ring was built differently on
 * each side (a different virtual-node count, a different seed, a different shard id
 * spelling) and the migration about to run would shuffle far more data than intended.
 *
 * <p>Compare {@code hash % N}, where changing N rehashes roughly every tenant. The whole
 * point of the ring is that this plan is small; a plan that is <em>not</em> small is
 * therefore evidence of a configuration mistake, not of a large migration.
 *
 * <h2>What it deliberately does not know</h2>
 *
 * <p>It is given the tenant list rather than discovering it. The authoritative list lives in
 * the control plane's {@code tenants} table, and a planner that queried for it would couple
 * this logic to a schema and make it untestable without a database. The caller reads the
 * list once and passes it in.
 *
 * <p>It also says nothing about <em>what</em> to copy. Which tables hold per-tenant data,
 * and which of them even need copying, is a property of the data rather than of the ring -
 * {@code prompt_cache} can be discarded and allowed to re-fill, while {@code usage_ledger}
 * cannot. See {@code docs/runbooks/shard-migration.md}.
 */
public final class ShardMigrationPlan {

    private final ConsistentHashRing before;
    private final ConsistentHashRing after;
    private final List<Move> moves;
    private final List<TenantId> unmoved;

    private ShardMigrationPlan(
            ConsistentHashRing before,
            ConsistentHashRing after,
            List<Move> moves,
            List<TenantId> unmoved) {

        this.before = before;
        this.after = after;
        this.moves = List.copyOf(moves);
        this.unmoved = List.copyOf(unmoved);
    }

    /**
     * One tenant's relocation.
     *
     * @param tenant the tenant whose data has to move
     * @param from the shard that holds it now
     * @param to the shard that will own it after the cutover
     */
    public record Move(TenantId tenant, ShardId from, ShardId to) {

        public Move {
            Objects.requireNonNull(tenant, "tenant must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");

            if (from.equals(to)) {
                // A Move that does not move is a planning bug, and a silent one: it would
                // produce a copy of a tenant's rows onto the shard they already live on,
                // which either duplicates them or no-ops depending on the conflict clause.
                throw new IllegalArgumentException(
                        "from and to are the same shard (" + from + ") for tenant " + tenant);
            }
        }
    }

    /**
     * Computes the plan.
     *
     * <p>Routes every tenant through both rings and keeps the ones that disagree. That is
     * the entire algorithm, and it is deliberately the same {@code route} call the gateway
     * makes on the hot path: a planner with its own copy of the routing rule would drift
     * from the thing it is predicting.
     *
     * @param before the ring currently in effect
     * @param after the ring that will be in effect
     * @param tenants every tenant with data, from the control plane
     */
    public static ShardMigrationPlan between(
            ConsistentHashRing before, ConsistentHashRing after, Collection<TenantId> tenants) {

        Objects.requireNonNull(before, "before must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(tenants, "tenants must not be null");

        if (before.shardCount() == 0 || after.shardCount() == 0) {
            // An empty ring cannot route, so there is no plan to compute - and an empty
            // "after" would silently mean "every tenant moves to nowhere".
            throw new IllegalArgumentException(
                    "both rings must have at least one shard; before=" + before.shardCount()
                            + " after=" + after.shardCount());
        }

        List<Move> moves = new ArrayList<>();
        List<TenantId> unmoved = new ArrayList<>();

        // Sorted, so two runs over the same inputs produce byte-identical plans. A
        // migration is reviewed by a human and then replayed; a plan whose order changes
        // between runs cannot be diffed against the one that was approved.
        List<TenantId> ordered = new ArrayList<>(tenants);
        ordered.sort(Comparator.comparing(TenantId::value));

        for (TenantId tenant : ordered) {
            ShardId from = before.route(tenant);
            ShardId to = after.route(tenant);
            if (from.equals(to)) {
                unmoved.add(tenant);
            } else {
                moves.add(new Move(tenant, from, to));
            }
        }

        return new ShardMigrationPlan(before, after, moves, unmoved);
    }

    /** The relocations, in deterministic tenant order. */
    public List<Move> moves() {
        return moves;
    }

    /** Tenants whose shard is unchanged. Nothing is copied for these. */
    public List<TenantId> unmoved() {
        return unmoved;
    }

    public int moveCount() {
        return moves.size();
    }

    public int tenantCount() {
        return moves.size() + unmoved.size();
    }

    /** Fraction of tenants that move, 0.0 to 1.0. */
    public double movedFraction() {
        int total = tenantCount();
        return total == 0 ? 0.0d : (double) moves.size() / total;
    }

    /** Shards present in {@code after} but not {@code before}. */
    public Set<ShardId> addedShards() {
        Set<ShardId> added = new TreeSet<>(after.shards());
        added.removeAll(before.shards());
        return added;
    }

    /** Shards present in {@code before} but not {@code after}. */
    public Set<ShardId> removedShards() {
        Set<ShardId> removed = new TreeSet<>(before.shards());
        removed.removeAll(after.shards());
        return removed;
    }

    /** How many tenants each destination shard gains, highest first. */
    public Map<ShardId, Integer> gainsByShard() {
        return countBy(Move::to);
    }

    /** How many tenants each source shard loses, highest first. */
    public Map<ShardId, Integer> lossesByShard() {
        return countBy(Move::from);
    }

    private Map<ShardId, Integer> countBy(java.util.function.Function<Move, ShardId> key) {
        Map<ShardId, Integer> counts = new LinkedHashMap<>();
        for (Move move : moves) {
            counts.merge(key.apply(move), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<ShardId, Integer>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /**
     * Moves that go from one pre-existing shard to another pre-existing shard.
     *
     * <p>A correct ring produces none. Every move should either land on a newly added shard
     * or leave a removed one.
     */
    public List<Move> lateralMoves() {
        Set<ShardId> added = addedShards();
        Set<ShardId> removed = removedShards();
        List<Move> lateral = new ArrayList<>();
        for (Move move : moves) {
            if (!added.contains(move.to()) && !removed.contains(move.from())) {
                lateral.add(move);
            }
        }
        return lateral;
    }

    /**
     * Fails unless the plan has the shape a consistent hash ring guarantees.
     *
     * <p>Call this before copying anything. A lateral move is not a bigger migration to be
     * accepted - it is evidence that the two rings were not built the same way, and the
     * usual causes are a different virtual-node count, a different hash seed, or a shard id
     * that differs by case or whitespace. Copying against such a plan moves far more data
     * than intended and leaves tenants pointing at shards that were never populated.
     *
     * @throws IllegalStateException if any tenant moves between two shards that both exist
     *     on each side of the change
     */
    public void assertNoLateralMoves() {
        List<Move> lateral = lateralMoves();
        if (lateral.isEmpty()) {
            return;
        }

        Move first = lateral.get(0);
        throw new IllegalStateException(
                "plan contains " + lateral.size() + " lateral move(s), which a consistent "
                        + "hash ring cannot produce - the two rings were almost certainly "
                        + "built with different virtual-node counts, seeds or shard ids. "
                        + "First: tenant " + first.tenant() + " " + first.from() + " -> "
                        + first.to());
    }

    /** A human-readable summary, for the dry run and the change record. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("shard migration plan\n");
        out.append("  before:  ").append(before.shardCount()).append(" shard(s) ")
                .append(new TreeSet<>(before.shards())).append('\n');
        out.append("  after:   ").append(after.shardCount()).append(" shard(s) ")
                .append(new TreeSet<>(after.shards())).append('\n');

        if (!addedShards().isEmpty()) {
            out.append("  added:   ").append(addedShards()).append('\n');
        }
        if (!removedShards().isEmpty()) {
            out.append("  removed: ").append(removedShards()).append('\n');
        }

        out.append(String.format(
                "  tenants: %d total, %d move (%.1f%%), %d stay%n",
                tenantCount(), moveCount(), movedFraction() * 100.0d, unmoved.size()));

        if (!moves.isEmpty()) {
            out.append("  gains:   ").append(gainsByShard()).append('\n');
            out.append("  losses:  ").append(lossesByShard()).append('\n');
        }

        List<Move> lateral = lateralMoves();
        out.append(lateral.isEmpty()
                ? "  lateral: none (as a consistent hash ring guarantees)\n"
                : "  lateral: " + lateral.size() + " - REFUSE TO MIGRATE, see "
                        + "assertNoLateralMoves()\n");

        return out.toString();
    }

    @Override
    public String toString() {
        return "ShardMigrationPlan[" + moveCount() + " of " + tenantCount() + " tenants move]";
    }
}
