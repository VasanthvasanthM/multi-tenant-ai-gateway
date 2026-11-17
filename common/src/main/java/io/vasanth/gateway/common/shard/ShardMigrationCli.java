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
/*
 * SystemPrintln and DoNotTerminateVM are suppressed for this class only, and for the same
 * reason ArchitectureRulesTest.noSystemOut exempts *Cli: in a command-line tool stdout is
 * not a side channel, it is the product. This one writes a TSV plan on stdout for another
 * script to consume and its human summary on stderr, precisely so a pipe can separate them,
 * and its exit code is part of its contract - `cli ... && migrate-all ...` depends on a
 * non-zero exit stopping the chain.
 *
 * Suppressed here rather than excluded from the ruleset, so the rule keeps protecting every
 * service class, where a console write really does bypass the structured logger.
 *
 * DoNotTerminateVM needed suppressing too until System.exit was confined to main(). PMD's
 * UnnecessaryWarningSuppression rule is what pointed that out - a stale suppression is the
 * kind of thing that otherwise survives forever, quietly disabling a rule nobody meant to
 * disable any more.
 */
@SuppressWarnings("PMD.SystemPrintln")
public final class ShardMigrationCli {

    private ShardMigrationCli() {
    }

    /**
     * The parsed command line.
     *
     * <p>Extracted from {@code main} because parsing inline needed {@code ++i} inside the
     * loop header's own control variable - which works, and which PMD flags as
     * {@code AvoidReassigningLoopVariables} for the good reason that a reader cannot tell
     * how far the loop advanced without simulating it. A while loop with an explicit
     * cursor says the same thing without the trick, and it takes the branching out of
     * {@code main}, whose cyclomatic complexity was 11.
     */
    record Options(String before, String after, int virtualNodes, boolean tsv) {
    }

    static Options parse(String[] args) {
        String before = null;
        String after = null;
        boolean tsv = false;
        int virtualNodes = ConsistentHashRing.DEFAULT_VIRTUAL_NODES;

        int cursor = 0;
        while (cursor < args.length) {
            String flag = args[cursor];
            switch (flag) {
                case "--before" -> before = valueOf(args, ++cursor, flag);
                case "--after" -> after = valueOf(args, ++cursor, flag);
                case "--virtual-nodes" ->
                        virtualNodes = Integer.parseInt(valueOf(args, ++cursor, flag));
                case "--tsv" -> tsv = true;
                default -> fail("unknown argument: " + flag);
            }
            cursor++;
        }

        if (before == null || after == null) {
            fail("--before and --after are required, as comma-separated shard ids");
        }
        return new Options(before, after, virtualNodes, tsv);
    }

    /**
     * Thrown for a usage error instead of exiting.
     *
     * <p>{@code fail()} used to call {@link System#exit} directly, which made every error
     * path untestable: a test that exercised one would take the JVM down with it. So the
     * argument handling - the part of a CLI most likely to be wrong - had no tests at all
     * while looking perfectly reasonable.
     *
     * <p>Now {@code System.exit} appears in {@code main} and nowhere else, and everything
     * it calls is an ordinary function a test can drive.
     */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws IOException {
        try {
            int exitCode = run(args);
            if (exitCode != 0) {
                System.exit(exitCode);
            }
        } catch (UsageException usage) {
            System.err.println("error: " + usage.getMessage());
            System.err.println();
            System.err.println("usage: ShardMigrationCli --before a,b,c --after a,b,c,d"
                    + " [--tsv] [--virtual-nodes N]   (tenant ids on stdin, one per line)");
            System.exit(1);
        }
    }

    /**
     * The whole tool, returning the process exit code rather than setting it.
     *
     * @return 0 normally, 2 when the plan contains lateral moves and must not be run
     */
    static int run(String[] args) throws IOException {
        Options options = parse(args);

        List<TenantId> tenants = readTenants();
        if (tenants.isEmpty()) {
            fail("no tenants on stdin; pipe one tenant id per line");
        }

        ShardMigrationPlan plan = ShardMigrationPlan.between(
                ConsistentHashRing.of(shards(options.before()), options.virtualNodes()),
                ConsistentHashRing.of(shards(options.after()), options.virtualNodes()),
                tenants);

        // Summary to stderr, plan to stdout: `> plan.tsv` then captures only the lines the
        // driver parses, while the operator still sees the percentages and the verdict.
        System.err.print(plan.summary());

        if (!plan.lateralMoves().isEmpty()) {
            // Non-zero rather than printing a plan that must not be run. A caller doing
            // `cli ... > plan.tsv && migrate-all ...` then stops here.
            System.err.println();
            System.err.println("REFUSING to emit a plan: " + plan.lateralMoves().size()
                    + " lateral move(s). A consistent hash ring cannot produce these, so the"
                    + " two rings were built differently - check --virtual-nodes and the"
                    + " exact shard id spelling on both sides.");
            return 2;
        }

        if (options.tsv()) {
            System.out.print(plan.asTsv());
        }
        return 0;
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

    static List<ShardId> shards(String commaSeparated) {
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
        throw new UsageException(message);
    }
}
