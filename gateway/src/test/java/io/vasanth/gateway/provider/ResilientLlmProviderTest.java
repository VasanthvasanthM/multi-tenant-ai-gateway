package io.vasanth.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.LlmProvider;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The resilience decorator. */
class ResilientLlmProviderTest {

    private static final CompletionRequest REQUEST = CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("llama-3.1-8b"), "hello");

    private static final class CountingProvider implements LlmProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean failing;
        private volatile Duration delay = Duration.ZERO;

        CountingProvider(boolean failing) {
            this.failing = failing;
        }

        @Override
        public ProviderId id() {
            return ProviderId.of("mock");
        }

        @Override
        public Mono<CompletionResponse> complete(CompletionRequest request) {
            return Mono.defer(() -> {
                calls.incrementAndGet();
                if (failing) {
                    return Mono.<CompletionResponse>error(new GatewayException(
                            new GatewayError.UpstreamUnavailable("down")));
                }
                return Mono.just(new CompletionResponse(
                        "ok", request.model(), new TokenUsage(1L, 1L),
                        CacheTier.UPSTREAM, id(), Instant.EPOCH, Duration.ZERO));
            }).delayElement(delay);
        }

        @Override
        public Flux<String> stream(CompletionRequest request) {
            calls.incrementAndGet();
            return failing
                    ? Flux.error(new GatewayException(
                            new GatewayError.UpstreamUnavailable("down")))
                    : Flux.just("a", "b");
        }

        @Override
        public Mono<Boolean> isHealthy() {
            return Mono.just(!failing);
        }

        @Override
        public boolean supports(ModelId model) {
            return true;
        }
    }

    private static ResilientLlmProvider wrap(
            LlmProvider delegate, CircuitBreaker breaker, Retry retry) {
        return new ResilientLlmProvider(
                delegate,
                breaker,
                Bulkhead.of("test", BulkheadConfig.custom()
                        .maxConcurrentCalls(10).maxWaitDuration(Duration.ZERO).build()),
                TimeLimiter.of(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(2)).build()),
                retry);
    }

    private static CircuitBreaker breaker(int minimumCalls) {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());
    }

    private static Retry noRetry() {
        return Retry.of("test", RetryConfig.custom().maxAttempts(1).build());
    }

    @Test
    void passesThroughWhenHealthy() {
        ResilientLlmProvider provider =
                wrap(new CountingProvider(false), breaker(5), noRetry());

        StepVerifier.create(provider.complete(REQUEST))
                .assertNext(r -> assertThat(r.content()).isEqualTo("ok"))
                .verifyComplete();
    }

    @Test
    @DisplayName("the breaker opens after enough failures and then rejects without calling")
    void opensAndShedsLoad() {
        CountingProvider delegate = new CountingProvider(true);
        ResilientLlmProvider provider = wrap(delegate, breaker(5), noRetry());

        for (int i = 0; i < 5; i++) {
            provider.complete(REQUEST).onErrorResume(e -> Mono.empty())
                    .block(Duration.ofSeconds(5));
        }
        assertThat(provider.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = delegate.calls.get();
        StepVerifier.create(provider.complete(REQUEST))
                .expectErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    // CircuitOpen, not UpstreamUnavailable: a decision we made, not a
                    // failure we observed. Keeping them apart is what lets a dashboard
                    // distinguish deliberate shedding from a vendor being down.
                    assertThat(gatewayError).isInstanceOf(GatewayError.CircuitOpen.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(503);
                    assertThat(gatewayError.retryable()).isTrue();
                })
                .verify(Duration.ofSeconds(5));

        assertThat(delegate.calls.get())
                .as("an open circuit must not reach the delegate at all")
                .isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("an open circuit reports unhealthy regardless of the delegate")
    void healthReflectsBreakerState() {
        CountingProvider delegate = new CountingProvider(true);
        ResilientLlmProvider provider = wrap(delegate, breaker(3), noRetry());

        for (int i = 0; i < 3; i++) {
            provider.complete(REQUEST).onErrorResume(e -> Mono.empty())
                    .block(Duration.ofSeconds(5));
        }
        delegate.failing = false; // delegate recovers, breaker has not yet re-closed

        assertThat(provider.isHealthy().block(Duration.ofSeconds(5)))
                .as("a provider whose circuit is open is not usable, however cheerful "
                        + "its own health endpoint")
                .isFalse();
    }

    @Test
    @DisplayName("a slow call is cut off by the time limiter and recorded as a failure")
    void timesOutSlowCalls() {
        CountingProvider delegate = new CountingProvider(false);
        delegate.delay = Duration.ofSeconds(10);

        ResilientLlmProvider provider = wrap(delegate, breaker(5), noRetry());

        StepVerifier.create(provider.complete(REQUEST))
                .expectError()
                .verify(Duration.ofSeconds(8));
    }

    @Test
    void retriesOnceWhenConfiguredTo() {
        CountingProvider delegate = new CountingProvider(true);
        Retry twice = Retry.of("test", RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(GatewayException.class)
                .build());

        ResilientLlmProvider provider = wrap(delegate, breaker(100), twice);

        provider.complete(REQUEST).onErrorResume(e -> Mono.empty())
                .block(Duration.ofSeconds(5));

        assertThat(delegate.calls.get()).as("one attempt plus one retry").isEqualTo(2);
    }

    @Test
    void streamingIsGuardedButNotTimeLimited() {
        // A time limiter on a stream would cancel a healthy long generation partway
        // through; the point of streaming is that total duration is unbounded.
        ResilientLlmProvider provider =
                wrap(new CountingProvider(false), breaker(5), noRetry());

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("a", "b")
                .verifyComplete();
    }

    @Test
    void delegatesIdentityAndModelSupport() {
        ResilientLlmProvider provider =
                wrap(new CountingProvider(false), breaker(5), noRetry());

        assertThat(provider.id().value()).isEqualTo("mock");
        assertThat(provider.supports(ModelId.of("anything"))).isTrue();
    }
}
