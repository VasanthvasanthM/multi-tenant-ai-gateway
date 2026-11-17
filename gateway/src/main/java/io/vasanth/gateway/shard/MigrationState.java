package io.vasanth.gateway.shard;

import java.util.Objects;

import io.vasanth.gateway.common.shard.ShardId;

/**
 * What is happening to one tenant's data right now.
 *
 * <p>Two strategies for the same problem - rows written while a copy is running would
 * otherwise be left behind on the source - and they trade in opposite directions.
 *
 * <table>
 *   <caption>Choosing a mode</caption>
 *   <tr><th></th><th>{@code PAUSE}</th><th>{@code DUAL}</th></tr>
 *   <tr><td>the tenant</td><td>briefly refused</td><td><b>served normally</b></td></tr>
 *   <tr><td>writes</td><td>none happen</td><td>go to both shards</td></tr>
 *   <tr><td>needs</td><td>nothing</td><td>both shards reachable and healthy</td></tr>
 *   <tr><td>if the target is down</td><td>n/a</td><td>the write fails on one side</td></tr>
 * </table>
 *
 * <p>{@code DUAL} is the right default for a production migration: nobody is refused.
 * {@code PAUSE} stays because it is the only option that needs no cooperation from the
 * write path at all - which matters for the tables written by services that do not route
 * by shard (see the class javadoc on {@link MigrationRegistry}).
 *
 * @param mode which strategy applies
 * @param from the shard that holds the tenant's data today; null for {@code PAUSE}
 * @param to the shard being copied to; null for {@code PAUSE}
 */
public record MigrationState(Mode mode, ShardId from, ShardId to) {

    public enum Mode {
        /** Refuse this tenant's requests entirely for the seconds a copy takes. */
        PAUSE,
        /** Serve normally, but write to the source and the target. */
        DUAL
    }

    public MigrationState {
        Objects.requireNonNull(mode, "mode must not be null");
        if (mode == Mode.DUAL) {
            Objects.requireNonNull(from, "DUAL needs a source shard");
            Objects.requireNonNull(to, "DUAL needs a target shard");
            if (from.equals(to)) {
                // Would silently degrade to a normal single write while the operator
                // believes both shards are being kept current.
                throw new IllegalArgumentException(
                        "DUAL from and to must differ, both were " + from);
            }
        }
    }

    public static MigrationState paused() {
        return new MigrationState(Mode.PAUSE, null, null);
    }

    public static MigrationState dualWrite(ShardId from, ShardId to) {
        return new MigrationState(Mode.DUAL, from, to);
    }

    public boolean isPause() {
        return mode == Mode.PAUSE;
    }

    public boolean isDual() {
        return mode == Mode.DUAL;
    }

    /**
     * Parses the Redis field value.
     *
     * <p>{@code pause} or {@code dual:<from>:<to>}. Returns null for anything
     * unrecognised rather than throwing: a malformed entry written by hand must not stop
     * the refresh for every other tenant, and the caller logs it.
     */
    static MigrationState parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        // Literal first, so the comparison cannot NPE however this is called later.
        if ("pause".equalsIgnoreCase(value)) {
            return paused();
        }
        String[] parts = value.split(":", 3);
        if (parts.length == 3 && "dual".equalsIgnoreCase(parts[0])
                && !parts[1].isBlank() && !parts[2].isBlank()
                && !parts[1].equals(parts[2])) {
            return dualWrite(ShardId.of(parts[1]), ShardId.of(parts[2]));
        }
        return null;
    }
}
