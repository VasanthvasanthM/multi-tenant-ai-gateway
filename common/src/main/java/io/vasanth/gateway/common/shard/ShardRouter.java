package io.vasanth.gateway.common.shard;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Routes a tenant to a shard, and lets the shard set change while requests are in
 * flight.
 *
 * <h2>Why this exists separately from {@link ConsistentHashRing}</h2>
 *
 * <p>The ring is deliberately immutable - a ring mutated while being read is a
 * correctness bug, and routing is on the hot path. But shard membership genuinely does
 * change: a shard is added when capacity runs out, and removed when one is
 * decommissioned. Something has to own that transition.
 *
 * <p>This class is that owner. It holds the current ring in an {@link AtomicReference}
 * and swaps it wholesale. A reader either sees the ring before the change or the ring
 * after it, never a half-updated one, and no reader ever blocks - which matters because
 * every single request routes.
 *
 * <h2>What a membership change costs</h2>
 *
 * <p>Adding a shard moves approximately K/N keys, so those tenants' next Tier-2 lookups
 * miss and fall through to the provider. That is a cost in latency and provider spend
 * for a short period, not an error - which is the same reason read replicas are
 * acceptable here (ADR-0007). It is also why a rebalance should be a deliberate
 * operation rather than something driven automatically off a health check: a flapping
 * shard would repeatedly invalidate a slice of the cache.
 *
 * <h2>Why the shard key is the tenant</h2>
 *
 * <p>Every cache lookup is already tenant-scoped, so a request touches exactly one
 * shard and there is never a cross-shard nearest-neighbour search. See
 * ADR-0006 - that single property is what makes the vector search affordable at all.
 *
 * <p>This class is framework-free, like the rest of {@code common}, and thread-safe.
 */
public final class ShardRouter {

    private final AtomicReference<ConsistentHashRing> ring;

    public ShardRouter(ConsistentHashRing initial) {
        if (initial.shardCount() == 0) {
            // An empty ring cannot route, and the failure would appear later as a
            // NoSuchElementException from somewhere deep in a request rather than at
            // startup where it belongs.
            throw new IllegalArgumentException("a router needs at least one shard");
        }
        this.ring = new AtomicReference<>(initial);
    }

    /** Builds a router over the given shards with the default virtual-node count. */
    public static ShardRouter over(Collection<ShardId> shards) {
        return new ShardRouter(ConsistentHashRing.of(shards));
    }

    /** Builds a single-shard router, which is the unsharded deployment. */
    public static ShardRouter single(ShardId shard) {
        return over(List.of(shard));
    }

    /** The shard owning this tenant's data. */
    public ShardId route(TenantId tenantId) {
        return ring.get().route(tenantId);
    }

    /**
     * The shard owning an arbitrary key.
     *
     * <p>Used for keys that are not a tenant id - a whale tenant's sub-shard key, for
     * instance, which is {@code tenantId + "#" + bucket}.
     */
    public ShardId route(String key) {
        return ring.get().route(key);
    }

    /**
     * The first {@code count} distinct shards clockwise from the key.
     *
     * <p>For replication of the same data across shards, which this project does not do
     * today - PostgreSQL streaming replication handles redundancy within a shard. It is
     * here because the ring supports it and a future multi-write topology would need it.
     */
    public List<ShardId> routeWithReplicas(TenantId tenantId, int count) {
        return ring.get().routeWithReplicas(tenantId.value(), count);
    }

    /** Current membership. */
    public Set<ShardId> shards() {
        return ring.get().shards();
    }

    public int shardCount() {
        return ring.get().shardCount();
    }

    /** True when there is exactly one shard, i.e. an unsharded deployment. */
    public boolean isSingleShard() {
        return ring.get().shardCount() == 1;
    }

    /**
     * Adds a shard, returning true if membership actually changed.
     *
     * <p>Compare-and-set in a loop rather than a lock: two concurrent topology changes
     * are rare but must not lose one of the two updates, and a plain get-then-set would.
     */
    public boolean addShard(ShardId shard) {
        while (true) {
            ConsistentHashRing current = ring.get();
            if (current.shards().contains(shard)) {
                return false;
            }
            if (ring.compareAndSet(current, current.withShard(shard))) {
                return true;
            }
        }
    }

    /**
     * Removes a shard, returning true if membership actually changed.
     *
     * <p>Refuses to remove the last shard. An empty ring cannot route, so this would
     * turn a decommission into a total outage of the Tier-2 cache - and the symptom
     * would be an exception on every request rather than anything naming the cause.
     */
    public boolean removeShard(ShardId shard) {
        while (true) {
            ConsistentHashRing current = ring.get();
            if (!current.shards().contains(shard)) {
                return false;
            }
            if (current.shardCount() == 1) {
                throw new IllegalStateException(
                        "refusing to remove the last shard " + shard
                                + "; an empty ring cannot route any tenant");
            }
            if (ring.compareAndSet(current, current.withoutShard(shard))) {
                return true;
            }
        }
    }

    /** The ring as it is right now. Immutable, so safe to hand out. */
    public ConsistentHashRing currentRing() {
        return ring.get();
    }

    @Override
    public String toString() {
        return "ShardRouter" + ring.get().shards();
    }
}
