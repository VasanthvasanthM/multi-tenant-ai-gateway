package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shard topology configuration, and the mistakes it refuses to boot with. */
class ShardPropertiesTest {

    private static ShardProperties.Shard shard(String id, String url, String... replicas) {
        return new ShardProperties.Shard(id, url, List.of(replicas));
    }

    @Test
    @DisplayName("no shards means unsharded, which is the default")
    void emptyMeansUnsharded() {
        ShardProperties properties = new ShardProperties(null, 0);

        // Null and empty both mean "use the auto-configured datasource". A sharding
        // feature that forced every deployment to declare a topology would make the
        // simple case harder in order to serve a case almost nobody has yet.
        assertThat(properties.isSharded()).isFalse();
        assertThat(properties.shards()).isEmpty();
    }

    @Test
    @DisplayName("virtualNodes defaults to 150 when unset or invalid")
    void virtualNodesDefaults() {
        assertThat(new ShardProperties(List.of(), 0).virtualNodes()).isEqualTo(150);
        assertThat(new ShardProperties(List.of(), -5).virtualNodes()).isEqualTo(150);
        // One point per shard distributes badly; 150 brings the spread to a few percent.
        assertThat(new ShardProperties(List.of(), 1).virtualNodes()).isEqualTo(1);
        assertThat(new ShardProperties(List.of(), 300).virtualNodes()).isEqualTo(300);
    }

    @Test
    @DisplayName("duplicate shard ids are refused at startup")
    void duplicateIdsAreRefused() {
        // The ring takes a Set, so duplicates would collapse SILENTLY: two configured
        // shards become one, half the configured capacity is never used, and nothing
        // reports it. Failing to boot is the kinder outcome.
        assertThatThrownBy(() -> new ShardProperties(List.of(
                shard("shard-a", "r2dbc:postgresql://pg-a:5432/aigw"),
                shard("shard-a", "r2dbc:postgresql://pg-b:5432/aigw")), 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate shard ids")
                .hasMessageContaining("shard-a");
    }

    @Test
    @DisplayName("distinct ids pointing at the same URL are allowed")
    void sameUrlDifferentIdsIsAllowed() {
        // Deliberately permitted: it is how a shard split is staged. Both ids point at
        // the same database while data is copied, then one is repointed.
        ShardProperties properties = new ShardProperties(List.of(
                shard("shard-a", "r2dbc:postgresql://pg:5432/aigw"),
                shard("shard-b", "r2dbc:postgresql://pg:5432/aigw")), 150);

        assertThat(properties.isSharded()).isTrue();
        assertThat(properties.byId()).containsOnlyKeys("shard-a", "shard-b");
    }

    @Test
    @DisplayName("replica URLs default to empty, meaning read from the primary")
    void replicasDefaultToEmpty() {
        ShardProperties.Shard withNull =
                new ShardProperties.Shard("shard-a", "r2dbc:postgresql://pg:5432/aigw", null);

        assertThat(withNull.replicaUrls()).isEmpty();
        assertThat(withNull.hasReplicas()).isFalse();
    }

    @Test
    @DisplayName("a shard with replicas reports them")
    void replicasAreReported() {
        ShardProperties.Shard withReplicas = shard(
                "shard-a",
                "r2dbc:postgresql://pg-a:5432/aigw",
                "r2dbc:postgresql://pg-a-r1:5432/aigw",
                "r2dbc:postgresql://pg-a-r2:5432/aigw");

        assertThat(withReplicas.hasReplicas()).isTrue();
        assertThat(withReplicas.replicaUrls()).hasSize(2);
    }

    @Test
    @DisplayName("the shard list is immutable once built")
    void shardListIsImmutable() {
        List<ShardProperties.Shard> mutable = new java.util.ArrayList<>();
        mutable.add(shard("shard-a", "r2dbc:postgresql://pg-a:5432/aigw"));

        ShardProperties properties = new ShardProperties(mutable, 150);
        mutable.clear();

        // Topology is read on every request path construction; a caller mutating the
        // list afterwards must not be able to change routing.
        assertThat(properties.shards()).hasSize(1);
        assertThatThrownBy(() -> properties.shards().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("byId maps every shard")
    void byIdMapsEverything() {
        ShardProperties properties = new ShardProperties(List.of(
                shard("shard-a", "r2dbc:postgresql://pg-a:5432/aigw"),
                shard("shard-b", "r2dbc:postgresql://pg-b:5432/aigw"),
                shard("shard-c", "r2dbc:postgresql://pg-c:5432/aigw")), 150);

        assertThat(properties.byId()).hasSize(3)
                .containsOnlyKeys("shard-a", "shard-b", "shard-c");
        assertThat(properties.byId().get("shard-b").url()).contains("pg-b");
    }
}
