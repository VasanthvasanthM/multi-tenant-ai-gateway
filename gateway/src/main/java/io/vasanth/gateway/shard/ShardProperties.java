package io.vasanth.gateway.shard;

import java.time.Duration;
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
        @Min(1) int virtualNodes,
        Duration lagProbeInterval,
        Duration lagProbeTimeout,
        Map<String, Integer> whaleBuckets) {

    /** Matches ConsistentHashRing.DEFAULT_VIRTUAL_NODES. */
    private static final int DEFAULT_VIRTUAL_NODES = 150;

    /**
     * How often to measure replica lag.
     *
     * <p>Thirty seconds, which is frequent enough that an alert with a short `for:` clause
     * fires promptly and infrequent enough to be invisible against request traffic - one
     * trivial query per shard per half minute. Zero disables probing.
     */
    private static final Duration DEFAULT_LAG_PROBE_INTERVAL = Duration.ofSeconds(30);

    /**
     * Deadline for one lag query.
     *
     * <p>Short on purpose. A replica too slow to answer a trivial query within two seconds
     * is itself the problem being looked for, and a probe that hung would stop the interval
     * chain - {@code concatMap} runs passes one at a time - so the gauge would freeze at
     * its last value and read as healthy.
     */
    private static final Duration DEFAULT_LAG_PROBE_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Topology without lag-probe overrides.
     *
     * <p>Keeps the two probe settings out of every construction site that does not care
     * about them, and means existing configuration that sets only shards and virtual nodes
     * still binds to the same object it always did.
     */
    public ShardProperties(List<Shard> shards, int virtualNodes) {
        this(shards, virtualNodes, null, null, null);
    }

    /** Topology plus lag-probe settings, without whale overrides. */
    public ShardProperties(
            List<Shard> shards,
            int virtualNodes,
            Duration lagProbeInterval,
            Duration lagProbeTimeout) {
        this(shards, virtualNodes, lagProbeInterval, lagProbeTimeout, null);
    }

    // Boot's constructor binding needs to be told WHICH constructor to use once a record
    // has more than one. Without this it reports "No default constructor found" and the
    // whole sharding context fails to start - a confusing message for what is really an
    // ambiguity between the canonical constructor and the convenience overloads below.
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ShardProperties {
        shards = shards == null ? List.of() : List.copyOf(shards);
        virtualNodes = virtualNodes < 1 ? DEFAULT_VIRTUAL_NODES : virtualNodes;
        lagProbeInterval =
                lagProbeInterval == null ? DEFAULT_LAG_PROBE_INTERVAL : lagProbeInterval;
        lagProbeTimeout = lagProbeTimeout == null || lagProbeTimeout.isZero()
                || lagProbeTimeout.isNegative()
                ? DEFAULT_LAG_PROBE_TIMEOUT : lagProbeTimeout;

        // Sub-sharding is opt-in per tenant and must stay rare: it trades a bounded
        // read amplification for an unbounded write hotspot, and applying it widely
        // would turn every Tier-2 lookup into a scatter-gather. See SubShard.
        whaleBuckets = whaleBuckets == null ? Map.of() : Map.copyOf(whaleBuckets);
        List<String> invalid = whaleBuckets.entrySet().stream()
                .filter(entry -> entry.getValue() == null || entry.getValue() < 2)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!invalid.isEmpty()) {
            // One bucket is not sub-sharding, it is the default with extra steps - and it
            // would silently add a scatter-gather read path for no distribution benefit.
            throw new IllegalArgumentException(
                    "whale bucket counts must be at least 2; offending tenants: " + invalid);
        }

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
