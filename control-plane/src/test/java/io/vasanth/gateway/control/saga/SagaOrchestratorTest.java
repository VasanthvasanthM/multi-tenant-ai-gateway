package io.vasanth.gateway.control.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

/**
 * Saga orchestration: step order, compensation order, and what happens when compensation
 * itself fails.
 *
 * <h2>Why this is worth a unit test rather than only an IT</h2>
 *
 * <p>{@code OutboxRelayIT} covers the control plane against real Postgres, but it does not
 * exercise the part of the orchestrator that is hardest to get right and most expensive to
 * get wrong: the <b>order</b> in which completed steps are undone, and the decision about
 * <b>which</b> steps count as completed. Both are pure control flow over a list, and both
 * are invisible in a passing integration test that never fails a step.
 *
 * <p>The persistence is three almost identical statements, so {@code DatabaseClient} is
 * stubbed with {@code RETURNS_SELF} to collapse the fluent {@code bind} chain. Every SQL
 * string is captured, which is enough to assert the state machine without a database.
 */
class SagaOrchestratorTest {

    /** Records execution and compensation, and can be told to fail either. */
    private static final class RecordingStep implements SagaStep {

        private final String name;
        private final List<String> journal;
        private boolean failExecute;
        private boolean failCompensate;

        RecordingStep(String name, List<String> journal) {
            this.name = name;
            this.journal = journal;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Mono<Void> execute(SagaContext context) {
            if (failExecute) {
                journal.add("execute-failed:" + name);
                return Mono.error(new IllegalStateException("step " + name + " blew up"));
            }
            journal.add("execute:" + name);
            // Leaves something behind, so a test can prove compensation sees what earlier
            // steps produced rather than an empty context.
            context.put("produced-by-" + name, name + "-value");
            return Mono.empty();
        }

        @Override
        public Mono<Void> compensate(SagaContext context) {
            if (failCompensate) {
                journal.add("compensate-failed:" + name);
                return Mono.error(new IllegalStateException("undo " + name + " blew up"));
            }
            journal.add("compensate:" + name);
            return Mono.empty();
        }
    }

    private List<String> journal;

    /** SQL in the order the statements were ASSEMBLED. */
    private List<String> assembled;

    /** SQL in the order the statements were actually SUBSCRIBED - i.e. executed. */
    private List<String> executed;

    private SimpleMeterRegistry meters;
    private SagaOrchestrator orchestrator;

    /**
     * Stubs the client so each {@code sql(...)} call yields its OWN spec.
     *
     * <p>Sharing one spec across calls loses the association between a statement and its
     * subscription, and that association is the whole point here: assembly order and
     * execution order genuinely differ. {@code then(markState(...))} evaluates its argument
     * when the chain is BUILT, so the terminal UPDATE is assembled before the INSERT is
     * subscribed - the same hazard {@code CompletionPipeline} guards against with
     * {@code Mono.defer}. It is not a behavioural bug, because the returned Mono is cold
     * and nothing runs until subscription, which is exactly why the distinction has to be
     * measured rather than assumed.
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        journal = new CopyOnWriteArrayList<>();
        assembled = new CopyOnWriteArrayList<>();
        executed = new CopyOnWriteArrayList<>();
        meters = new SimpleMeterRegistry();

        DatabaseClient db = mock(DatabaseClient.class);

        when(db.sql(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            assembled.add(sql);

            DatabaseClient.GenericExecuteSpec spec =
                    mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
            FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
            when(spec.fetch()).thenReturn(fetch);
            when(fetch.rowsUpdated()).thenReturn(Mono.fromCallable(() -> {
                executed.add(sql);
                return 1L;
            }));
            return spec;
        });

        orchestrator = new SagaOrchestrator(db, new ObjectMapper(), meters);
    }

    private RecordingStep step(String name) {
        return new RecordingStep(name, journal);
    }

    private double counter(String name) {
        return Optional.ofNullable(meters.find(name).counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    /** Labels the captured SQL by kind, preserving order. */
    private List<String> kindsOf(List<String> statements) {
        List<String> states = new ArrayList<>();
        for (String sql : statements) {
            if (sql.contains("SET state")) {
                states.add("state");
            } else if (sql.contains("SET completed_step")) {
                states.add("progress");
            } else if (sql.contains("INSERT INTO saga_instances")) {
                states.add("insert");
            }
        }
        return states;
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("when every step succeeds")
    class Success {

        @Test
        @DisplayName("steps run in order and nothing is compensated")
        void runsInOrder() {
            UUID sagaId = orchestrator
                    .run("provision", "acme", List.of(step("a"), step("b"), step("c")))
                    .block();

            assertThat(sagaId).isNotNull();
            // concatMap, not flatMap: each step may depend on what the previous one put
            // into the context, so interleaving them would be a correctness bug.
            assertThat(journal).containsExactly("execute:a", "execute:b", "execute:c");
            assertThat(counter("saga.completed")).isEqualTo(1.0d);
            assertThat(counter("saga.compensated")).isZero();
        }

        @Test
        @DisplayName("progress is recorded after each step, before the next begins")
        void progressRecordedPerStep() {
            orchestrator.run("provision", "acme", List.of(step("a"), step("b"))).block();

            // insert, then progress per step, then the terminal state. Recovery depends on
            // this ordering to know exactly which steps ran. Asserted on EXECUTION order:
            // see the note on setUp for why assembly order differs.
            assertThat(kindsOf(executed))
                    .containsExactly("insert", "progress", "progress", "state");
        }

        @Test
        @DisplayName("the terminal UPDATE is assembled early but executed last")
        void assemblyOrderDiffersFromExecutionOrder() {
            orchestrator.run("provision", "acme", List.of(step("a"))).block();

            // Pinned deliberately rather than left as a surprise. `then(markState(...))`
            // calls markState when the chain is BUILT, so db.sql(UPDATE_STATE) and its
            // binds happen before the INSERT is subscribed. Harmless here because the
            // returned Mono is cold - but it is the same shape as a bug the gateway's
            // pipeline documents guarding against with Mono.defer, and anyone who adds a
            // side effect to the assembly path will fail this test and find out why.
            assertThat(kindsOf(assembled).get(0)).isEqualTo("state");
            assertThat(kindsOf(executed)).last().isEqualTo("state");
        }

        @Test
        @DisplayName("a saga with no steps completes rather than failing")
        void emptySagaCompletes() {
            assertThat(orchestrator.run("noop", "acme", List.of()).block()).isNotNull();

            assertThat(journal).isEmpty();
            assertThat(counter("saga.completed")).isEqualTo(1.0d);
        }
    }

    // ------------------------------------------------------------------
    // Compensation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("when a step fails")
    class Compensation {

        @Test
        @DisplayName("completed steps are undone in REVERSE order")
        void compensatesInReverse() {
            RecordingStep a = step("a");
            RecordingStep b = step("b");
            RecordingStep c = step("c");
            c.failExecute = true;

            orchestrator.run("provision", "acme", List.of(a, b, c)).block();

            // Reverse matters: later steps may depend on earlier ones, so undoing forwards
            // could try to remove something a later step still references.
            assertThat(journal).containsExactly(
                    "execute:a", "execute:b", "execute-failed:c",
                    "compensate:b", "compensate:a");
            assertThat(counter("saga.compensated")).isEqualTo(1.0d);
            assertThat(counter("saga.completed")).isZero();
        }

        @Test
        @DisplayName("the step that failed is NOT compensated")
        void failedStepIsNotCompensated() {
            RecordingStep a = step("a");
            RecordingStep b = step("b");
            b.failExecute = true;

            orchestrator.run("provision", "acme", List.of(a, b)).block();

            // Only steps that actually completed are undone. Compensating a step that
            // never ran is at best a no-op and at worst removes something it did not
            // create.
            assertThat(journal).doesNotContain("compensate:b");
            assertThat(journal).contains("compensate:a");
        }

        @Test
        @DisplayName("a failure in the FIRST step compensates nothing and is marked FAILED")
        void firstStepFailureCompensatesNothing() {
            RecordingStep a = step("a");
            a.failExecute = true;

            orchestrator.run("provision", "acme", List.of(a, step("b"))).block();

            assertThat(journal).containsExactly("execute-failed:a");
            // Nothing succeeded, so there is nothing to undo - and the saga goes straight
            // to FAILED rather than through COMPENSATING, which would be a lie about what
            // happened.
            assertThat(counter("saga.compensated")).isZero();
            assertThat(counter("saga.completed")).isZero();
        }

        @Test
        @DisplayName("compensation sees what the earlier steps produced")
        void compensationSeesTheContext() {
            List<String> seen = new CopyOnWriteArrayList<>();
            SagaStep recorder = new SagaStep() {
                @Override
                public String name() {
                    return "recorder";
                }

                @Override
                public Mono<Void> execute(SagaContext context) {
                    context.put("produced-by-recorder", "token-42");
                    return Mono.empty();
                }

                @Override
                public Mono<Void> compensate(SagaContext context) {
                    seen.add(context.get("produced-by-recorder", String.class));
                    return Mono.empty();
                }
            };
            RecordingStep failing = step("failing");
            failing.failExecute = true;

            orchestrator.run("provision", "acme", List.of(recorder, failing)).block();

            // The reason SagaContext is mutable at all: compensation must know what to
            // undo, and that value does not exist until the step that produced it ran.
            assertThat(seen).containsExactly("token-42");
        }

        @Test
        @DisplayName("the saga id is returned even when it compensated")
        void returnsIdOnCompensation() {
            RecordingStep a = step("a");
            a.failExecute = true;

            // A compensated saga is a normal outcome, not an error - the caller inspects
            // the saga row rather than catching an exception, so this must not signal one.
            assertThat(orchestrator.run("provision", "acme", List.of(a)).block()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // Compensation that itself fails
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("when compensation fails")
    class CompensationFails {

        @Test
        @DisplayName("the walk continues past a failed compensation")
        void continuesPastAFailure() {
            RecordingStep a = step("a");
            RecordingStep b = step("b");
            RecordingStep c = step("c");
            b.failCompensate = true;
            c.failExecute = true;

            orchestrator.run("provision", "acme", List.of(a, b, c)).block();

            // Stopping at b would leave the saga stuck in COMPENSATING forever, which
            // blocks every retry of the same operation - worse than leaving one resource
            // behind and reporting it.
            assertThat(journal).containsExactly(
                    "execute:a", "execute:b", "execute-failed:c",
                    "compensate-failed:b", "compensate:a");
        }

        @Test
        @DisplayName("a failed compensation increments the alert counter")
        void incrementsTheAlertCounter() {
            RecordingStep a = step("a");
            RecordingStep b = step("b");
            a.failCompensate = true;
            b.failExecute = true;

            orchestrator.run("provision", "acme", List.of(a, b)).block();

            // This is the one that pages a human: a saga that failed to compensate has
            // left resources behind and will not resolve itself.
            assertThat(counter("saga.compensation_failed")).isEqualTo(1.0d);
            // Still reaches COMPENSATED, so the row is not stuck.
            assertThat(counter("saga.compensated")).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("every failed compensation is counted, not just the first")
        void countsEveryFailure() {
            RecordingStep a = step("a");
            RecordingStep b = step("b");
            RecordingStep c = step("c");
            a.failCompensate = true;
            b.failCompensate = true;
            c.failExecute = true;

            orchestrator.run("provision", "acme", List.of(a, b, c)).block();

            assertThat(counter("saga.compensation_failed")).isEqualTo(2.0d);
        }
    }
}
