package io.vasanth.gateway.control.saga;

import reactor.core.publisher.Mono;

/**
 * One reversible step in a saga.
 *
 * <h2>Compensation must be idempotent, and that is not optional</h2>
 *
 * <p>The orchestrator can crash partway through compensating and resume, so a
 * compensation may run more than once for the same step. "Delete the Redis key" is
 * idempotent; "decrement the counter" is not. Every implementation here is written as the
 * former.
 *
 * <h2>Compensation must also tolerate a step that never fully ran</h2>
 *
 * <p>A step can fail <em>after</em> doing part of its work - the process dies between
 * writing a row and returning. Compensation therefore has to undo whatever is actually
 * there rather than assume the step completed, which is why they are written as "ensure
 * absent" rather than "reverse the change".
 */
public interface SagaStep {

    /** Short stable name, recorded in logs and in the saga row. */
    String name();

    /** Performs the step. */
    Mono<Void> execute(SagaContext context);

    /**
     * Undoes the step.
     *
     * <p>Must succeed or the saga cannot finish compensating. An implementation that
     * cannot guarantee success should log loudly and complete anyway - leaving a saga
     * stuck in COMPENSATING forever is worse than leaving one resource behind and
     * reporting it, because the stuck saga blocks every retry of the same operation.
     */
    Mono<Void> compensate(SagaContext context);
}
