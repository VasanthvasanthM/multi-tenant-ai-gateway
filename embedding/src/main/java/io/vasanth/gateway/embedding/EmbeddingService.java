package io.vasanth.gateway.embedding;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The reactive boundary around embedding inference.
 *
 * <h2>This class exists for exactly one reason</h2>
 *
 * <p>Embedding is CPU-bound work on the request path of a WebFlux application. Running it
 * on a Netty event loop thread would stall every other request sharing that loop - with
 * four cores and four event loops, a handful of concurrent embeddings would freeze the
 * entire gateway, including the cache hits that need no embedding at all. It is the single
 * most damaging mistake available in a reactive application, and it is invisible until the
 * service is under load.
 *
 * <p>Every call is therefore moved onto a dedicated bounded scheduler with
 * {@code subscribeOn}. Nothing in the gateway may call {@link EmbeddingModel} directly.
 *
 * <h2>Why the scheduler is bounded in both dimensions</h2>
 *
 * <p>Threads are capped at the model's concurrency - normally the core count - because the
 * work is already CPU-saturated and extra threads only add context switching.
 *
 * <p>The <em>queue</em> is capped too, which is the part usually forgotten. An unbounded
 * queue converts an overload into unbounded latency and eventually an OOM: requests keep
 * being accepted, each waits longer than the last, and clients time out having already
 * paid for work still queued behind them. A bounded queue rejects instead, and that
 * rejection is a legitimate backpressure signal that becomes a 503 the caller can act on.
 */
public final class EmbeddingService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    /**
     * Idle worker lifetime. Short, because this scheduler is sized to the core count and
     * should not hold threads through quiet periods.
     */
    private static final int WORKER_TTL_SECONDS = 60;

    private final EmbeddingModel model;
    private final Scheduler scheduler;
    private final boolean ownsScheduler;

    private EmbeddingService(EmbeddingModel model, Scheduler scheduler, boolean ownsScheduler) {
        this.model = model;
        this.scheduler = scheduler;
        this.ownsScheduler = ownsScheduler;
    }

    /**
     * Creates a service with its own bounded scheduler.
     *
     * @param model the inference implementation
     * @param queueCapacity how many calls may wait for a worker before rejection begins.
     *     Sized from the latency target, not from optimism: at roughly 6 ms per inference
     *     and 4 workers, a queue of 256 means the last entry waits about 380 ms, already
     *     far beyond the 15 ms Tier-2 budget. Anything deeper is latency nobody will
     *     collect on
     */
    public static EmbeddingService create(EmbeddingModel model, int queueCapacity) {
        Objects.requireNonNull(model, "model must not be null");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }

        int threads = Math.min(model.maxConcurrency(), Runtime.getRuntime().availableProcessors());
        threads = Math.max(1, threads);

        Scheduler scheduler = Schedulers.newBoundedElastic(
                threads, queueCapacity, "embedding", WORKER_TTL_SECONDS, true);

        log.info(
                "embedding service ready: model={} dimension={} threads={} queueCapacity={}",
                model.modelId(), model.dimension(), threads, queueCapacity);

        return new EmbeddingService(model, scheduler, true);
    }

    /** Creates a service on a caller-supplied scheduler. Used by tests to run inline. */
    public static EmbeddingService using(EmbeddingModel model, Scheduler scheduler) {
        return new EmbeddingService(
                Objects.requireNonNull(model), Objects.requireNonNull(scheduler), false);
    }

    /**
     * Embeds one text off the event loop.
     *
     * <p>{@code subscribeOn} rather than {@code publishOn}: the work must be moved before
     * it starts, not after. With {@code publishOn} the {@code fromCallable} body would
     * still execute on whichever thread subscribed - the event loop - which is precisely
     * the failure this class exists to prevent.
     */
    public Mono<Embedding> embed(String text) {
        Objects.requireNonNull(text, "text must not be null");

        return Mono.fromCallable(() -> model.embed(text))
                .subscribeOn(scheduler)
                .onErrorMap(EmbeddingService::translate);
    }

    /** Embeds a batch off the event loop. Used by the indexer's backfill, never by a request. */
    public Mono<List<Embedding>> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.isEmpty()) {
            return Mono.just(List.of());
        }

        return Mono.fromCallable(() -> model.embedBatch(texts))
                .subscribeOn(scheduler)
                .onErrorMap(EmbeddingService::translate);
    }

    public int dimension() {
        return model.dimension();
    }

    public String modelId() {
        return model.modelId();
    }

    /**
     * Whether the active model produces semantically meaningful vectors.
     *
     * <p>The hashing fallback captures surface form only. Tier 2 must refuse to serve from
     * it, because a lexical near-match is not an answer to the same question.
     */
    public boolean isSemanticallyMeaningful() {
        return !(model instanceof HashingEmbeddingModel);
    }

    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.dispose();
        }
        model.close();
    }

    private static Throwable translate(Throwable error) {
        if (error instanceof RejectedExecutionException) {
            // The bounded queue is full. This is backpressure working, not a defect, so it
            // becomes a retryable 503 rather than a 500.
            return new GatewayException(new GatewayError.Overloaded(0));
        }
        if (error instanceof GatewayException) {
            return error;
        }
        log.warn("embedding failed: {}", error.toString());
        return new GatewayException(
                new GatewayError.Internal("embedding unavailable"));
    }
}
