package io.vasanth.gateway.common.shard;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Sub-shard keys for a tenant too large to fit one shard.
 *
 * <h2>The problem this solves, and the one it creates</h2>
 *
 * <p>Routing by {@code tenantId} alone means one tenant's entire working set lands on one
 * shard. For most tenants that is the whole point - it is what confines a nearest-neighbour
 * search to a single HNSW index. For a tenant an order of magnitude larger than the rest it
 * is a hot shard that caps the system: adding shards cannot help, because that tenant still
 * hashes to exactly one.
 *
 * <p>Splitting it across {@code n} buckets fixes the write and storage distribution. It
 * also <b>breaks exact-match reasoning about Tier 2</b>, and that has to be said plainly
 * rather than discovered later:
 *
 * <ul>
 *   <li><b>Tier 1 is unaffected.</b> An exact-hash lookup for a given prompt always derives
 *       the same bucket, so it goes straight to the right place.
 *   <li><b>Tier 2 cannot be routed to one bucket.</b> A semantic search looks for prompts
 *       that are <em>similar</em>, not identical, and similar prompts hash to unrelated
 *       buckets. Querying one bucket would silently find only the fraction of a whale's
 *       history that happened to land there, which presents as a mysteriously poor hit
 *       ratio for the biggest tenant.
 * </ul>
 *
 * <p>So a whale's Tier-2 reads must fan out across its buckets and merge. That is the
 * cross-shard nearest-neighbour search this design otherwise avoids entirely - the thing
 * ADR-0006 calls out as the usual reason sharded vector stores stop scaling. It is accepted
 * here, deliberately and only here, because the fan-out is bounded by one tenant's
 * configured bucket count rather than by the shard total, and because the alternative is a
 * hot shard that bounds everyone.
 *
 * <p><b>Sub-sharding is therefore opt-in per tenant and should stay rare.</b> It trades a
 * bounded read amplification for an unbounded write hotspot; applying it to every tenant
 * would convert every lookup into a scatter-gather and give up the property the whole
 * design rests on.
 */
public final class SubShard {

    /**
     * Separator between tenant and bucket.
     *
     * <p>{@code #} because {@link TenantId} forbids it - the id pattern is lowercase
     * alphanumeric with hyphens - so a sub-shard key can never collide with a real tenant
     * id, and a key that appears in a log is unambiguous about which it is.
     */
    public static final char SEPARATOR = '#';

    private SubShard() {
    }

    /**
     * The routing key for one bucket of a tenant.
     *
     * @throws IllegalArgumentException if {@code buckets} is below one or the bucket is out
     *     of range, rather than producing a key that routes somewhere arbitrary
     */
    public static String key(TenantId tenantId, int bucket, int buckets) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (buckets < 1) {
            throw new IllegalArgumentException("buckets must be at least 1, got " + buckets);
        }
        if (bucket < 0 || bucket >= buckets) {
            throw new IllegalArgumentException(
                    "bucket " + bucket + " out of range for " + buckets + " buckets");
        }
        return tenantId.value() + SEPARATOR + bucket;
    }

    /** Every bucket key for a tenant, in order. */
    public static List<String> allKeys(TenantId tenantId, int buckets) {
        if (buckets < 1) {
            throw new IllegalArgumentException("buckets must be at least 1, got " + buckets);
        }
        return IntStream.range(0, buckets)
                .mapToObj(bucket -> key(tenantId, bucket, buckets))
                .toList();
    }

    /**
     * Which bucket a discriminator falls in.
     *
     * <p>The discriminator is the Tier-1 cache key's hex digest - already a uniform hash of
     * the prompt, so no further mixing is needed and taking it modulo the bucket count is
     * sufficient. Derived from the digest rather than from {@code String.hashCode()}
     * because hashCode is not specified to be stable across JVM versions, and a bucket
     * assignment that moved between releases would orphan every entry written before it.
     *
     * <p>{@code Math.floorMod}, not {@code %}: a negative intermediate would otherwise
     * produce a negative bucket and an out-of-range key.
     */
    public static int bucketOf(String discriminator, int buckets) {
        if (buckets < 1) {
            throw new IllegalArgumentException("buckets must be at least 1, got " + buckets);
        }
        if (buckets == 1) {
            return 0;
        }
        if (discriminator == null || discriminator.isEmpty()) {
            // Everything unattributable lands in bucket 0 rather than throwing. A missing
            // discriminator is a programming error upstream, and failing a request over it
            // would be worse than concentrating a few entries.
            return 0;
        }
        return Math.floorMod(stableHash(discriminator), buckets);
    }

    /**
     * FNV-1a over the discriminator's characters.
     *
     * <p>Deliberately not {@code String.hashCode()}: its value is a documented part of the
     * String contract today, but relying on a JDK implementation detail for a persistent
     * routing decision is the kind of assumption that breaks on an upgrade and strands
     * data. FNV-1a is specified, trivial, and fixed here in source where it cannot drift.
     */
    private static int stableHash(String value) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }
}
