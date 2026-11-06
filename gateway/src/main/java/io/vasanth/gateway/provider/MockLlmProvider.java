package io.vasanth.gateway.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.LlmProvider;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.config.MockProviderProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Deterministic in-process provider. The default, and the only provider used by load tests
 * and CI.
 *
 * <h2>Why benchmarks must run against this</h2>
 *
 * <p>Pointing a load test at a real vendor measures the vendor's queue depth, their regional
 * routing and their rate limiter - not this gateway. The numbers move between runs for
 * reasons nobody controls, which makes a scalability fit meaningless. A deterministic
 * provider with configurable latency removes that variable entirely, so a change in measured
 * throughput can only have come from a change in the gateway.
 *
 * <p>It also costs nothing, needs no API key, and works offline.
 *
 * <h2>Determinism</h2>
 *
 * <p>Output is derived from the request content, so the same prompt always yields the same
 * answer. That is what makes it possible to assert that a cached response matches what the
 * provider would have produced - with a real model, non-zero temperature makes that
 * assertion impossible.
 *
 * <h2>Fault injection</h2>
 *
 * <p>{@code failureRate} and {@code latency} drive the resilience demos: the circuit breaker
 * opening, the fallback chain engaging, and timeouts firing can all be shown without
 * depending on a third party actually being broken at the right moment.
 */
public class MockLlmProvider implements LlmProvider {


    /** Rough industry average; good enough for a mock, and documented as an estimate. */
    private static final double CHARS_PER_TOKEN = 4.0d;

    private final ProviderId id;
    private final MockProviderProperties properties;
    private final Clock clock;
    private final AtomicLong callCount = new AtomicLong();

    public MockLlmProvider(ProviderId id, MockProviderProperties properties, Clock clock) {
        this.id = id;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ProviderId id() {
        return id;
    }

    @Override
    public Mono<CompletionResponse> complete(CompletionRequest request) {
        return Mono.defer(() -> {
                    callCount.incrementAndGet();
                    if (shouldFail()) {
                        return Mono.error(new GatewayException(new GatewayError.UpstreamUnavailable(
                                "mock provider: injected failure")));
                    }
                    return Mono.just(buildResponse(request));
                })
                // delayElement rather than Thread.sleep: this must not occupy an event loop
                // thread. A blocking sleep here would stall every other request sharing the
                // loop and make the mock itself the bottleneck being measured.
                .delaySubscription(properties.latency());
    }

    @Override
    public Flux<String> stream(CompletionRequest request) {
        return Mono.defer(() -> {
                    if (shouldFail()) {
                        return Mono.<String>error(new GatewayException(
                                new GatewayError.UpstreamUnavailable(
                                        "mock provider: injected failure")));
                    }
                    return Mono.just(generateContent(request));
                })
                .flatMapMany(MockLlmProvider::splitIntoDeltas)
                // Spread the configured latency across the stream so that time-to-first-token
                // and inter-token delay both look realistic, rather than the whole response
                // arriving at once after a pause.
                .delayElements(properties.interTokenDelay());
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.just(properties.healthy());
    }

    @Override
    public boolean supports(ModelId model) {
        // The mock stands in for any model, which is what lets it serve as a fallback for
        // every provider in resilience tests.
        return true;
    }

    /** Calls received since startup. Used by tests to assert the cache prevented a call. */
    public long callCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0L);
    }

    private boolean shouldFail() {
        double rate = properties.failureRate();
        return rate > 0.0d && ThreadLocalRandom.current().nextDouble() < rate;
    }

    private CompletionResponse buildResponse(CompletionRequest request) {
        String content = generateContent(request);
        long promptChars = request.messages().stream()
                .mapToLong(m -> m.content().length())
                .sum();

        return new CompletionResponse(
                content,
                request.model(),
                new TokenUsage(
                        estimateTokens(promptChars), estimateTokens(content.length())),
                CacheTier.UPSTREAM,
                id,
                Instant.now(clock),
                properties.latency());
    }

    /**
     * Derives a stable answer from the request.
     *
     * <p>Uses {@code hashCode} rather than a cryptographic digest deliberately: this value
     * is never a cache key or a security boundary, only filler text, and the mock should add
     * as little CPU cost as possible to a path whose latency is being measured.
     */
    private static String generateContent(CompletionRequest request) {
        String lastUserMessage = request.messages().stream()
                .filter(m -> m.role() == Message.Role.USER)
                .map(Message::content)
                .reduce((first, second) -> second)
                .orElse("");

        int fingerprint = lastUserMessage.hashCode();
        return "mock response to \"" + truncate(lastUserMessage)
                + "\" [model=" + request.model().value()
                + ", fingerprint=" + Integer.toHexString(fingerprint) + "]";
    }

    private static Flux<String> splitIntoDeltas(String content) {
        // Emits whitespace-delimited chunks, each carrying its trailing space, so that
        // concatenating every delta reproduces the original exactly. Returning cumulative
        // snapshots instead would make bandwidth quadratic in response length.
        String[] words = content.split(" ");
        return Flux.range(0, words.length)
                .map(i -> i < words.length - 1 ? words[i] + ' ' : words[i]);
    }

    private static long estimateTokens(long characters) {
        return Math.max(1L, Math.round(characters / CHARS_PER_TOKEN));
    }

    private static String truncate(String text) {
        int limit = 60;
        if (text.codePointCount(0, text.length()) <= limit) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, limit)) + "...";
    }

    /** Exposed so tests can assert the configured delay without reaching into properties. */
    Duration configuredLatency() {
        return properties.latency();
    }
}
