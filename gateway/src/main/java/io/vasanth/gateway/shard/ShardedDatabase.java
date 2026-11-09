package io.vasanth.gateway.shard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.r2dbc.core.DatabaseClient;

import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;
import io.vasanth.gateway.common.shard.SubShard;
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

    /**
     * Tenants split across several buckets, and how many each has.
     *
     * <p>Empty for every normal deployment. See {@link SubShard} for why this is opt-in
     * and why it must stay rare.
     */
    private final Map<String, Integer> whaleBuckets;

    /**
     * Decides whether a shard's replica is too far behind to read from.
     *
     * <p>Injected as a predicate rather than a dependency on {@code ReplicaLagMonitor},
     * which would be circular - the monitor needs this class to reach the replicas it
     * measures. Defaults to "never unhealthy", so a deployment that never sets a monitor
     * behaves exactly as before.
     */
    private volatile java.util.function.Predicate<ShardId> replicaUnhealthy = shard -> false;

    public ShardedDatabase(ShardRouter router, Map<ShardId, ShardClients> clients) {
        this(router, clients, Map.of());
    }

    public ShardedDatabase(
            ShardRouter router,
            Map<ShardId, ShardClients> clients,
            Map<String, Integer> whaleBuckets) {
        this.router = router;
        this.clients = Map.copyOf(clients);
        this.whaleBuckets = Map.copyOf(whaleBuckets);

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

    /**
     * Supplies the health check used to decide whether to read from a replica.
     *
     * <p>Set once at startup by {@code ReplicaLagMonitor}. Volatile rather than final
     * because the monitor is constructed after this - a setter is the smaller evil against
     * a circular constructor dependency, and nothing else mutates it.
     */
    public void useReplicaHealth(java.util.function.Predicate<ShardId> unhealthy) {
        this.replicaUnhealthy = unhealthy == null ? shard -> false : unhealthy;
    }

    /**
     * The client to read this tenant's cache entries from. A replica when available
     * <em>and healthy</em>.
     *
     * <p>A replica lagging past {@code gateway.sharding.max-replica-lag} is skipped in
     * favour of the primary. ADR-0007's argument is that lag costs cache HITS rather than
     * correctness, so past some lag the replica is paying a round trip to be told nothing
     * - and every miss it causes is a billed upstream call. Falling back trades replica
     * offload for hit rate, which is the right trade at that point.
     */
    public DatabaseClient forRead(TenantId tenantId) {
        ShardId shard = router.route(tenantId);
        ShardClients shardClients = clients.get(shard);
        return replicaUnhealthy.test(shard) ? shardClients.primary() : shardClients.read();
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
    // Whale tenants: sub-sharded across several buckets.
    //
    // Everything below is a no-op for a tenant that is not configured as a
    // whale, which is every tenant by default. The methods exist so the
    // caller does not have to branch: bucketCountFor returns 1, the write
    // routes exactly as forWrite does, and the read fan-out is a list of one.
    // -----------------------------------------------------------------

    /** How many buckets a tenant is split across; 1 when it is not sub-sharded. */
    public int bucketCountFor(TenantId tenantId) {
        return whaleBuckets.getOrDefault(tenantId.value(), 1);
    }

    /** True when this tenant's reads must fan out across buckets. */
    public boolean isSubSharded(TenantId tenantId) {
        return bucketCountFor(tenantId) > 1;
    }

    /**
     * The primary to write one entry to, honouring sub-sharding.
     *
     * <p>{@code discriminator} is the Tier-1 cache key digest. The same prompt therefore
     * always lands in the same bucket, which is what keeps an exact-hash lookup a single
     * hop even for a whale.
     */
    public DatabaseClient forWriteBucket(TenantId tenantId, String discriminator) {
        int buckets = bucketCountFor(tenantId);
        if (buckets == 1) {
            return forWrite(tenantId);
        }
        int bucket = SubShard.bucketOf(discriminator, buckets);
        return clientFor(router.route(SubShard.key(tenantId, bucket, buckets))).primary();
    }

    /**
     * Every read client a tenant's data could be on.
     *
     * <p>One element for a normal tenant. For a whale it is one per bucket, because a
     * SEMANTIC search cannot be routed: similar prompts hash to unrelated buckets, so
     * querying one bucket would find only the fraction of that tenant's history which
     * happened to land there. The caller fans out and merges - see
     * {@link SubShard} for why this cross-shard search is accepted here and nowhere else.
     *
     * <p>Distinct clients, because several buckets can hash to the same shard; querying
     * one shard twice would double the work and could return the same row twice.
     */
    public List<DatabaseClient> readClientsFor(TenantId tenantId) {
        int buckets = bucketCountFor(tenantId);
        if (buckets == 1) {
            return List.of(forRead(tenantId));
        }
        return SubShard.allKeys(tenantId, buckets).stream()
                .map(router::route)
                .distinct()
                .map(shard -> replicaUnhealthy.test(shard)
                        ? clientFor(shard).primary()
                        : clientFor(shard).read())
                .toList();
    }

    private ShardClients clientFor(ShardId shard) {
        ShardClients found = clients.get(shard);
        if (found == null) {
            throw new IllegalStateException("no database client for shard " + shard);
        }
        return found;
    }

    /**
     * The read client for a named shard, for health probing rather than for serving.
     *
     * <p>{@code forRead(TenantId)} is the serving path and needs a tenant to hash. A
     * monitor has no tenant - it wants to ask every shard a question - so it addresses
     * shards directly. Returns the same replica-preferring client, which is the point:
     * measuring lag on the primary would always read zero and prove nothing.
     *
     * @throws IllegalArgumentException if the shard is not part of this topology, rather
     *     than returning null for a caller to dereference
     */
    public DatabaseClient readClientFor(ShardId shard) {
        ShardClients shardClients = clients.get(shard);
        if (shardClients == null) {
            throw new IllegalArgumentException("unknown shard: " + shard);
        }
        return shardClients.read();
    }

    /** True when the named shard has at least one replica configured. */
    public boolean hasReplicas(ShardId shard) {
        ShardClients shardClients = clients.get(shard);
        return shardClients != null && shardClients.replicaCount() > 0;
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
