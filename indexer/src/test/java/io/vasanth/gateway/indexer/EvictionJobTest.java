package io.vasanth.gateway.indexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

/**
 * The eviction sweep's batching and its stopping conditions.
 *
 * <h2>Why a unit test</h2>
 *
 * <p>{@code VectorIndexerIT} runs against real pgvector with eviction switched OFF, so the
 * sweep's control flow has never been exercised. Three properties decide whether this job
 * is safe to schedule, and none of them is about SQL:
 *
 * <ul>
 *   <li>it <b>stops as soon as a batch removes nothing</b> - otherwise every run issues all
 *       twenty DELETEs against an empty backlog, five minutes apart, forever;
 *   <li>it is <b>capped</b> at twenty batches, so a huge backlog cannot monopolise the
 *       database in one run;
 *   <li>a failing batch <b>does not propagate</b> - this runs on a scheduler thread where
 *       an escaping error kills the job silently and it never runs again.
 * </ul>
 */
class EvictionJobTest {

    private AtomicInteger batchCalls;
    private SimpleMeterRegistry meters;
    private DatabaseClient db;

    /** Rows removed by the Nth batch, zero-indexed. */
    private IntFunction<Long> rowsPerBatch;

    /** Batch index that should fail instead of returning rows, or -1 for none. */
    private int failingBatch;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        batchCalls = new AtomicInteger();
        meters = new SimpleMeterRegistry();
        rowsPerBatch = index -> 0L;
        failingBatch = -1;

        db = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);

        when(db.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.fromCallable(() -> {
            int index = batchCalls.getAndIncrement();
            if (index == failingBatch) {
                throw new IllegalStateException("deadlock detected");
            }
            return rowsPerBatch.apply(index);
        }));
    }

    private EvictionJob job(boolean enabled) {
        return new EvictionJob(db, meters, enabled);
    }

    private double evicted() {
        return Optional.ofNullable(meters.find("indexer.entries.evicted").counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    @Test
    @DisplayName("an empty backlog costs exactly ONE query, not twenty")
    void emptyBacklogIssuesOneBatch() {
        job(true).sweep();

        // takeWhile stops on the first batch that removes nothing. Without it, every
        // five-minute run would fire all twenty DELETEs against an empty table forever -
        // constant load that produces nothing and looks like normal activity.
        assertThat(batchCalls.get()).isEqualTo(1);
        assertThat(evicted()).isZero();
    }

    @Test
    @DisplayName("it keeps going while batches remove rows, then stops")
    void drainsThenStops() {
        // Two full batches, a partial third, then nothing.
        rowsPerBatch = index -> switch (index) {
            case 0, 1 -> 500L;
            case 2 -> 120L;
            default -> 0L;
        };

        job(true).sweep();

        // Four calls: three that removed rows, plus the one that found the backlog clear.
        assertThat(batchCalls.get()).isEqualTo(4);
        assertThat(evicted()).isEqualTo(1_120.0d);
    }

    @Test
    @DisplayName("one run is capped at twenty batches even with an endless backlog")
    void cappedPerRun() {
        // Always a full batch: the backlog never clears.
        rowsPerBatch = index -> 500L;

        job(true).sweep();

        // The cap is what keeps a huge backlog from monopolising the database. The
        // remainder is simply picked up by the next run five minutes later, which is why
        // the schedule is fixedDelay rather than fixedRate.
        assertThat(batchCalls.get()).isEqualTo(20);
        assertThat(evicted()).isEqualTo(10_000.0d);
    }

    @Test
    @DisplayName("a failing batch does not escape the scheduled method")
    void failureDoesNotPropagate() {
        rowsPerBatch = index -> 500L;
        failingBatch = 1;

        // An exception escaping a @Scheduled method cancels the schedule in Spring - the
        // job would never run again, with nothing but one stack trace to show why. The
        // failure resolves to zero rows, which then stops the sweep via takeWhile.
        job(true).sweep();

        assertThat(batchCalls.get()).isEqualTo(2);
        // The first batch's rows are still counted: eviction is a capacity concern, not a
        // correctness one, and stale rows are filtered out at read time regardless.
        assertThat(evicted()).isEqualTo(500.0d);
    }

    @Test
    @DisplayName("a failure on the very first batch leaves the counter untouched")
    void firstBatchFailureCountsNothing() {
        rowsPerBatch = index -> 500L;
        failingBatch = 0;

        job(true).sweep();

        assertThat(batchCalls.get()).isEqualTo(1);
        assertThat(evicted()).isZero();
    }

    @Test
    @DisplayName("disabled issues no query at all")
    void disabledDoesNothing() {
        job(false).sweep();

        // How VectorIndexerIT runs: eviction off, so the test's own rows are not deleted
        // underneath it.
        assertThat(batchCalls.get()).isZero();
        assertThat(evicted()).isZero();
    }
}
