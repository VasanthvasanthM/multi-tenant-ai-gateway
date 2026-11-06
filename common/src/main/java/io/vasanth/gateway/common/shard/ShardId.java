package io.vasanth.gateway.common.shard;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies one physical storage shard, e.g. {@code pg-shard-0}.
 *
 * <p>Resolved by configuration to a concrete connection target. The ring deals only in these
 * identifiers so that it can be unit-tested exhaustively without a database anywhere near
 * it - which is what makes the rebalancing proofs cheap to run.
 */
public record ShardId(String value) implements Comparable<ShardId> {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    public ShardId {
        Objects.requireNonNull(value, "shard id must not be null");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid shard id: must match " + VALID.pattern());
        }
    }

    public static ShardId of(String value) {
        return new ShardId(value);
    }

    @Override
    public int compareTo(ShardId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
