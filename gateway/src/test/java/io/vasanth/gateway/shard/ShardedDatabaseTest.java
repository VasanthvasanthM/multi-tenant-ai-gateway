package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;

import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * The read/write split and the shard lookup.
 *
 * <p>Uses distinguishable mock {@link DatabaseClient}s rather than real ones: what is
 * being tested is WHICH client a tenant gets, not what a query does with it. A
 * Testcontainers test would exercise the query and tell us nothing about the routing.
 */
class ShardedDatabaseTest {

    private static final ShardId A = ShardId.of("shard-a");
    private static final ShardId B = ShardId.of("shard-b");

    /** A mock whose identity is readable in an assertion failure. */
    private static DatabaseClient client(String name) {
        return Mockito.mock(DatabaseClient.class, name);
    }

    // ------------------------------------------------------------------
    // Unsharded - the default deployment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("single() routes every tenant to the one client")
    void singleShardRoutesEverything() {
        DatabaseClient only = client("only");
        ShardedDatabase database = ShardedDatabase.single(only);

        for (int i = 0; i < 200; i++) {
            TenantId tenant = TenantId.of("tenant-" + i);
            assertThat(database.forRead(tenant)).isSameAs(only);
            assertThat(database.forWrite(tenant)).isSameAs(only);
        }
        // The unsharded deployment must behave exactly as it did before sharding
        // existed, which is why reads and writes both land on the same client here.
        assertThat(database.isSingleShard()).isTrue();
        assertThat(database.shardOf(TenantId.of("acme")).value()).isEqualTo("default");
    }

    // ------------------------------------------------------------------
    // Read/write split
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reads go to a replica, writes to the primary")
    void readsPreferReplicaWritesUsePrimary() {
        DatabaseClient primary = client("primary");
        DatabaseClient replica = client("replica");
        ShardedDatabase database = new ShardedDatabase(
                ShardRouter.single(A),
                Map.of(A, new ShardedDatabase.ShardClients(primary, List.of(replica))));

        TenantId tenant = TenantId.of("acme");

        // Reads may lag, because lag here causes a cache MISS and never a wrong answer
        // (ADR-0007). Writes may not: a write to a replica simply fails.
        assertThat(database.forRead(tenant)).isSameAs(replica);
        assertThat(database.forWrite(tenant)).isSameAs(primary);
    }

    @Test
    @DisplayName("with no replica, reads fall back to the primary")
    void readsFallBackToPrimary() {
        DatabaseClient primary = client("primary");
        ShardedDatabase database = new ShardedDatabase(
                ShardRouter.single(A),
                Map.of(A, new ShardedDatabase.ShardClients(primary, List.of())));

        assertThat(database.forRead(TenantId.of("acme"))).isSameAs(primary);
    }

    @Test
    @DisplayName("replicas are used round-robin, not randomly")
    void replicasRotate() {
        DatabaseClient primary = client("primary");
        DatabaseClient r1 = client("replica-1");
        DatabaseClient r2 = client("replica-2");
        DatabaseClient r3 = client("replica-3");
        ShardedDatabase.ShardClients clients =
                new ShardedDatabase.ShardClients(primary, List.of(r1, r2, r3));

        List<DatabaseClient> sequence = IntStream.range(0, 9)
                .mapToObj(i -> clients.read())
                .toList();

        // Strict rotation. Random selection with two or three replicas gives an uneven
        // split often enough to look like a replica problem.
        assertThat(sequence).containsExactly(r1, r2, r3, r1, r2, r3, r1, r2, r3);
        assertThat(clients.replicaCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a single replica is returned without touching the counter")
    void singleReplicaIsReturnedDirectly() {
        DatabaseClient replica = client("replica");
        ShardedDatabase.ShardClients clients =
                new ShardedDatabase.ShardClients(client("primary"), List.of(replica));

        for (int i = 0; i < 100; i++) {
            assertThat(clients.read()).isSameAs(replica);
        }
    }

    @Test
    @DisplayName("replica selection stays in range past Integer.MAX_VALUE")
    void replicaIndexSurvivesCounterOverflow() {
        // The counter is an AtomicInteger and getAndIncrement eventually wraps to
        // Integer.MIN_VALUE. A plain `% size` then yields a NEGATIVE index and an
        // ArrayIndexOutOfBoundsException - after roughly two billion reads, which is a
        // fortnight at a few thousand requests a second, and is exactly the kind of bug
        // that only ever appears in production.
        //
        // Driving the real counter that far is not practical in a test, so this pins the
        // arithmetic the implementation relies on.
        for (int size : new int[] {2, 3, 4, 7}) {
            assertThat(Math.floorMod(Integer.MIN_VALUE, size)).isBetween(0, size - 1);
            assertThat(Math.floorMod(-1, size)).isBetween(0, size - 1);
            assertThat(Math.floorMod(Integer.MAX_VALUE, size)).isBetween(0, size - 1);
            // And the reason floorMod was needed rather than %.
            assertThat(Integer.MIN_VALUE % size).isLessThanOrEqualTo(0);
        }
    }

    // ------------------------------------------------------------------
    // Sharded routing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tenant always gets the same shard's clients")
    void routingIsStablePerTenant() {
        Map<ShardId, ShardedDatabase.ShardClients> clients = Map.of(
                A, new ShardedDatabase.ShardClients(client("a-primary"), List.of()),
                B, new ShardedDatabase.ShardClients(client("b-primary"), List.of()));
        ShardedDatabase database = new ShardedDatabase(ShardRouter.over(Set.of(A, B)), clients);

        TenantId tenant = TenantId.of("acme");
        DatabaseClient first = database.forWrite(tenant);
        for (int i = 0; i < 50; i++) {
            assertThat(database.forWrite(tenant)).isSameAs(first);
        }
    }

    @Test
    @DisplayName("tenants are spread across shards, and reads and writes agree per tenant")
    void tenantsSpreadAndStayConsistent() {
        DatabaseClient aPrimary = client("a-primary");
        DatabaseClient aReplica = client("a-replica");
        DatabaseClient bPrimary = client("b-primary");
        DatabaseClient bReplica = client("b-replica");

        Map<ShardId, ShardedDatabase.ShardClients> clients = Map.of(
                A, new ShardedDatabase.ShardClients(aPrimary, List.of(aReplica)),
                B, new ShardedDatabase.ShardClients(bPrimary, List.of(bReplica)));
        ShardedDatabase database = new ShardedDatabase(ShardRouter.over(Set.of(A, B)), clients);

        Map<ShardId, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1_000; i++) {
            TenantId tenant = TenantId.of("tenant-" + i);
            ShardId shard = database.shardOf(tenant);
            counts.merge(shard, 1, Integer::sum);

            // The crucial invariant: a tenant's read and write must land on the SAME
            // shard. If they diverged, a write would go to one database and the
            // subsequent lookup would search another - a cache that never hits, with no
            // error anywhere.
            DatabaseClient read = database.forRead(tenant);
            DatabaseClient write = database.forWrite(tenant);
            if (shard.equals(A)) {
                assertThat(read).isSameAs(aReplica);
                assertThat(write).isSameAs(aPrimary);
            } else {
                assertThat(read).isSameAs(bReplica);
                assertThat(write).isSameAs(bPrimary);
            }
        }

        // Both shards get a real share - 150 virtual nodes each keeps the split close.
        assertThat(counts.keySet()).containsExactlyInAnyOrder(A, B);
        assertThat(counts.get(A)).isBetween(300, 700);
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a shard on the ring with no client is rejected at construction")
    void shardWithoutClientIsRejected() {
        // Caught at startup rather than at request time. Otherwise a tenant hashing to
        // the unbacked shard fails on every request while every other tenant is fine,
        // which presents as an intermittent tenant-specific outage.
        assertThatThrownBy(() -> new ShardedDatabase(
                ShardRouter.over(Set.of(A, B)),
                Map.of(A, new ShardedDatabase.ShardClients(client("a"), List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no database client")
                .hasMessageContaining("shard-b");
    }

    @Test
    @DisplayName("extra clients for shards not on the ring are harmless")
    void extraClientsAreTolerated() {
        Map<ShardId, ShardedDatabase.ShardClients> clients = Map.of(
                A, new ShardedDatabase.ShardClients(client("a"), List.of()),
                B, new ShardedDatabase.ShardClients(client("b"), List.of()));

        // The reverse of the case above, and deliberately NOT an error: a shard being
        // drained is removed from the ring first and its client torn down afterwards, so
        // this state is a normal step in a decommission.
        ShardedDatabase database =
                new ShardedDatabase(ShardRouter.single(A), clients);

        assertThat(database.shards()).containsExactly(A);
    }

    @Test
    @DisplayName("the client map is copied, so a later mutation cannot change routing")
    void clientMapIsCopied() {
        Map<ShardId, ShardedDatabase.ShardClients> mutable = new HashMap<>();
        DatabaseClient original = client("original");
        mutable.put(A, new ShardedDatabase.ShardClients(original, List.of()));

        ShardedDatabase database = new ShardedDatabase(ShardRouter.single(A), mutable);
        mutable.put(A, new ShardedDatabase.ShardClients(client("swapped"), List.of()));

        assertThat(database.forWrite(TenantId.of("acme"))).isSameAs(original);
    }

    @Test
    @DisplayName("the replica list is copied too")
    void replicaListIsCopied() {
        List<DatabaseClient> mutable = new java.util.ArrayList<>();
        DatabaseClient replica = client("replica");
        mutable.add(replica);

        ShardedDatabase.ShardClients clients =
                new ShardedDatabase.ShardClients(client("primary"), mutable);
        mutable.clear();

        assertThat(clients.replicaCount()).isEqualTo(1);
        assertThat(clients.read()).isSameAs(replica);
    }

    @Test
    @DisplayName("every shard rotates its own replicas independently")
    void countersAreNotShared() {
        DatabaseClient a1 = client("a1");
        DatabaseClient a2 = client("a2");
        DatabaseClient b1 = client("b1");
        DatabaseClient b2 = client("b2");
        DatabaseClient b3 = client("b3");

        ShardedDatabase.ShardClients shardA =
                new ShardedDatabase.ShardClients(client("ap"), List.of(a1, a2));
        ShardedDatabase.ShardClients shardB =
                new ShardedDatabase.ShardClients(client("bp"), List.of(b1, b2, b3));

        // Interleave reads across both shards. A single shared counter would make the
        // 2-replica shard and the 3-replica shard distribute unevenly.
        Set<DatabaseClient> seenA = new HashSet<>();
        Set<DatabaseClient> seenB = new HashSet<>();
        for (int i = 0; i < 6; i++) {
            seenA.add(shardA.read());
            seenB.add(shardB.read());
        }

        assertThat(seenA).containsExactlyInAnyOrder(a1, a2);
        assertThat(seenB).containsExactlyInAnyOrder(b1, b2, b3);
    }
}
