package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.r2dbc.core.DatabaseClient;

import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Dual-write: the mode that lets a migration run without refusing anyone.
 *
 * <p>Two properties matter and neither is visible from the happy path. A write must reach
 * <b>both</b> shards, or the cutover loses whatever arrived during the copy. And a read
 * must come from the <b>source</b> whatever the local ring says, because the target is
 * incomplete until the copy finishes - and during a rolling restart half the pods have
 * already moved on to the new ring.
 */
class ShardedDatabaseDualWriteTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final ShardId SHARD_A = ShardId.of("shard-a");
    private static final ShardId SHARD_B = ShardId.of("shard-b");

    private final DatabaseClient primaryA = Mockito.mock(DatabaseClient.class);
    private final DatabaseClient replicaA = Mockito.mock(DatabaseClient.class);
    private final DatabaseClient primaryB = Mockito.mock(DatabaseClient.class);

    /** Both shards present, with acme routed to one of them by the ring. */
    private ShardedDatabase database() {
        return new ShardedDatabase(
                ShardRouter.over(List.of(SHARD_A, SHARD_B)),
                Map.of(
                        SHARD_A, new ShardedDatabase.ShardClients(primaryA, List.of(replicaA)),
                        SHARD_B, new ShardedDatabase.ShardClients(primaryB, List.of())));
    }

    @Test
    @DisplayName("with no migration, a write goes to exactly one shard")
    void singleWriteNormally() {
        ShardedDatabase database = database();

        assertThat(database.forWriteAll(ACME)).hasSize(1);
        assertThat(database.forWriteAll(ACME).get(0)).isEqualTo(database.forWrite(ACME));
    }

    @Test
    @DisplayName("while dual-writing, a write goes to BOTH shards, source first")
    void dualWriteReachesBoth() {
        ShardedDatabase database = database();
        database.useMigrationState(
                tenant -> MigrationState.dualWrite(SHARD_A, SHARD_B));

        List<DatabaseClient> targets = database.forWriteAll(ACME);

        assertThat(targets).containsExactly(primaryA, primaryB);
        // Source first on purpose: if the target is unreachable, the row is still durable
        // on the shard that is currently serving reads.
        assertThat(targets.get(0)).isEqualTo(primaryA);
    }

    @Test
    @DisplayName("a dual write targets primaries only, never a replica")
    void dualWriteNeverTargetsAReplica() {
        ShardedDatabase database = database();
        database.useMigrationState(tenant -> MigrationState.dualWrite(SHARD_A, SHARD_B));

        // A write issued to a replica simply fails. Fanning out to one would turn a
        // migration into a stream of errors on half the writes.
        assertThat(database.forWriteAll(ACME)).doesNotContain(replicaA);
    }

    @Test
    @DisplayName("while dual-writing, reads come from the SOURCE whatever the ring says")
    void readsComeFromTheSource() {
        ShardedDatabase database = database();

        // Force the ring's answer to be the TARGET, as it would be on a pod that has
        // already picked up the new configuration during a rolling restart.
        ShardId ringSays = database.shardOf(ACME);
        ShardId other = ringSays.equals(SHARD_A) ? SHARD_B : SHARD_A;
        database.useMigrationState(tenant -> MigrationState.dualWrite(other, ringSays));

        DatabaseClient read = database.forRead(ACME);

        // The source holds the historical rows AND everything written since, so it is the
        // only shard guaranteed complete for the whole window. Reading the ring's answer
        // here would miss rows that have not been copied yet - and would do so on some
        // pods and not others, which is the hardest kind of bug to reproduce.
        DatabaseClient expected = other.equals(SHARD_A) ? replicaA : primaryB;
        assertThat(read).isEqualTo(expected);
    }

    @Test
    @DisplayName("a paused tenant does not change write routing")
    void pauseDoesNotFanOut() {
        ShardedDatabase database = database();
        database.useMigrationState(tenant -> MigrationState.paused());

        // PAUSE stops traffic at the filter instead. If it also fanned out writes, the
        // two mechanisms would be doing each other's job and neither would be testable.
        assertThat(database.forWriteAll(ACME)).hasSize(1);
        assertThat(database.forRead(ACME)).isEqualTo(
                database.shardOf(ACME).equals(SHARD_A) ? replicaA : primaryB);
    }

    @Test
    @DisplayName("a bucketed write fans out to both shards for an ordinary tenant")
    void bucketedDualWriteReachesBoth() {
        ShardedDatabase database = database();
        database.useMigrationState(tenant -> MigrationState.dualWrite(SHARD_A, SHARD_B));

        // Tier 2 writes go through forWriteBucketAll, not forWriteAll, because a whale's
        // entries are bucketed by cache key. For a normal tenant the bucket count is 1,
        // so this must behave exactly like forWriteAll - which is the path every real
        // cache write takes during a migration.
        assertThat(database.forWriteBucketAll(ACME, "9f86d081"))
                .containsExactly(primaryA, primaryB);
    }

    @Test
    @DisplayName("with no migration, a bucketed write goes to one shard")
    void bucketedSingleWriteNormally() {
        ShardedDatabase database = database();

        assertThat(database.forWriteBucketAll(ACME, "9f86d081")).hasSize(1);
    }

    @Test
    @DisplayName("dual-write is REFUSED for a sub-sharded whale, not silently wrong")
    void whaleDualWriteIsRefused() {
        ShardedDatabase database = new ShardedDatabase(
                ShardRouter.over(List.of(SHARD_A, SHARD_B)),
                Map.of(
                        SHARD_A, new ShardedDatabase.ShardClients(primaryA, List.of(replicaA)),
                        SHARD_B, new ShardedDatabase.ShardClients(primaryB, List.of())),
                Map.of(ACME.value(), 4));
        database.useMigrationState(tenant -> MigrationState.dualWrite(SHARD_A, SHARD_B));

        // A whale's rows span four buckets and the planner emits no per-bucket source and
        // target. Writing to one arbitrary pair and calling it replicated would lose rows
        // at the cutover while reporting success, so this refuses and names the remedy.
        assertThatThrownBy(() -> database.forWriteBucketAll(ACME, "9f86d081"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not supported for sub-sharded tenant")
                .hasMessageContaining("pause this tenant");
    }

    @Test
    @DisplayName("a sub-sharded whale writes normally when NOT migrating")
    void whaleWritesNormallyWithoutMigration() {
        ShardedDatabase database = new ShardedDatabase(
                ShardRouter.over(List.of(SHARD_A, SHARD_B)),
                Map.of(
                        SHARD_A, new ShardedDatabase.ShardClients(primaryA, List.of(replicaA)),
                        SHARD_B, new ShardedDatabase.ShardClients(primaryB, List.of())),
                Map.of(ACME.value(), 4));

        // The refusal must be scoped to the migration. A whale that is not being moved is
        // an ordinary, supported configuration and must keep serving.
        assertThat(database.forWriteBucketAll(ACME, "9f86d081")).hasSize(1);
    }

    @Test
    @DisplayName("clearing the lookup restores ordinary single-shard behaviour")
    void nullLookupIsSafe() {
        ShardedDatabase database = database();
        database.useMigrationState(tenant -> MigrationState.dualWrite(SHARD_A, SHARD_B));
        assertThat(database.forWriteAll(ACME)).hasSize(2);

        database.useMigrationState(null);

        // A deployment with no registry at all must behave exactly as before.
        assertThat(database.forWriteAll(ACME)).hasSize(1);
    }
}
