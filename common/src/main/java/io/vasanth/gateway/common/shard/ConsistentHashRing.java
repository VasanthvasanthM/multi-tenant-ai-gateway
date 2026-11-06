package io.vasanth.gateway.common.shard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * A consistent-hash ring with virtual nodes, used to map a tenant to a storage shard.
 *
 * <h2>Why consistent hashing rather than modulo</h2>
 *
 * <p>{@code hash(key) % shardCount} remaps almost every key when the shard count changes -
 * growing from 4 shards to 5 moves roughly 80% of the key space, which means an effectively
 * total cache invalidation and a migration that cannot be done online. A consistent-hash
 * ring moves only about K/N keys for the same change.
 *
 * <h2>Why virtual nodes</h2>
 *
 * <p>With one ring position per shard, the arcs between shards vary wildly and some shard
 * ends up owning several times its fair share. Placing {@value #DEFAULT_VIRTUAL_NODES}
 * positions per shard averages the arcs out, bringing the spread to within a few percent.
 *
 * <h2>Why this class is immutable</h2>
 *
 * <p>Routing happens on every request. A mutable ring would need a lock or a concurrent map
 * on the hottest path in the system, and that shared-state contention is exactly what shows
 * up as a non-zero sigma term in the Universal Scalability Law fit - the thing this project
 * sets out to demonstrate is absent. Instead, {@link #withShard} and {@link #withoutShard}
 * return new rings and the reference is swapped atomically, so readers never synchronise.
 *
 * <h2>Determinism across nodes</h2>
 *
 * <p>Every gateway replica must build an identical ring from the same shard set, or two
 * replicas will route one tenant to two different shards and silently split its cache.
 * Shards are therefore sorted before placement, so ring construction does not depend on the
 * iteration order of whatever collection the caller passed in.
 */
public final class ConsistentHashRing {

    /**
     * Virtual nodes per physical shard.
     *
     * <p>150 is the usual choice (Ketama uses 160). Below about 100 the load spread becomes
     * visibly uneven; above a few hundred the ring costs memory and build time for no
     * measurable improvement in balance.
     */
    public static final int DEFAULT_VIRTUAL_NODES = 150;

    /**
     * Fixed seed. Must never change: altering it rebuilds the entire ring and relocates
     * every tenant, which is a full cache invalidation plus a data migration.
     */
    private static final int RING_SEED = 0x9747_b28c;

    private final NavigableMap<Long, ShardId> ring;
    private final Set<ShardId> shards;
    private final int virtualNodes;

    private ConsistentHashRing(
            NavigableMap<Long, ShardId> ring, Set<ShardId> shards, int virtualNodes) {
        this.ring = ring;
        this.shards = shards;
        this.virtualNodes = virtualNodes;
    }

    public static ConsistentHashRing of(Collection<ShardId> shards) {
        return of(shards, DEFAULT_VIRTUAL_NODES);
    }

    public static ConsistentHashRing of(Collection<ShardId> shards, int virtualNodes) {
        Objects.requireNonNull(shards, "shards must not be null");
        if (virtualNodes < 1) {
            throw new IllegalArgumentException("virtualNodes must be at least 1: " + virtualNodes);
        }

        // Sorted for determinism - see the class javadoc.
        List<ShardId> ordered = new ArrayList<>(new LinkedHashSet<>(shards));
        Collections.sort(ordered);

        NavigableMap<Long, ShardId> built = new TreeMap<>();
        for (ShardId shard : ordered) {
            for (int i = 0; i < virtualNodes; i++) {
                place(built, shard, i);
            }
        }

        return new ConsistentHashRing(
                Collections.unmodifiableNavigableMap(built),
                Set.copyOf(ordered),
                virtualNodes);
    }

    /**
     * Routes a tenant to its shard. This is the method the request path calls.
     *
     * <p>Routing by tenant rather than by cache key is what makes this design work: a
     * semantic cache lookup is always scoped to one tenant, so a query never has to fan out
     * across shards. Cross-shard approximate-nearest-neighbour search is the usual reason
     * sharded vector stores fail to scale, and this design simply never performs one.
     */
    public ShardId route(TenantId tenantId) {
        return route(tenantId.value());
    }

    /** Routes an arbitrary key string to a shard. */
    public ShardId route(String key) {
        requireNonEmpty();
        long position = MurmurHash3.hash64(key.getBytes(StandardCharsets.UTF_8), RING_SEED);

        Map.Entry<Long, ShardId> entry = ring.ceilingEntry(position);
        // Past the last position, wrap to the start: the ring is circular.
        return entry != null ? entry.getValue() : ring.firstEntry().getValue();
    }

    /**
     * Returns the {@code count} distinct shards clockwise from the key's position, the first
     * being the primary.
     *
     * <p>This is how replica placement is derived: a tenant's data lives on its primary and
     * the next N-1 distinct shards around the ring. Walking to distinct <em>physical</em>
     * shards matters - with virtual nodes the next few positions frequently belong to the
     * shard you just came from, and naively taking them would place every replica on one
     * machine.
     *
     * @param count how many shards to return; capped at the number of shards in the ring
     */
    public List<ShardId> routeWithReplicas(String key, int count) {
        requireNonEmpty();
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1: " + count);
        }

        int wanted = Math.min(count, shards.size());
        long position = MurmurHash3.hash64(key.getBytes(StandardCharsets.UTF_8), RING_SEED);

        Set<ShardId> selected = new LinkedHashSet<>(wanted);
        for (Map.Entry<Long, ShardId> entry : ring.tailMap(position, true).entrySet()) {
            selected.add(entry.getValue());
            if (selected.size() == wanted) {
                return List.copyOf(selected);
            }
        }
        // Wrapped past the end without filling the quota; continue from the start.
        for (Map.Entry<Long, ShardId> entry : ring.headMap(position, false).entrySet()) {
            selected.add(entry.getValue());
            if (selected.size() == wanted) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    /** Returns a new ring with {@code shard} added. This ring is unchanged. */
    public ConsistentHashRing withShard(ShardId shard) {
        Objects.requireNonNull(shard, "shard must not be null");
        if (shards.contains(shard)) {
            return this;
        }
        List<ShardId> next = new ArrayList<>(shards);
        next.add(shard);
        return of(next, virtualNodes);
    }

    /** Returns a new ring with {@code shard} removed. This ring is unchanged. */
    public ConsistentHashRing withoutShard(ShardId shard) {
        Objects.requireNonNull(shard, "shard must not be null");
        if (!shards.contains(shard)) {
            return this;
        }
        List<ShardId> next = new ArrayList<>(shards);
        next.remove(shard);
        return of(next, virtualNodes);
    }

    /** The physical shards on this ring. Unmodifiable. */
    public Set<ShardId> shards() {
        return shards;
    }

    public int shardCount() {
        return shards.size();
    }

    /** Total ring positions, i.e. shards times virtual nodes, minus any collisions resolved. */
    public int ringSize() {
        return ring.size();
    }

    public int virtualNodes() {
        return virtualNodes;
    }

    private static void place(NavigableMap<Long, ShardId> target, ShardId shard, int replica) {
        String label = shard.value() + '#' + replica;
        long position = MurmurHash3.hash64(label.getBytes(StandardCharsets.UTF_8), RING_SEED);

        // Two virtual nodes colliding on one position is vanishingly unlikely across a
        // 64-bit space, but "unlikely" is not "impossible" and silently dropping a vnode
        // would skew the balance in a way that is near-impossible to diagnose later.
        // Linear probing is deterministic here because placement order is sorted.
        while (target.putIfAbsent(position, shard) != null) {
            position++;
        }
    }

    private void requireNonEmpty() {
        if (ring.isEmpty()) {
            throw new IllegalStateException("cannot route on an empty ring: no shards configured");
        }
    }

    @Override
    public String toString() {
        return "ConsistentHashRing[shards=" + shards.size()
                + ", virtualNodes=" + virtualNodes
                + ", positions=" + ring.size() + ']';
    }
}
