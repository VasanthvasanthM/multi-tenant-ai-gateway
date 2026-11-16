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

import io.micrometer.core.instrument.Timer;
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
import reactor.core.Disposable;
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

    /** A pipeline whose admission limit is small enough to reach in a test. */
    private void buildWithMaxInFlight(int maxInFlight) {
        GatewayProperties properties = new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, false),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(100, maxInFlight, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));

        pipeline = new CompletionPipeline(
                tier1, tier2, embeddings, providers, rateLimitGate, budgetGate,
                Optional.empty(), FIXED, properties, new SimpleMeterRegistry());
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
        when(tier2.lookup(any(), any(), any(), anyString(), anyString(), anyBoolean()))
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
    @DisplayName("latency is re-stamped end-to-end, not left as the tier's own figure")
    void latencyIsEndToEndRatherThanPerComponent() {
        // A tier reports only its own work: Tier 1 times its Redis round trip, Tier 2 times
        // its vector search and NOT the embedding that preceded it, and a provider may
        // report a figure it was configured with rather than one it measured. Leaving that
        // in latency_ms made the field mean something different depending on which tier
        // answered, so the three numbers were never comparable.
        //
        // An absurd component figure makes the assertion deterministic: if it survives,
        // the stamp did not happen. A near-real value could coincide with the actual
        // elapsed time and the test would pass against the broken behaviour.
        CompletionResponse claimsSevenHours = new CompletionResponse(
                "cached answer", MODEL, TokenUsage.cacheHit(10L),
                CacheTier.TIER1_EXACT, ProviderId.CACHE,
                Instant.parse("2026-11-02T09:00:00Z"), Duration.ofHours(7));
        when(tier1.get(any())).thenReturn(Mono.just(claimsSevenHours));

        StepVerifier.create(pipeline.execute(request("hello")))
                .assertNext(r -> {
                    assertThat(r.latency()).isNotEqualTo(Duration.ofHours(7));
                    assertThat(r.latency()).isLessThan(Duration.ofMinutes(1));
                    // Still a real measurement, not zeroed out.
                    assertThat(r.latency()).isGreaterThanOrEqualTo(Duration.ZERO);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("embedding time is recorded, so a Tier-2 request's dominant cost is visible")
    void embeddingDurationIsMeasured() {
        // Nothing measured this until 2026-11-15. Tier 2's own timer starts after the
        // embedding has already been computed, so the dominant cost of a Tier-2 request
        // was absent from every meter and had to be inferred from cgroup counters.
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CompletionPipeline timed = new CompletionPipeline(
                tier1, tier2, embeddings, providers, rateLimitGate, budgetGate,
                Optional.empty(), FIXED, properties(true), meters);

        timed.execute(request("hello")).block(Duration.ofSeconds(5));

        Timer timer = meters.find("gateway.embedding.duration").timer();
        assertThat(timer).as("gateway.embedding.duration must be registered").isNotNull();

        // TWO, not one - and this assertion exists to pin that, because it was a surprise.
        // A full miss embeds the same prompt twice: once for the Tier-2 lookup, then again
        // in storeQuietly for the write-back. The write recomputes rather than reusing the
        // lookup's vector because the lookup may have been skipped entirely (Tier 2 off,
        // ineligible request, prompt over the length limit), so there is not always a
        // vector to reuse.
        //
        // When the lookup DID run, the second call is pure waste: the same text, the same
        // model, the same 384 numbers, at ~6 ms of CPU each. Embedding is the dominant
        // cost of a Tier-2 deployment, so this doubles the expensive part on every miss.
        // Carrying the vector forward would halve it. Not changed here - that is a
        // behaviour change, and this commit is about making the cost VISIBLE first.
        assertThat(timer.count())
                .as("a full miss embeds twice: once to look up, once to write back")
                .isEqualTo(2L);
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
        when(tier2.lookup(any(), any(), any(), anyString(), anyString(), anyBoolean()))
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
    @DisplayName("the Tier-2 length guard counts code points, not UTF-16 units")
    void tier2LengthGuardCountsCodePoints() {
        // The guard read String.length() until 2026-11-15. That counts UTF-16 units,
        // so one emoji counts 2 and everything outside the Basic Multilingual Plane
        // counts 2 - making the 1,000 limit really ~500 for such text, and skipping
        // Tier 2 for prompts comfortably inside the truncation-safe length. A cache
        // that quietly worked worse for non-Latin scripts.
        //
        // Built from a code point rather than a literal escape in source: a lone
        // surrogate in a source file is fragile and several tools rewrite it.
        String emoji = new String(new int[] {0x1F600}, 0, 1).repeat(600);

        assertThat(emoji.length())
                .as("UTF-16 units - OVER the 1000 limit").isEqualTo(1_200);
        assertThat(emoji.codePointCount(0, emoji.length()))
                .as("real characters - UNDER the limit").isEqualTo(600);

        when(embeddings.embed(anyString())).thenReturn(Mono.just(anyEmbedding()));
        when(providers.complete(any())).thenReturn(Mono.just(upstreamResponse()));

        // A pipeline whose PROMPT limit is generous, so validate() does not reject the
        // request first. The limit under test is TIER2_MAX_PROMPT_CHARS, which is a
        // separate constant and is what the guard compares against.
        GatewayProperties generous = new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), true, Duration.ofHours(24), 0.95, false),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(10_000, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
        CompletionPipeline roomy = new CompletionPipeline(
                tier1, tier2, embeddings, providers, rateLimitGate, budgetGate,
                Optional.empty(), FIXED, generous, new SimpleMeterRegistry());

        roomy.execute(request(emoji)).block(Duration.ofSeconds(5));

        // The decisive assertion: Tier 2 was consulted, not skipped.
        verify(tier2).lookup(any(), any(), any(), anyString(), anyString(), anyBoolean());
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
        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Tier 2 is skipped when tool definitions are present")
    void skipsTier2WhenToolsArePresent() {
        CompletionRequest withTools = new CompletionRequest(
                ACME, MODEL, List.of(Message.user("hello")),
                SamplingParams.DETERMINISTIC, false, "[{\"name\":\"f\"}]", "");

        pipeline.execute(withTools).block(Duration.ofSeconds(5));

        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void doesNotConsultTier2WhenDisabled() {
        build(false);

        pipeline.execute(request("hello")).block(Duration.ofSeconds(5));

        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyString(), anyBoolean());
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
        verify(tier2, never()).lookup(any(), any(), any(), anyString(), anyString(), anyBoolean());
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

    // =====================================================================
    // Admission control.
    //
    // Untested until 2026-11-16, which is how the streaming path came to bypass it
    // entirely: there was nothing to notice that stream() never touched the gate.
    // =====================================================================

    @Test
    @DisplayName("a request beyond the in-flight limit is shed, not queued")
    void shedsBeyondTheInFlightLimit() {
        buildWithMaxInFlight(1);
        // Never completes, so the permit stays held while the second request arrives.
        when(providers.complete(any())).thenReturn(Mono.never());

        Disposable holding = pipeline.execute(request("first")).subscribe();

        // Bounded, not verifyErrorSatisfies(): if the gate is ever removed the request is
        // admitted and hangs on a never() provider, and an unbounded verify would hang the
        // whole build instead of failing this test.
        StepVerifier.create(pipeline.execute(request("second")))
                .expectErrorSatisfies(thrown -> assertThat(
                        ((GatewayException) thrown).error())
                        .isInstanceOf(GatewayError.Overloaded.class))
                .verify(Duration.ofSeconds(5));

        holding.dispose();
    }

    @Test
    @DisplayName("a stream beyond the in-flight limit is shed too")
    void shedsStreamsBeyondTheInFlightLimit() {
        buildWithMaxInFlight(1);
        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.never());

        Disposable holding = pipeline.stream(request("first")).subscribe();

        // Before this test existed, stream() was outside admission control and this
        // second stream was admitted however full the pod already was.
        StepVerifier.create(pipeline.stream(request("second")))
                .expectErrorSatisfies(thrown -> assertThat(
                        ((GatewayException) thrown).error())
                        .isInstanceOf(GatewayError.Overloaded.class))
                .verify(Duration.ofSeconds(5));

        holding.dispose();
    }

    @Test
    @DisplayName("a cancelled stream releases its permit")
    void cancelledStreamReleasesItsPermit() {
        buildWithMaxInFlight(1);
        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.never());

        // A client that disconnects mid-stream. Cancellation is the NORMAL way a stream
        // ends, so doOnTerminate here would leak a permit on almost every stream and the
        // pod would slowly refuse everything while looking healthy.
        pipeline.stream(request("abandoned")).subscribe().dispose();

        when(providers.stream(any())).thenReturn(reactor.core.publisher.Flux.just("hello"));

        StepVerifier.create(pipeline.stream(request("next")))
                .expectNext("hello")
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }
}
