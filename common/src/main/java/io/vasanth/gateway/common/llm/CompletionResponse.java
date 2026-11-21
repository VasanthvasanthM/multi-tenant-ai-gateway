package io.vasanth.gateway.common.llm;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.model.ModelId;

/**
 * A completed response, whatever produced it.
 *
 * <p>{@code servedBy} and {@code provider} are part of the response rather than side-channel
 * logging on purpose: the caller is told which tier answered, which makes cache behaviour
 * auditable from the client side instead of being something only the operator can see.
 *
 * @param content the generated text
 * @param model model that produced it, or that produced the cached entry
 * @param usage token counts
 * @param servedBy which tier satisfied the request
 * @param provider upstream that produced it, or {@link ProviderId#CACHE}
 * @param createdAt when the content was originally generated, not when it was served; for a
 *     cache hit this is the timestamp of the original upstream call, which is what makes
 *     staleness visible to the caller
 * @param latency wall-clock time this particular request took
 */
public record CompletionResponse(
        String content,
        ModelId model,
        TokenUsage usage,
        CacheTier servedBy,
        ProviderId provider,
        Instant createdAt,
        Duration latency) {

    /**
     * The same response, re-stamped with the request's true end-to-end duration.
     *
     * <p>Each tier measures only its own work - Tier 1 times its Redis round trip, Tier 2
     * times its vector search and not the embedding that preceded it, and a provider may
     * report a figure it was configured with rather than one it measured. Those are useful
     * as separate metrics and useless as a single field, because the number silently
     * changes meaning depending on who answered.
     *
     * <p>So the pipeline overwrites it once, at the edge, with one consistently scoped
     * measurement. Per-component timings remain available as metrics
     * ({@code gateway.cache.lookup.duration}, {@code gateway.embedding.duration}).
     */
    public CompletionResponse withLatency(Duration endToEnd) {
        return new CompletionResponse(
                content, model, usage, servedBy, provider, createdAt, endToEnd);
    }

    public CompletionResponse {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(servedBy, "servedBy must not be null");
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(latency, "latency must not be null");

        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative: " + latency);
        }
    }

    /**
     * Rebuilds this response as one served from cache, preserving the original generation
     * time so the caller can see how old the content is.
     */
    public CompletionResponse asCacheHit(CacheTier tier, Duration servedIn) {
        return new CompletionResponse(
                content,
                model,
                TokenUsage.cacheHit(usage.promptTokens()),
                tier,
                ProviderId.CACHE,
                createdAt,
                servedIn);
    }

    public boolean wasCacheHit() {
        return servedBy.isHit();
    }
}
