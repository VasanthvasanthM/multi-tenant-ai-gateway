package io.vasanth.gateway.common.shard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Command-line entry point for {@link ShardMigrationPlan}.
 *
 * <p>Without this the planner is a class nobody can run: computing a plan would mean
 * writing a throwaway {@code main} or a test, which is how an operator ends up typing
 * migration commands from memory instead.
 *
 * <pre>
 *   psql -t -A -c 'SELECT DISTINCT tenant_id FROM usage_ledger' \
 *     | java -cp common/target/classes io.vasanth.gateway.common.shard.ShardMigrationCli \
 *         --before shard-0,shard-1,shard-2,shard-3 \
 *         --after  shard-0,shard-1,shard-2,shard-3,shard-4 \
 *         --tsv > plan.tsv
 * </pre>
 *
 * <p>Tenants are read from stdin, one per line, because the authoritative list lives in a
 * database and piping avoids this tool needing a driver, a connection string, or a
 * credential. It prints the summary to stderr and the machine-readable plan to stdout, so
 * {@code > plan.tsv} captures exactly the lines the driver consumes and the human-readable
 * part still reaches the terminal.
 */
public final class ShardMigrationCli {

    private ShardMigrationCli() {
    }

    public static void main(String[] args) throws IOException {
        String before = null;
        String after = null;
        boolean tsv = false;
        int virtualNodes = ConsistentHashRing.DEFAULT_VIRTUAL_NODES;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--before" -> before = valueOf(args, ++i, "--before");
                case "--after" -> after = valueOf(args, ++i, "--after");
                case "--virtual-nodes" ->
                        virtualNodes = Integer.parseInt(valueOf(args, ++i, "--virtual-nodes"));
                case "--tsv" -> tsv = true;
                default -> fail("unknown argument: " + args[i]);
            }
        }

        if (before == null || after == null) {
            fail("--before and --after are required, as comma-separated shard ids");
        }

        List<TenantId> tenants = readTenants();
        if (tenants.isEmpty()) {
            fail("no tenants on stdin; pipe one tenant id per line");
        }

        ShardMigrationPlan plan = ShardMigrationPlan.between(
                ConsistentHashRing.of(shards(before), virtualNodes),
                ConsistentHashRing.of(shards(after), virtualNodes),
                tenants);

        // Summary to stderr, plan to stdout: `> plan.tsv` then captures only the lines the
        // driver parses, while the operator still sees the percentages and the verdict.
        System.err.print(plan.summary());

        if (!plan.lateralMoves().isEmpty()) {
            // Exit non-zero rather than printing a plan that must not be run. A caller
            // doing `cli ... > plan.tsv && migrate-all ...` then stops here.
            System.err.println();
            System.err.println("REFUSING to emit a plan: " + plan.lateralMoves().size()
                    + " lateral move(s). A consistent hash ring cannot produce these, so the"
                    + " two rings were built differently - check --virtual-nodes and the"
                    + " exact shard id spelling on both sides.");
            System.exit(2);
        }

        if (tsv) {
            System.out.print(plan.asTsv());
        }
    }

    private static List<TenantId> readTenants() throws IOException {
        // LinkedHashSet, because a duplicated tenant would be copied twice. Dropping the
        // duplicate silently is right here: the plan is about the SET of tenants, and
        // `SELECT DISTINCT` is easy to forget.
        Set<TenantId> unique = new LinkedHashSet<>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    unique.add(TenantId.of(trimmed));
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private static List<ShardId> shards(String commaSeparated) {
        List<ShardId> shards = new ArrayList<>();
        for (String raw : commaSeparated.split(",")) {
            String id = raw.trim();
            if (!id.isEmpty()) {
                shards.add(ShardId.of(id));
            }
        }
        if (shards.isEmpty()) {
            fail("no shard ids parsed from: " + commaSeparated);
        }
        return shards;
    }

    private static String valueOf(String[] args, int index, String flag) {
        if (index >= args.length) {
            fail(flag + " needs a value");
        }
        return args[index];
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.err.println();
        System.err.println("usage: ShardMigrationCli --before a,b,c --after a,b,c,d [--tsv]"
                + " [--virtual-nodes N]   (tenant ids on stdin, one per line)");
        System.exit(1);
    }
}
