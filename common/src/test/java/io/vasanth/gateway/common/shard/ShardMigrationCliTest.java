package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The migration planner's command line.
 *
 * <p>Argument handling is the part of a CLI most likely to be wrong and was the part with
 * no tests: {@code fail()} called {@link System#exit} directly, so any test that exercised
 * an error path would have taken the JVM down with it. Once the exit moved into
 * {@code main} alone, all of this became ordinary testable code.
 *
 * <p>The exit code matters as much as the output. The documented workflow is
 * {@code cli ... > plan.tsv && migrate-all ...}, so a non-zero exit is the only thing
 * standing between a wrongly built ring and a driver that starts copying data.
 */
class ShardMigrationCliTest {

    private final InputStream originalIn = System.in;
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    @AfterEach
    void restoreStreams() {
        System.setIn(originalIn);
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("argument parsing")
    class Parsing {

        @Test
        @DisplayName("reads every flag")
        void readsEveryFlag() {
            ShardMigrationCli.Options options = ShardMigrationCli.parse(new String[] {
                "--before", "shard-0,shard-1",
                "--after", "shard-0,shard-1,shard-2",
                "--virtual-nodes", "32",
                "--tsv"
            });

            assertThat(options.before()).isEqualTo("shard-0,shard-1");
            assertThat(options.after()).isEqualTo("shard-0,shard-1,shard-2");
            assertThat(options.virtualNodes()).isEqualTo(32);
            assertThat(options.tsv()).isTrue();
        }

        @Test
        @DisplayName("defaults the virtual-node count to the ring's own default")
        void defaultsVirtualNodes() {
            ShardMigrationCli.Options options = ShardMigrationCli.parse(
                    new String[] {"--before", "a", "--after", "a,b"});

            // Must match the ring, or a plan is computed against a DIFFERENT ring from the
            // one the gateway uses - which produces lateral moves and a refusal, if you
            // are lucky, and a wrong plan if you are not.
            assertThat(options.virtualNodes())
                    .isEqualTo(ConsistentHashRing.DEFAULT_VIRTUAL_NODES);
            assertThat(options.tsv()).isFalse();
        }

        @Test
        @DisplayName("order does not matter")
        void orderIndependent() {
            ShardMigrationCli.Options options = ShardMigrationCli.parse(new String[] {
                "--tsv", "--after", "a,b", "--virtual-nodes", "8", "--before", "a"
            });

            assertThat(options.before()).isEqualTo("a");
            assertThat(options.after()).isEqualTo("a,b");
            assertThat(options.virtualNodes()).isEqualTo(8);
            assertThat(options.tsv()).isTrue();
        }

        @Test
        @DisplayName("an unknown flag is refused, not ignored")
        void unknownFlagRefused() {
            // Ignoring it would mean a typo like --virtualnodes silently using 150 and
            // producing a plan against the wrong ring.
            assertThatThrownBy(() -> ShardMigrationCli.parse(
                    new String[] {"--before", "a", "--after", "a,b", "--virtualnodes", "8"}))
                    .isInstanceOf(ShardMigrationCli.UsageException.class)
                    .hasMessageContaining("unknown argument");
        }

        @Test
        @DisplayName("a flag with no value is refused rather than reading past the end")
        void missingValueRefused() {
            assertThatThrownBy(() ->
                    ShardMigrationCli.parse(new String[] {"--before"}))
                    .isInstanceOf(ShardMigrationCli.UsageException.class)
                    .hasMessageContaining("--before needs a value");
        }

        @Test
        @DisplayName("both ring sides are required")
        void bothSidesRequired() {
            assertThatThrownBy(() ->
                    ShardMigrationCli.parse(new String[] {"--before", "a"}))
                    .isInstanceOf(ShardMigrationCli.UsageException.class)
                    .hasMessageContaining("--before and --after are required");

            assertThatThrownBy(() ->
                    ShardMigrationCli.parse(new String[] {"--after", "a,b"}))
                    .isInstanceOf(ShardMigrationCli.UsageException.class);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("shard id lists")
    class ShardLists {

        @Test
        void splitsAndTrims() {
            assertThat(ShardMigrationCli.shards(" shard-0 , shard-1,shard-2 "))
                    .containsExactly(
                            ShardId.of("shard-0"), ShardId.of("shard-1"), ShardId.of("shard-2"));
        }

        @Test
        @DisplayName("empty entries from a trailing comma are dropped")
        void dropsEmptyEntries() {
            assertThat(ShardMigrationCli.shards("shard-0,,shard-1,")).hasSize(2);
        }

        @Test
        @DisplayName("a list that parses to nothing is refused")
        void refusesEmptyList() {
            // ",," would otherwise build an empty ring, and an empty "after" ring means
            // every tenant moves to nowhere.
            assertThatThrownBy(() -> ShardMigrationCli.shards(",,"))
                    .isInstanceOf(ShardMigrationCli.UsageException.class)
                    .hasMessageContaining("no shard ids parsed");
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("running it end to end")
    class Running {

        private ByteArrayOutputStream out;
        private ByteArrayOutputStream err;

        private int runWith(String tenants, String... args) throws IOException {
            System.setIn(new ByteArrayInputStream(tenants.getBytes(StandardCharsets.UTF_8)));
            out = new ByteArrayOutputStream();
            err = new ByteArrayOutputStream();
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            return ShardMigrationCli.run(args);
        }

        private String stdout() {
            return out.toString(StandardCharsets.UTF_8);
        }

        private String stderr() {
            return err.toString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("summary to stderr, plan to stdout, exit 0")
        void splitsTheStreams() throws IOException {
            int code = runWith(
                    String.join("\n", List.of("acme", "globex", "initech", "umbrella")),
                    "--before", "shard-0,shard-1,shard-2,shard-3",
                    "--after", "shard-0,shard-1,shard-2,shard-3,shard-4",
                    "--tsv");

            assertThat(code).isZero();
            // The split is the contract: `> plan.tsv` must capture ONLY the lines the
            // driver parses, while the operator still sees the percentages.
            assertThat(stderr()).contains("shard migration plan").contains("tenants:");
            assertThat(stdout()).doesNotContain("shard migration plan");
        }

        @Test
        @DisplayName("without --tsv nothing goes to stdout at all")
        void tsvIsOptIn() throws IOException {
            int code = runWith("acme\nglobex\n",
                    "--before", "shard-0,shard-1", "--after", "shard-0,shard-1,shard-2");

            assertThat(code).isZero();
            assertThat(stdout()).isEmpty();
            assertThat(stderr()).contains("tenants:");
        }

        @Test
        @DisplayName("an empty stdin is refused, not reported as a plan of zero tenants")
        void refusesEmptyStdin() {
            // "migrated 0 tenants" and "migrated 400 tenants" must not look the same.
            assertThatThrownBy(() -> runWith("   \n\n",
                    "--before", "a", "--after", "a,b"))
                    .isInstanceOf(ShardMigrationCli.UsageException.class)
                    .hasMessageContaining("no tenants on stdin");
        }

        @Test
        @DisplayName("duplicate tenants on stdin are collapsed, not planned twice")
        void collapsesDuplicates() throws IOException {
            int code = runWith("acme\nacme\nacme\n",
                    "--before", "shard-0,shard-1", "--after", "shard-0,shard-1,shard-2",
                    "--tsv");

            assertThat(code).isZero();
            // A tenant copied twice is a tenant whose rows may be duplicated, and
            // `SELECT DISTINCT` is easy to forget upstream.
            assertThat(stderr()).contains("1 total");
        }

        @Test
        @DisplayName("replacing a shard in one step is legal, and exits 0")
        void addAndRemoveTogetherIsLegal() throws IOException {
            // Written first as a lateral-move test, which was wrong and is worth
            // recording. Removing shard-0 while adding shard-3 produces two KINDS of
            // move, and the planner permits both: a move FROM a removed shard, and a
            // move TO an added one. Only a move between two shards present in BOTH rings
            // is impossible - and that cannot arise here, because run() builds both rings
            // with the same --virtual-nodes. The lateral check guards against a ring
            // built wrongly in code, not against CLI misuse.
            int code = runWith(
                    String.join("\n", List.of("acme", "globex", "initech", "umbrella",
                            "wayne", "stark", "hooli", "soylent")),
                    "--before", "shard-0,shard-1,shard-2",
                    "--after", "shard-1,shard-2,shard-3",
                    "--tsv");

            assertThat(code).isZero();
            assertThat(stderr())
                    .contains("added:")
                    .contains("removed:")
                    .contains("lateral: none");
        }

        @Test
        @DisplayName("a no-op change moves nobody")
        void identicalRingsMoveNothing() throws IOException {
            int code = runWith("acme\nglobex\n",
                    "--before", "shard-0,shard-1", "--after", "shard-0,shard-1", "--tsv");

            assertThat(code).isZero();
            // An operator who pastes the same list twice should see "0 move" rather than
            // a plan, and an empty TSV rather than no TSV.
            assertThat(stderr()).contains("0 move");
            assertThat(stdout()).isEmpty();
        }
    }
}
