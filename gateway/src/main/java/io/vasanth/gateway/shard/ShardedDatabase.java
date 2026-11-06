package io.vasanth.gateway.shard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.r2dbc.core.DatabaseClient;

import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Picks the {@link DatabaseClient} for a tenant, and for reads prefers a replica.
 *
 * <p>This is the class that makes the consistent-hash ring load-bearing rather than
 * merely present. Everything that touches Tier-2 storage goes through
 * {@link #forRead(TenantId)} or {@link #forWrite(TenantId)}, so the shard key is applied
 * in exactly one place.
 *
 * <h2>Why reads may go to a replica and writes may not</h2>
 *
 * <p>On this data, replica lag produces a cache MISS, never a wrong answer: the search
 * does not find a newly written entry, the gateway falls through to the provider, and
 * the response is re-cached. The cost is one avoidable upstream call on a small fraction
 * of requests. That is a completely different risk profile from replica lag on an
 * account balance, and it is why read scaling is safe here at all (ADR-0007).
 *
 * <p>Writes go to the primary because a write to a replica simply fails - which at
 * least fails loudly.
 *
 * <h2>Replica selection</h2>
 *
 * <p>Round-robin over a shard's replicas, by an atomic counter. Deliberately not random:
 * with two replicas, random selection gives an uneven split often enough to look like a
 * replica problem. Not least-connections either - that needs per-connection state the
 * R2DBC pool already owns, and guessing at it from here would be worse than round-robin.
 */
public final class ShardedDatabase {

    private final ShardRouter router;
    private final Map<ShardId, ShardClients> clients;

    public ShardedDatabase(ShardRouter router, Map<ShardId, ShardClients> clients) {
        this.router = router;
        this.clients = Map.copyOf(clients);

        // Every shard on the ring must have a client, or a tenant hashing to the missing
        // one fails on every request while every other tenant is fine - which presents
        // as an intermittent, tenant-specific outage and is miserable to diagnose.
        List<ShardId> unbacked = router.shards().stream()
                .filter(shard -> !this.clients.containsKey(shard))
                .sorted()
                .toList();
        if (!unbacked.isEmpty()) {
            throw new IllegalStateException(
                    "shards on the ring have no database client: " + unbacked);
        }
    }

    /** Single-shard convenience: the unsharded deployment. */
    public static ShardedDatabase single(DatabaseClient client) {
        ShardId only = ShardId.of("default");
        return new ShardedDatabase(
                ShardRouter.single(only),
                Map.of(only, new ShardClients(client, List.of())));
    }

    /** The client to read this tenant's cache entries from. A replica when available. */
    public DatabaseClient forRead(TenantId tenantId) {
        return clients.get(router.route(tenantId)).read();
    }

    /** The client to write this tenant's cache entries to. Always the primary. */
    public DatabaseClient forWrite(TenantId tenantId) {
        return clients.get(router.route(tenantId)).primary();
    }

    /** Which shard owns this tenant. For logging and metrics, not for routing. */
    public ShardId shardOf(TenantId tenantId) {
        return router.route(tenantId);
    }

    // -----------------------------------------------------------------
    // Topology facts, rather than the router itself.
    //
    // A router() getter handed out the MUTABLE ShardRouter, so any caller could
    // add or remove a shard through a getter on the database facade - which
    // SpotBugs flags as EI_EXPOSE_REP and is right to. Changing the topology
    // should be a deliberate, named operation, not a side effect of asking a
    // question about it. Nothing changes membership at runtime yet; when an
    // admin endpoint needs to, it gets its own entry point.
    // -----------------------------------------------------------------

    public int shardCount() {
        return router.shardCount();
    }

    /** True when there is exactly one shard, i.e. an unsharded deployment. */
    public boolean isSingleShard() {
        return router.isSingleShard();
    }

    /** Current membership. The ring returns an immutable set. */
    public Set<ShardId> shards() {
        return router.shards();
    }

    /** Virtual nodes per shard, for the startup log and for tests. */
    public int virtualNodes() {
        return router.currentRing().virtualNodes();
    }

    /** Total points on the ring: shards x virtual nodes. */
    public int ringSize() {
        return router.currentRing().ringSize();
    }

    /**
     * One shard's clients.
     *
     * <p>The round-robin counter lives here rather than in {@link ShardedDatabase} so
     * that each shard rotates its own replicas independently. A single shared counter
     * would couple them: with shards holding two and three replicas, one counter would
     * distribute unevenly across both.
     */
    public static final class ShardClients {

        private final DatabaseClient primary;
        private final List<DatabaseClient> replicas;
        private final AtomicInteger next = new AtomicInteger();

        public ShardClients(DatabaseClient primary, List<DatabaseClient> replicas) {
            this.primary = primary;
            this.replicas = List.copyOf(replicas);
        }

        public DatabaseClient primary() {
            return primary;
        }

        /** A replica, or the primary when none are configured. */
        public DatabaseClient read() {
            if (replicas.isEmpty()) {
                return primary;
            }
            if (replicas.size() == 1) {
                return replicas.get(0);
            }
            // Math.floorMod, not %. getAndIncrement overflows to Integer.MIN_VALUE
            // eventually, and a plain % then yields a NEGATIVE index and an
            // ArrayIndexOutOfBoundsException - after roughly two billion reads, which is
            // a fortnight at a few thousand requests per second and is exactly the kind
            // of bug that only ever appears in production.
            return replicas.get(Math.floorMod(next.getAndIncrement(), replicas.size()));
        }

        public int replicaCount() {
            return replicas.size();
        }
    }
}
