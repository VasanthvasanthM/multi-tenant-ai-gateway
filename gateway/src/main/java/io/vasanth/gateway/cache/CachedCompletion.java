package io.vasanth.gateway.cache;

import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;

/**
 * Wire format of a cached completion.
 *
 * <p>Separate from {@link CompletionResponse} on purpose. The response type is free to
 * change shape as the API evolves; this one is a persisted format, and every field change
 * must be considered against entries already sitting in Redis. Serialising the domain
 * object directly would couple an internal refactor to a production cache migration.
 *
 * <p>Fields are stored as primitives and plain strings rather than as the domain value
 * types, so deserialising an old entry cannot fail on a validation rule that was tightened
 * after it was written. A stale entry should be ignorable, never a source of exceptions on
 * the read path.
 *
 * @param schemaVersion format version; an entry with an unrecognised version is treated as
 *     a miss rather than an error, which lets the format change without a flush
 * @param content the generated text
 * @param model model that produced it
 * @param promptTokens input token count from the original call
 * @param completionTokens output token count from the original call
 * @param provider upstream that produced it
 * @param createdAtEpochMillis when it was originally generated, not when it was cached
 */
public record CachedCompletion(
        @JsonProperty("v") int schemaVersion,
        @JsonProperty("c") String content,
        @JsonProperty("m") String model,
        @JsonProperty("pt") long promptTokens,
        @JsonProperty("ct") long completionTokens,
        @JsonProperty("p") String provider,
        @JsonProperty("ts") long createdAtEpochMillis) {

    /** Current format version. Bump when the shape changes incompatibly. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonCreator
    public CachedCompletion {
        // No validation beyond what Jackson needs. See the class javadoc: a malformed or
        // outdated entry must degrade to a cache miss, not throw on the hot path.
    }

    public static CachedCompletion from(CompletionResponse response) {
        return new CachedCompletion(
                CURRENT_SCHEMA_VERSION,
                response.content(),
                response.model().value(),
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.provider().value(),
                response.createdAt().toEpochMilli());
    }

    /**
     * Not serialised. Jackson treats an {@code isX()} method on a record as a derived
     * property and would write {@code "readable":true} into every cache entry - pure
     * waste on every write, every read and every byte of Redis memory, multiplied by the
     * whole key space.
     */
    @JsonIgnore
    public boolean isReadable() {
        return schemaVersion == CURRENT_SCHEMA_VERSION && content != null && model != null;
    }

    /**
     * Rebuilds a response for serving.
     *
     * @param tier which tier produced this hit
     * @param servedIn wall-clock time this lookup took, which is the latency the caller
     *     actually experienced - not the latency of the original upstream call
     */
    public CompletionResponse toResponse(CacheTier tier, Duration servedIn) {
        return new CompletionResponse(
                content,
                ModelId.of(model),
                TokenUsage.cacheHit(promptTokens),
                tier,
                ProviderId.CACHE,
                Instant.ofEpochMilli(createdAtEpochMillis),
                servedIn);
    }
}
