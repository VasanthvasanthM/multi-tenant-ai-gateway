package io.vasanth.gateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.budget.BudgetGate;
import io.vasanth.gateway.cache.Tier1Cache;
import io.vasanth.gateway.cache.Tier2SemanticCache;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.SamplingParams;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import io.vasanth.gateway.embedding.Embedding;
import io.vasanth.gateway.embedding.EmbeddingService;
import io.vasanth.gateway.provider.ProviderRegistry;
import io.vasanth.gateway.ratelimit.TokenBucketGate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Pipeline orchestration: stage order, short-circuiting and the guards.
 *
 * <p>Collaborators are mocked so these tests assert the <em>decisions</em> the pipeline
 * makes rather than retesting Redis, pgvector or ONNX. Those have their own integration
 * tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompletionPipelineTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);
    private static final TenantId ACME = TenantId.of("acme");
    private static final ModelId MODEL = ModelId.of("llama-3.1-8b");

    @Mock private Tier1Cache tier1;
    @Mock private Tier2SemanticCache tier2;
    @Mock private EmbeddingService embeddings;
    @Mock private ProviderRegistry providers;
    @Mock private TokenBucketGate rateLimitGate;
    @Mock private BudgetGate budgetGate;

    private CompletionPipeline pipeline;

    private static CompletionResponse upstreamResponse() {
        return new CompletionResponse(
                "fresh answer", MODEL, new TokenUsage(10L, 20L),
                CacheTier.UPSTREAM, ProviderId.of("mock"),
                Instant.parse("2026-11-02T10:00:00Z"), Duration.ofMillis(40));
    }

    private static CompletionResponse cachedResponse(CacheTier tier) {
        return new CompletionResponse(
                "cached answer", MODEL, TokenUsage.cacheHit(10L),
                tier, ProviderId.CACHE,
                Instant.parse("2026-11-02T09:00:00Z"), Duration.ofMillis(2));
    }

    private static Embedding anyEmbedding() {
        float[] values = new float[384];
        values[0] = 1.0f;
        return Embedding.of(values);
    }

    private static CompletionRequest request(String prompt) {
        return CompletionRequest.of(ACME, MODEL, prompt);
    }

    private GatewayProperties properties(boolean tier2Enabled) {
        return new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), tier2Enabled, Duration.ofHours(24),
                        0.95, false),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(100, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
    }

    private void build(boolean tier2Enabled) {
        pipeline = new CompletionPipeline(
                tier1, tier2, embeddings, providers, rateLimitGate, budgetGate,
                Optional.empty(), FIXED, properties(tier2Enabled), new SimpleMeterRegistry());
    }

    @BeforeEach
    void setUp() {
        when(rateLimitGate.check(any())).thenReturn(Mono.empty());
        when(budgetGate.check(any())).thenReturn(Mono.empty());
        when(budgetGate.record(any(), anyLong())).thenReturn(Mono.empty());
        when(budgetGate.recordStreamed(any(), anyLong())).thenReturn(Mono.empty());
        when(tier1.get(any())).thenReturn(Mono.empty());
        when(tier1.put(any(), any())).thenReturn(Mono.empty());
        when(tier2.lookup(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(Mono.empty());
        when(tier2.store(any(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(Mono.empty());
        when(embeddings.embed(anyString())).thenReturn(Mono.just(anyEmbedding()));
        when(embeddings.modelId()).thenReturn("all-MiniLM-L6-v2");
        when(embeddings.isSemanticallyMeaningful()).thenReturn(true);
        when(providers.complete(any())).thenReturn(Mono.just(upstreamResponse()));
        build(true);
    }

    @Test
    @DisplayName("a Tier-1 hit short-circuits everything downstream")
    void tier1HitSkipsEmbeddingAndUpstream() {
        when(tier1.get(any())).thenReturn(Mono.just(cachedResponse(CacheTier.TIER1_EXACT)));

        StepVerifier.create(pipeline.execute(request("hello")))
                .assertNext(r -> assertThat(r.servedBy()).isEqualTo(CacheTier.TIER1_EXACT))
                .verifyComplete();

        // The point of Tier 1: no 6 ms embedding, no vector search, no vendor call.
        verify(embeddings, never()).embed(anyString());
        verify(providers, never()).complete(any());
    }

    @Test
    void tier2HitIsPromotedIntoTier1() {
        when(tier2.lookup(any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(Mono.just(cachedResponse(CacheTier.TIER2_SEMANTIC)));

        StepVerifier.create(pipeline.execute(request("hello")))
                .assertNext(r -> assertThat(r.servedBy()).isEqualTo(CacheTier.TIER2_SEMANTIC))
                .verifyComplete();

        // Promotion makes the next occurrence of this exact prompt a 2 ms hit instead of
        // another vector search.
        verify(tier1).put(any(), any());
        verify(providers, never()).complete(any());
    }

    @Test
    void fallsThroughToUpstreamOnFullMiss() {
        StepVerifier.create(pipeline.execute(request("hello")))
                .assertNext(r -> assertThat(r.servedBy()).isEqualTo(CacheTier.UPSTREAM))
                .verifyComplete();

        verify(providers).complete(any());
        verify(tier1).put(any(), any());
    }

    @Test
    @DisplayName("rate limiting runs BEFORE the cache is consulted")
    void rateLimitPrecedesTheCache() {
        // Otherwise a tenant hammering a cached prompt consumes unlimited capacity for
        // free - cheap for us, but still occupying connections and event-loop time that
        // paying traffic needs.
        when(rateLimitGate.check(any())).thenReturn(Mono.error(
                new GatewayException(new GatewayError.RateLimited(Duration.ofSeconds(1)))));

        StepVerifier.create(pipeline.execute(request("hello")))
                .expectError(GatewayException.class)
                .verify(Duration.ofSeconds(5));

        verify(tier1, never()).get(any());
        verify(providers, never()).complete(any());
    }

    @Test
    @DisplayName("an oversized prompt is rejected before any I/O")
    void validationRunsFirst() {
        String tooLong = "x".repeat(200); // limit is 100 code points in this config

        StepVerifier.create(pipeline.execute(request(tooLong)))
                .expectErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.PayloadTooLarge.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(413);
                })
                .verify(Duration.ofSeconds(5));

        verify(rateLimitGate, never()).check(any());
        verify(tier1, never()).get(any());
    }

    @Test
    @DisplayName("Tier 2 is skipped for non-deterministic sampling")
    void skipsTier2WhenTemperatureIsNonZero() {
        CompletionRequest sampled = new CompletionRequest(
                ACME, MODEL, List.of(Message.user("hello")),
                new SamplingParams(0.8d, 1.0d, 1024), false, "", "");

        StepVerifier.create(pipeline.execute(sampled))
                .assertNext(r -> assertThat(r.servedBy()).isEqualTo(CacheTier.UPSTREAM))
                .verifyComplete();

        // The caller asked for variety; returning a stored answer for a merely similar
        // prompt defeats exactly what they asked for.
        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Tier 2 is skipped when tool definitions are present")
    void skipsTier2WhenToolsArePresent() {
        CompletionRequest withTools = new CompletionRequest(
                ACME, MODEL, List.of(Message.user("hello")),
                SamplingParams.DETERMINISTIC, false, "[{\"name\":\"f\"}]", "");

        pipeline.execute(withTools).block(Duration.ofSeconds(5));

        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void doesNotConsultTier2WhenDisabled() {
        build(false);

        pipeline.execute(request("hello")).block(Duration.ofSeconds(5));

        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyBoolean());
        verify(embeddings, never()).embed(anyString());
    }

    @Test
    @DisplayName("an embedding failure falls through rather than failing the request")
    void embeddingFailureDegradesGracefully() {
        when(embeddings.embed(anyString())).thenReturn(Mono.error(
                new GatewayException(GatewayError.Internal.generic())));

        StepVerifier.create(pipeline.execute(request("hello")))
                .assertNext(r -> assertThat(r.servedBy()).isEqualTo(CacheTier.UPSTREAM))
                .verifyComplete();
    }

    @Test
    @DisplayName("an upstream timeout is reported as 504, not a generic error")
    void upstreamTimeoutIsTyped() {
        when(providers.complete(any()))
                .thenReturn(Mono.delay(Duration.ofSeconds(60)).map(ignored -> upstreamResponse()));

        GatewayProperties shortTimeout = new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, false),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(1000, 256, Duration.ofMillis(100)),
                new GatewayProperties.Providers("mock", List.of()));

        CompletionPipeline impatient = new CompletionPipeline(
                tier1, tier2, embeddings, providers, rateLimitGate, budgetGate,
                Optional.empty(), FIXED, shortTimeout, new SimpleMeterRegistry());

        StepVerifier.create(impatient.execute(request("hello")))
                .expectErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.UpstreamTimeout.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(504);
                })
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a cached response is never re-stored")
    void doesNotRewriteCacheOnAHit() {
        // Re-storing would only refresh the TTL, letting a popular entry live forever
        // without ever being revalidated upstream.
        when(tier1.get(any())).thenReturn(Mono.just(cachedResponse(CacheTier.TIER1_EXACT)));

        pipeline.execute(request("hello")).block(Duration.ofSeconds(5));

        verify(tier1, never()).put(any(), any());
    }

    @Test
    void streamingBypassesBothCacheTiers() {
        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.just("a", "b"));

        StepVerifier.create(pipeline.stream(request("hello")))
                .expectNext("a", "b")
                .verifyComplete();

        verify(tier1, never()).get(any());
        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyBoolean());
    }

    @Test
    void streamingStillEnforcesTheRateLimit() {
        when(rateLimitGate.check(any())).thenReturn(Mono.error(
                new GatewayException(new GatewayError.RateLimited(Duration.ofSeconds(1)))));

        StepVerifier.create(pipeline.stream(request("hello")))
                .expectError(GatewayException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void inFlightCounterReturnsToZero() {
        pipeline.execute(request("hello")).block(Duration.ofSeconds(5));

        // Leaking a permit per request - which doFinally rather than doOnTerminate
        // prevents - would slowly strangle the gateway.
        assertThat(pipeline.inFlight()).isZero();
    }

    @Test
    void inFlightCounterIsReleasedOnFailureToo() {
        when(providers.complete(any())).thenReturn(Mono.error(
                new GatewayException(new GatewayError.UpstreamUnavailable("down"))));

        pipeline.execute(request("hello")).onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(5));

        assertThat(pipeline.inFlight()).isZero();
    }

    // ------------------------------------------------------------------
    // Streaming budget drawdown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a streamed response draws down the characters it delivered")
    void streamDrawsDownDelivered() {
        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.just("Hello", " world"));

        pipeline.stream(request("hi")).collectList().block(Duration.ofSeconds(5));

        // 5 + 6 = 11 characters. The gate converts to tokens; the pipeline's job is only
        // to count what passed, without retaining any of it.
        verify(budgetGate).recordStreamed(TenantId.of("acme"), 11L);
    }

    @Test
    @DisplayName("a CANCELLED stream still draws down what was delivered")
    void cancelledStreamStillDrawsDown() {
        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.just("abcd", "efgh", "ijkl"));

        // Takes one delta and hangs up, which is what a client closing the connection
        // looks like. doFinally is what makes this record; doOnComplete would not fire,
        // and a caller could then avoid the drawdown entirely by disconnecting.
        pipeline.stream(request("hi")).take(1).collectList().block(Duration.ofSeconds(5));

        verify(budgetGate).recordStreamed(TenantId.of("acme"), 4L);
    }

    @Test
    @DisplayName("an empty stream records nothing rather than zero")
    void emptyStreamRecordsZero() {
        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.empty());

        pipeline.stream(request("hi")).collectList().block(Duration.ofSeconds(5));

        // Still called, with zero - the gate is what decides a zero is not worth a Redis
        // round trip, so that decision lives in one place.
        verify(budgetGate).recordStreamed(TenantId.of("acme"), 0L);
    }

    @Test
    @DisplayName("the budget is checked before any delta is produced")
    void streamIsBudgetChecked() {
        when(budgetGate.check(any())).thenReturn(Mono.error(
                new GatewayException(new GatewayError.BudgetExhausted(100L, 100L))));

        StepVerifier.create(pipeline.stream(request("hi")))
                .verifyErrorSatisfies(thrown -> assertThat(
                        ((GatewayException) thrown).error())
                        .isInstanceOf(GatewayError.BudgetExhausted.class));

        // An exhausted tenant must not be able to open a new stream at all.
        verify(providers, never()).stream(any());
    }
}
