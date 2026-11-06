package io.vasanth.gateway.shard;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shard topology for the Tier-2 cache.
 *
 * <h2>Empty means unsharded, and that is the default</h2>
 *
 * <p>With no {@code gateway.sharding.shards} configured, the gateway uses the single
 * {@code DatabaseClient} Spring Boot auto-configures from {@code spring.r2dbc.url} -
 * exactly as it did before sharding existed. Every existing deployment, every
 * integration test and the whole local setup keep working untouched.
 *
 * <p>That matters more than it looks: a sharding feature that forces every deployment
 * to declare a topology makes the simple case harder in order to support a case almost
 * nobody has yet.
 *
 * <h2>Example</h2>
 *
 * <pre>
 * gateway:
 *   sharding:
 *     shards:
 *       - id: shard-a
 *         url: r2dbc:postgresql://pg-a:5432/aigw
 *         replica-urls:
 *           - r2dbc:postgresql://pg-a-replica:5432/aigw
 *       - id: shard-b
 *         url: r2dbc:postgresql://pg-b:5432/aigw
 * </pre>
 *
 * <p>Credentials are NOT per shard. They come from {@code spring.r2dbc.username} and
 * {@code password}, which in a cluster are injected from a Secret. Per-shard
 * credentials in this file would mean passwords in values.yaml, visible in
 * {@code helm get values}, in CI logs, and in git.
 *
 * @param shards the shards, or empty for an unsharded deployment
 * @param virtualNodes virtual nodes per shard on the hash ring
 */
@ConfigurationProperties(prefix = "gateway.sharding")
public record ShardProperties(
        @Valid List<Shard> shards,
        @Min(1) int virtualNodes) {

    /** Matches ConsistentHashRing.DEFAULT_VIRTUAL_NODES. */
    private static final int DEFAULT_VIRTUAL_NODES = 150;

    public ShardProperties {
        shards = shards == null ? List.of() : List.copyOf(shards);
        virtualNodes = virtualNodes < 1 ? DEFAULT_VIRTUAL_NODES : virtualNodes;

        // Duplicate ids would collapse silently in the ring - ConsistentHashRing takes a
        // Set - so two configured shards would become one and half the configured
        // capacity would simply never be used, with nothing reporting it.
        Set<String> ids = shards.stream().map(Shard::id).collect(Collectors.toSet());
        if (ids.size() != shards.size()) {
            throw new IllegalArgumentException(
                    "duplicate shard ids in gateway.sharding.shards: "
                            + shards.stream().map(Shard::id).sorted().toList());
        }
    }

    public boolean isSharded() {
        return !shards.isEmpty();
    }

    public Map<String, Shard> byId() {
        return shards.stream().collect(Collectors.toMap(Shard::id, shard -> shard));
    }

    /**
     * One shard: a primary and any number of read replicas.
     *
     * @param id stable identifier, and the value hashed onto the ring. **Renaming it
     *     remaps every tenant**, so it must outlive the hostname it happens to point at
     *     today - use {@code shard-a}, never {@code pg-prod-eu-west-1b}
     * @param url the primary, which takes every write
     * @param replicaUrls read replicas, or empty to read from the primary
     */
    public record Shard(
            @NotBlank String id,
            @NotBlank String url,
            List<String> replicaUrls) {

        public Shard {
            replicaUrls = replicaUrls == null ? List.of() : List.copyOf(replicaUrls);
        }

        public boolean hasReplicas() {
            return !replicaUrls.isEmpty();
        }
    }
}
