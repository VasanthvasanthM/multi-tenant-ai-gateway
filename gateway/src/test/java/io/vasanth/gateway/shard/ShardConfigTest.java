package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.r2dbc.core.DatabaseClient;

import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * The Spring wiring for sharding.
 *
 * <p>Worth its own test because a bean-wiring mistake here surfaces only at startup, and
 * the gateway module has no {@code @SpringBootTest} that would catch it. An
 * {@link ApplicationContextRunner} loads just this configuration with stub collaborators
 * - no Redis, no PostgreSQL, no network - because r2dbc-pool builds its pool lazily and
 * does not connect until a connection is actually requested.
 */
class ShardConfigTest {

    private static final DatabaseClient AUTO_CONFIGURED =
            Mockito.mock(DatabaseClient.class, "auto-configured");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(ShardConfig.class)
            .withBean(DatabaseClient.class, () -> AUTO_CONFIGURED)
            .withBean(R2dbcProperties.class, () -> {
                R2dbcProperties properties = new R2dbcProperties();
                properties.setUsername("aigw");
                properties.setPassword("secret");
                return properties;
            });

    // ------------------------------------------------------------------
    // Unsharded - the default
    // ------------------------------------------------------------------

    @Test
    @DisplayName("with no shards configured, the auto-configured client is used directly")
    void unshardedUsesTheAutoConfiguredClient() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ShardedDatabase.class);
            ShardedDatabase database = context.getBean(ShardedDatabase.class);

            // The whole point of the default path: an existing deployment behaves
            // exactly as it did before sharding existed.
            assertThat(database.router().isSingleShard()).isTrue();
            assertThat(database.forRead(TenantId.of("acme"))).isSameAs(AUTO_CONFIGURED);
            assertThat(database.forWrite(TenantId.of("acme"))).isSameAs(AUTO_CONFIGURED);
            assertThat(database.shardOf(TenantId.of("acme"))).isEqualTo(ShardId.of("default"));
        });
    }

    // ------------------------------------------------------------------
    // Sharded
    // ------------------------------------------------------------------

    @Test
    @DisplayName("two configured shards produce a two-shard ring with real clients")
    void twoShardsAreWired() {
        runner.withPropertyValues(
                        "gateway.sharding.shards[0].id=shard-a",
                        "gateway.sharding.shards[0].url=r2dbc:postgresql://pg-a:5432/aigw",
                        "gateway.sharding.shards[1].id=shard-b",
                        "gateway.sharding.shards[1].url=r2dbc:postgresql://pg-b:5432/aigw")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ShardedDatabase database = context.getBean(ShardedDatabase.class);

                    assertThat(database.router().shardCount()).isEqualTo(2);
                    assertThat(database.router().shards())
                            .containsExactlyInAnyOrder(
                                    ShardId.of("shard-a"), ShardId.of("shard-b"));
                    assertThat(database.router().isSingleShard()).isFalse();

                    // Real pooled clients, not the auto-configured one.
                    assertThat(database.forWrite(TenantId.of("acme")))
                            .isNotSameAs(AUTO_CONFIGURED);

                    // Tenants land on both shards.
                    long onA = java.util.stream.IntStream.range(0, 500)
                            .mapToObj(i -> database.shardOf(TenantId.of("tenant-" + i)))
                            .filter(ShardId.of("shard-a")::equals)
                            .count();
                    assertThat(onA).isBetween(150L, 350L);
                });
    }

    @Test
    @DisplayName("replica URLs produce replica clients, and reads use them")
    void replicasAreWired() {
        runner.withPropertyValues(
                        "gateway.sharding.shards[0].id=shard-a",
                        "gateway.sharding.shards[0].url=r2dbc:postgresql://pg-a:5432/aigw",
                        "gateway.sharding.shards[0].replica-urls[0]="
                                + "r2dbc:postgresql://pg-a-r1:5432/aigw",
                        "gateway.sharding.shards[0].replica-urls[1]="
                                + "r2dbc:postgresql://pg-a-r2:5432/aigw")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ShardedDatabase database = context.getBean(ShardedDatabase.class);
                    TenantId tenant = TenantId.of("acme");

                    // Reads and writes must be DIFFERENT clients now: a read may lag,
                    // a write may not.
                    assertThat(database.forRead(tenant))
                            .isNotSameAs(database.forWrite(tenant));

                    // And the two replicas rotate.
                    assertThat(database.forRead(tenant))
                            .isNotSameAs(database.forRead(tenant));
                });
    }

    @Test
    @DisplayName("a custom virtual-node count is honoured")
    void virtualNodesAreConfigurable() {
        runner.withPropertyValues(
                        "gateway.sharding.virtual-nodes=32",
                        "gateway.sharding.shards[0].id=shard-a",
                        "gateway.sharding.shards[0].url=r2dbc:postgresql://pg-a:5432/aigw",
                        "gateway.sharding.shards[1].id=shard-b",
                        "gateway.sharding.shards[1].url=r2dbc:postgresql://pg-b:5432/aigw")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ShardedDatabase database = context.getBean(ShardedDatabase.class);

                    assertThat(database.router().currentRing().virtualNodes()).isEqualTo(32);
                    assertThat(database.router().currentRing().ringSize()).isEqualTo(64);
                });
    }

    // ------------------------------------------------------------------
    // Configuration mistakes must stop the application
    // ------------------------------------------------------------------

    @Test
    @DisplayName("duplicate shard ids stop the context from starting")
    void duplicateIdsFailFast() {
        runner.withPropertyValues(
                        "gateway.sharding.shards[0].id=shard-a",
                        "gateway.sharding.shards[0].url=r2dbc:postgresql://pg-a:5432/aigw",
                        "gateway.sharding.shards[1].id=shard-a",
                        "gateway.sharding.shards[1].url=r2dbc:postgresql://pg-b:5432/aigw")
                .run(context -> {
                    // Refusing to boot, rather than silently collapsing two shards into
                    // one and never using half the configured capacity.
                    assertThat(context).hasFailed();

                    // Asserted against the whole stack-trace text rather than against a
                    // root-cause type: the binder wraps a record constructor failure in
                    // its own exception chain, and pinning the exact wrapper type would
                    // break on a Spring upgrade while telling a reader nothing. What
                    // matters is that the reason reaches whoever is looking at the log.
                    java.io.StringWriter trace = new java.io.StringWriter();
                    context.getStartupFailure().printStackTrace(new java.io.PrintWriter(trace));
                    assertThat(trace.toString())
                            .contains("duplicate shard ids")
                            .contains("shard-a");
                });
    }

    @Test
    @DisplayName("a blank shard id stops the context from starting")
    void blankIdFailsFast() {
        runner.withPropertyValues(
                        "gateway.sharding.shards[0].id=",
                        "gateway.sharding.shards[0].url=r2dbc:postgresql://pg-a:5432/aigw")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("an unparseable shard URL stops the context from starting")
    void badUrlFailsFast() {
        runner.withPropertyValues(
                        "gateway.sharding.shards[0].id=shard-a",
                        "gateway.sharding.shards[0].url=not-an-r2dbc-url")
                .run(context -> {
                    // At startup, not on the first request that happens to route there.
                    assertThat(context).hasFailed();
                });
    }
}
