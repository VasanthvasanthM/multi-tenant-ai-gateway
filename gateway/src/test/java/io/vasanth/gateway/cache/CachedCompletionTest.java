package io.vasanth.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;

/**
 * The persisted cache format.
 *
 * <p>This is a wire format living in Redis, so the tests here are about what happens to
 * entries written by a previous version of the code - the case that only shows up in
 * production, during a deploy.
 */
class CachedCompletionTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static CompletionResponse response() {
        return new CompletionResponse(
                "the answer",
                ModelId.of("llama-3.1-8b"),
                new TokenUsage(10L, 20L),
                CacheTier.UPSTREAM,
                ProviderId.of("mock"),
                Instant.parse("2026-11-02T10:00:00Z"),
                Duration.ofMillis(40));
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        CachedCompletion original = CachedCompletion.from(response());

        String json = mapper.writeValueAsString(original);
        CachedCompletion parsed = mapper.readValue(json, CachedCompletion.class);

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.isReadable()).isTrue();
    }

    @Test
    @DisplayName("field names stay short - they are paid for on every read and write")
    void usesCompactFieldNames() throws Exception {
        String json = mapper.writeValueAsString(CachedCompletion.from(response()));

        // Multiplied across the whole key space, verbose names are real Redis memory.
        assertThat(json)
                .contains("\"v\":", "\"c\":", "\"m\":", "\"pt\":", "\"ct\":", "\"p\":", "\"ts\":")
                .doesNotContain("schemaVersion", "completionTokens", "createdAtEpochMillis");
    }

    @Test
    @DisplayName("the derived isReadable() getter is never serialised")
    void doesNotSerialiseDerivedProperties() throws Exception {
        // Regression: Jackson treats isX() on a record as a property and was writing
        // "readable":true into every entry - wasted bytes on every read, write and byte
        // of Redis memory.
        String json = mapper.writeValueAsString(CachedCompletion.from(response()));

        assertThat(json).doesNotContain("readable");
    }

    @Test
    @DisplayName("an entry from a newer schema is unreadable, not an exception")
    void futureSchemaVersionIsNotReadable() {
        CachedCompletion future = new CachedCompletion(
                CachedCompletion.CURRENT_SCHEMA_VERSION + 1,
                "content", "model", 1L, 1L, "mock", 0L);

        // Treated as a miss so the format can change without a coordinated cache flush.
        assertThat(future.isReadable()).isFalse();
    }

    @Test
    void entryMissingRequiredFieldsIsNotReadable() {
        assertThat(new CachedCompletion(1, null, "model", 1L, 1L, "mock", 0L).isReadable())
                .isFalse();
        assertThat(new CachedCompletion(1, "content", null, 1L, 1L, "mock", 0L).isReadable())
                .isFalse();
    }

    @Test
    @DisplayName("unknown fields from a future version deserialise without throwing")
    void toleratesUnknownFields() throws Exception {
        String futureJson = """
                {"v":1,"c":"hi","m":"llama-3.1-8b","pt":1,"ct":2,"p":"mock","ts":0,
                 "somethingAddedLater":"value"}
                """;

        CachedCompletion parsed = mapper
                .copy()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature
                        .FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(futureJson, CachedCompletion.class);

        assertThat(parsed.content()).isEqualTo("hi");
    }

    @Test
    @DisplayName("serving a hit preserves the ORIGINAL generation time, not the serve time")
    void preservesOriginalCreationTime() {
        CachedCompletion stored = CachedCompletion.from(response());

        CompletionResponse served =
                stored.toResponse(CacheTier.TIER1_EXACT, Duration.ofMillis(2));

        // This is what lets a caller judge how stale an answer is. Overwriting it with
        // the serve time would make every cached response look freshly generated.
        assertThat(served.createdAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
        assertThat(served.latency()).isEqualTo(Duration.ofMillis(2));
        assertThat(served.servedBy()).isEqualTo(CacheTier.TIER1_EXACT);
        assertThat(served.provider()).isEqualTo(ProviderId.CACHE);
    }

    @Test
    @DisplayName("a cache hit reports prompt tokens but zero completion tokens")
    void cacheHitZeroesCompletionTokens() {
        CompletionResponse served = CachedCompletion.from(response())
                .toResponse(CacheTier.TIER1_EXACT, Duration.ofMillis(2));

        // The tenant's input was still processed, so prompt tokens are charged. No model
        // generated anything, so completion tokens are zero. That asymmetry is what the
        // "tokens saved" figure is computed from.
        assertThat(served.usage().promptTokens()).isEqualTo(10L);
        assertThat(served.usage().completionTokens()).isZero();
    }
}
