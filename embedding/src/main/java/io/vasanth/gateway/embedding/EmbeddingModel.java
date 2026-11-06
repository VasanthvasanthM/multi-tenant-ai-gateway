package io.vasanth.gateway.embedding;

import java.util.List;

/**
 * Turns text into a vector. Synchronous and CPU-bound by nature.
 *
 * <h2>This interface is deliberately blocking</h2>
 *
 * <p>Inference is pure computation - there is no I/O to await, so wrapping it in
 * {@code Mono} here would be dishonest: it would look non-blocking while still occupying
 * whatever thread subscribed to it. Hiding CPU work behind a reactive type is how CPU work
 * ends up on a Netty event loop.
 *
 * <p>The reactive boundary lives one layer up in {@link EmbeddingService}, which moves
 * these calls onto a bounded scheduler. Callers in the gateway must use that, never an
 * implementation of this interface directly.
 *
 * <h2>Implementations are not required to be thread-safe</h2>
 *
 * <p>DJL's {@code Predictor} is not, and pretending otherwise would mean either a lock on
 * the hottest CPU path or silent corruption. {@link EmbeddingService} owns the concurrency
 * strategy; implementations say what they need via {@link #maxConcurrency()}.
 */
public interface EmbeddingModel extends AutoCloseable {

    /**
     * Embeds a single text.
     *
     * @return an L2-normalised vector of {@link #dimension()} components
     */
    Embedding embed(String text);

    /**
     * Embeds several texts at once.
     *
     * <p>Worth having as its own method rather than a loop: ONNX Runtime amortises graph
     * setup across a batch, so batching is substantially faster per item. The indexer's
     * backfill path depends on this; the request path never batches, because a request has
     * exactly one prompt and waiting to fill a batch would add latency to serve throughput
     * nobody asked for.
     */
    List<Embedding> embedBatch(List<String> texts);

    /** Output width. Must match the {@code vector(n)} column in Postgres. */
    int dimension();

    /**
     * Identifier of the model and its version.
     *
     * <p>Stored on every indexed row. Changing the model changes the meaning of the whole
     * vector space, so rows produced by an older model must be ignored or re-indexed
     * rather than compared against new ones - comparing across models yields similarity
     * scores that look plausible and are meaningless.
     */
    String modelId();

    /**
     * How many threads may call {@link #embed} concurrently.
     *
     * <p>Usually the core count. Exceeding it does not increase throughput because the work
     * is already CPU-saturated; it just adds context switching and inflates p99.
     */
    int maxConcurrency();

    @Override
    void close();
}
