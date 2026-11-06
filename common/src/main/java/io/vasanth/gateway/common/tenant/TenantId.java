package io.vasanth.gateway.common.tenant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity of a tenant, and the shard key for the entire system.
 *
 * <p>The character set is deliberately narrow because a tenant id is not merely a label - it
 * is interpolated into Redis keys, used as a Kafka partition key, emitted as a Prometheus
 * label, and used to derive a position on the consistent-hash ring. Each of those is a
 * separate hazard if the value is unconstrained:
 *
 * <ul>
 *   <li>Redis keys: a {@code :} or {@code *} in a tenant id lets one tenant's key pattern
 *       match another's, which is a cross-tenant data leak, not a cosmetic problem.
 *   <li>Prometheus labels: unbounded values cause a cardinality explosion that will take
 *       the monitoring stack down before it takes the gateway down.
 *   <li>Log output: newlines permit log injection and forged audit entries.
 * </ul>
 *
 * <p>Validating once at the boundary is far cheaper than escaping correctly at every one of
 * those call sites, and impossible to forget.
 */
public record TenantId(String value) implements Comparable<TenantId> {

    /** Lowercase alphanumeric plus {@code -} and {@code _}; 3-64 characters. */
    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9_-]{2,63}$");

    public TenantId {
        Objects.requireNonNull(value, "tenant id must not be null");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid tenant id: must match " + VALID.pattern() + " but was length "
                            + value.length());
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    /**
     * Returns the Redis key prefix for this tenant, wrapped in a cluster hash tag.
     *
     * <p>Redis Cluster hashes only the substring between the first {@code {} and {@code }}
     * when deciding which slot a key belongs to. Writing every tenant-scoped key as
     * {@code {tenant:acme}:something} therefore guarantees all of one tenant's keys land on
     * one slot, which is what makes multi-key operations and Lua scripts legal in cluster
     * mode.
     *
     * <p>This is used from day one on a single Redis node even though it has no effect there.
     * Retrofitting hash tags after the key space is populated means migrating every key.
     */
    public String redisHashTag() {
        return "{tenant:" + value + "}";
    }

    @Override
    public int compareTo(TenantId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
