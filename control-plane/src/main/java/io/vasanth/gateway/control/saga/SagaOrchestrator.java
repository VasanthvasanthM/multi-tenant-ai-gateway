package io.vasanth.gateway.control.saga;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Runs a sequence of reversible steps, compensating backwards on failure.
 *
 * <h2>Why a saga is genuinely needed here</h2>
 *
 * <p>Provisioning a tenant touches Postgres, Redis and Kafka. Those three share no
 * transaction, so a failure at step three leaves a tenant row with no quota buckets and
 * no key - a half-created tenant that looks real to an operator and does not work.
 *
 * <h2>Why orchestration rather than choreography</h2>
 *
 * <p>Choreography - each service reacting to the previous one's event - avoids a central
 * coordinator but scatters one business process across several codebases. Nothing can
 * then answer "where is tenant X stuck?" without reading logs from every service.
 *
 * <p>An orchestrator with persisted state answers that with a {@code SELECT}. The cost is
 * coupling: this class knows every step. For a process with five steps and one owner that
 * is the better trade, and it is reversible - the step list is data, not control flow.
 *
 * <h2>completed_step is persisted before the next step runs</h2>
 *
 * <p>That ordering is the point. If progress were recorded after a step, a crash between
 * doing the work and recording it would resume by repeating it; if recorded before, a
 * crash would skip it. Recording immediately after each step completes, in its own write,
 * means recovery compensates exactly the steps that actually ran.
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private static final String INSERT_SAGA = """
            INSERT INTO saga_instances (saga_id, saga_type, aggregate_id, state, payload)
            VALUES (:sagaId, :sagaType, :aggregateId, 'STARTED', CAST(:payload AS jsonb))
            """;

    private static final String UPDATE_PROGRESS = """
            UPDATE saga_instances
               SET completed_step = :step, updated_at = now()
             WHERE saga_id = :sagaId
            """;

    private static final String UPDATE_STATE = """
            UPDATE saga_instances
               SET state = :state, last_error = :error, updated_at = now()
             WHERE saga_id = :sagaId
            """;

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;
    private final Counter completed;
    private final Counter compensated;
    private final Counter stuck;

    public SagaOrchestrator(
            DatabaseClient db, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.db = db;
        this.objectMapper = objectMapper;
        this.completed = Counter.builder("saga.completed").register(meterRegistry);
        this.compensated = Counter.builder("saga.compensated").register(meterRegistry);
        // Alert on this. A saga that failed to compensate has left resources behind and
        // needs a human; it will not resolve itself.
        this.stuck = Counter.builder("saga.compensation_failed").register(meterRegistry);
    }

    /**
     * Runs a saga to completion, or compensates it.
     *
     * @return the saga id, whether it succeeded or was compensated. The caller inspects
     *     the saga row for the outcome rather than relying on an exception, because a
     *     compensated saga is a normal result, not an error
     */
    public Mono<UUID> run(String sagaType, String aggregateId, List<SagaStep> steps) {
        UUID sagaId = UUID.randomUUID();
        SagaContext context = new SagaContext(sagaId, aggregateId);

        return persistStart(sagaId, sagaType, aggregateId)
                .then(executeSteps(steps, context))
                .then(markState(sagaId, "COMPLETED", null))
                .doOnSuccess(ignored -> {
                    completed.increment();
                    log.info("saga {} ({}) completed for {}", sagaId, sagaType, aggregateId);
                })
                .onErrorResume(error -> {
                    log.warn("saga {} ({}) failed at step {}: {}",
                            sagaId, sagaType, context.get("__step", Integer.class),
                            error.toString());
                    return compensate(sagaId, steps, context, error);
                })
                .thenReturn(sagaId);
    }

    private Mono<Void> executeSteps(List<SagaStep> steps, SagaContext context) {
        return Flux.range(0, steps.size())
                // concatMap: steps are ordered and each may depend on what the previous
                // one put into the context.
                .concatMap(index -> {
                    SagaStep step = steps.get(index);
                    context.put("__step", index);
                    return step.execute(context)
                            .doOnSubscribe(subscription -> log.debug(
                                    "saga {} executing step {} ({})",
                                    context.sagaId(), index, step.name()))
                            // Progress is recorded immediately after the step succeeds and
                            // before the next begins, so recovery knows exactly which
                            // steps ran.
                            .then(persistProgress(context.sagaId(), index + 1));
                })
                .then();
    }

    /**
     * Undoes completed steps in reverse order.
     *
     * <p>Reverse order matters: later steps may depend on earlier ones, so undoing
     * forwards could try to remove something a later step still references.
     *
     * <p>Only steps that actually completed are compensated - taken from the context's
     * step marker rather than assuming all of them ran.
     */
    private Mono<Void> compensate(
            UUID sagaId, List<SagaStep> steps, SagaContext context, Throwable cause) {

        Integer failedAt = context.get("__step", Integer.class);
        int lastCompleted = failedAt == null ? -1 : failedAt - 1;

        if (lastCompleted < 0) {
            // Nothing succeeded, so there is nothing to undo.
            return markState(sagaId, "FAILED", cause.toString());
        }

        return markState(sagaId, "COMPENSATING", cause.toString())
                .thenMany(Flux.range(0, lastCompleted + 1)
                        .map(index -> lastCompleted - index)
                        .concatMap(index -> {
                            SagaStep step = steps.get(index);
                            return step.compensate(context)
                                    .doOnSubscribe(s -> log.info(
                                            "saga {} compensating step {} ({})",
                                            sagaId, index, step.name()))
                                    .onErrorResume(error -> {
                                        // Compensation failing leaves a resource behind.
                                        // Logged loudly and counted, but the walk
                                        // continues: stopping here would leave the saga
                                        // stuck in COMPENSATING forever, which blocks
                                        // every retry of the same operation.
                                        stuck.increment();
                                        log.error("saga {} FAILED to compensate step {} ({}) - "
                                                        + "manual cleanup required",
                                                sagaId, index, step.name(), error);
                                        return Mono.empty();
                                    });
                        }))
                .then(markState(sagaId, "COMPENSATED", cause.toString()))
                .doOnSuccess(ignored -> compensated.increment());
    }

    private Mono<Void> persistStart(UUID sagaId, String sagaType, String aggregateId) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(
                        java.util.Map.of("aggregateId", aggregateId)))
                .flatMap(payload -> db.sql(INSERT_SAGA)
                        .bind("sagaId", sagaId)
                        .bind("sagaType", sagaType)
                        .bind("aggregateId", aggregateId)
                        .bind("payload", payload)
                        .fetch()
                        .rowsUpdated())
                .then();
    }

    private Mono<Void> persistProgress(UUID sagaId, int step) {
        return db.sql(UPDATE_PROGRESS)
                .bind("sagaId", sagaId)
                .bind("step", step)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> markState(UUID sagaId, String state, String error) {
        DatabaseClient.GenericExecuteSpec spec = db.sql(UPDATE_STATE)
                .bind("sagaId", sagaId)
                .bind("state", state);

        spec = error == null
                // R2DBC refuses a plain null bind, so a typed null is required to write
                // SQL NULL rather than the string "null".
                ? spec.bindNull("error", String.class)
                : spec.bind("error", error.length() > 1000 ? error.substring(0, 1000) : error);

        return spec.fetch().rowsUpdated().then();
    }
}
