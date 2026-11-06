package io.vasanth.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
import io.vasanth.gateway.config.GatewayProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Fallback-chain behaviour. */
class ProviderRegistryTest {

    private static final CompletionRequest REQUEST = CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("llama-3.1-8b"), "hello");

    /** A provider that succeeds, fails, or only supports certain models. */
    private static final class StubProvider implements LlmProvider {
        private final ProviderId id;
        private final boolean fails;
        private final boolean supportsAll;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> streamChunks;

        StubProvider(String id, boolean fails) {
            this(id, fails, true, List.of("a", "b"));
        }

        StubProvider(String id, boolean fails, boolean supportsAll, List<String> streamChunks) {
            this.id = ProviderId.of(id);
            this.fails = fails;
            this.supportsAll = supportsAll;
            this.streamChunks = streamChunks;
        }

        @Override
        public ProviderId id() {
            return id;
        }

        @Override
        public Mono<CompletionResponse> complete(CompletionRequest request) {
            calls.incrementAndGet();
            if (fails) {
                return Mono.error(new GatewayException(
                        new GatewayError.UpstreamUnavailable(id + " is down")));
            }
            return Mono.just(new CompletionResponse(
                    "answer from " + id, request.model(), new TokenUsage(1L, 1L),
                    CacheTier.UPSTREAM, id, Instant.EPOCH, Duration.ZERO));
        }

        @Override
        public Flux<String> stream(CompletionRequest request) {
            calls.incrementAndGet();
            if (fails) {
                return Flux.error(new GatewayException(
                        new GatewayError.UpstreamUnavailable(id + " is down")));
            }
            return Flux.fromIterable(streamChunks);
        }

        @Override
        public Mono<Boolean> isHealthy() {
            return Mono.just(!fails);
        }

        @Override
        public boolean supports(ModelId model) {
            return supportsAll;
        }
    }

    private static GatewayProperties properties(String primary, List<String> fallback) {
        return new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, true),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(32000, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers(primary, fallback));
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        void rejectsDuplicateProviderIds() {
            assertThatThrownBy(() -> new ProviderRegistry(
                    List.of(new StubProvider("mock", false), new StubProvider("mock", false)),
                    properties("mock", List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("two providers claim id");
        }

        @Test
        void rejectsAConfiguredProviderThatIsNotOnTheClasspath() {
            // Fails at startup rather than on the first request, when it would look like
            // an outage.
            assertThatThrownBy(() -> new ProviderRegistry(
                    List.of(new StubProvider("mock", false)),
                    properties("groq", List.of())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not on the classpath");
        }
    }

    @Nested
    @DisplayName("fallback chain")
    class Fallback {

        @Test
        void usesThePrimaryWhenItSucceeds() {
            StubProvider primary = new StubProvider("mock", false);
            StubProvider backup = new StubProvider("mock-secondary", false);
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(primary, backup), properties("mock", List.of("mock-secondary")));

            StepVerifier.create(registry.complete(REQUEST))
                    .assertNext(r -> assertThat(r.provider().value()).isEqualTo("mock"))
                    .verifyComplete();

            assertThat(backup.calls.get()).as("backup must not be touched").isZero();
        }

        @Test
        void fallsBackWhenThePrimaryFails() {
            StubProvider primary = new StubProvider("mock", true);
            StubProvider backup = new StubProvider("mock-secondary", false);
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(primary, backup), properties("mock", List.of("mock-secondary")));

            StepVerifier.create(registry.complete(REQUEST))
                    .assertNext(r ->
                            assertThat(r.provider().value()).isEqualTo("mock-secondary"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("each provider is attempted at most once")
        void doesNotRetryWithinTheChain() {
            // An unbounded or retrying chain turns one upstream outage into an amplified
            // storm against every remaining vendor. Retry policy belongs to Resilience4j,
            // in one place.
            StubProvider primary = new StubProvider("mock", true);
            StubProvider backup = new StubProvider("mock-secondary", true);
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(primary, backup), properties("mock", List.of("mock-secondary")));

            StepVerifier.create(registry.complete(REQUEST))
                    .expectError(GatewayException.class)
                    .verify(Duration.ofSeconds(5));

            assertThat(primary.calls.get()).isEqualTo(1);
            assertThat(backup.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("the final error does not name the vendors that failed")
        void doesNotLeakVendorDetailToTheCaller() {
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(new StubProvider("mock", true)), properties("mock", List.of()));

            StepVerifier.create(registry.complete(REQUEST))
                    .expectErrorSatisfies(error -> {
                        GatewayError gatewayError = ((GatewayException) error).error();
                        assertThat(gatewayError)
                                .isInstanceOf(GatewayError.UpstreamUnavailable.class);
                        assertThat(gatewayError.retryable()).isTrue();
                    })
                    .verify(Duration.ofSeconds(5));
        }

        @Test
        void failsWhenNoProviderSupportsTheModel() {
            StubProvider narrow = new StubProvider("mock", false, false, List.of());
            ProviderRegistry registry =
                    new ProviderRegistry(List.of(narrow), properties("mock", List.of()));

            StepVerifier.create(registry.complete(REQUEST))
                    .expectErrorSatisfies(error -> assertThat(error.getMessage())
                            .contains("no provider supports model"))
                    .verify(Duration.ofSeconds(5));

            assertThat(narrow.calls.get()).as("an unsupported provider is never called")
                    .isZero();
        }
    }

    @Nested
    @DisplayName("streaming fallback")
    class Streaming {

        @Test
        void fallsBackWhenThePrimaryFailsBeforeAnyToken() {
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(new StubProvider("mock", true),
                            new StubProvider("mock-secondary", false)),
                    properties("mock", List.of("mock-secondary")));

            StepVerifier.create(registry.stream(REQUEST))
                    .expectNext("a", "b")
                    .verifyComplete();
        }

        @Test
        @DisplayName("a mid-stream failure terminates rather than switching provider")
        void doesNotSwitchProviderMidStream() {
            // Switching after bytes have reached the client would splice two different
            // answers together into incoherent output.
            LlmProvider failsMidway = new LlmProvider() {
                @Override
                public ProviderId id() {
                    return ProviderId.of("mock");
                }

                @Override
                public Mono<CompletionResponse> complete(CompletionRequest request) {
                    return Mono.error(new IllegalStateException("not used"));
                }

                @Override
                public Flux<String> stream(CompletionRequest request) {
                    return Flux.concat(
                            Flux.just("partial "),
                            Flux.error(new GatewayException(
                                    new GatewayError.UpstreamUnavailable("died mid-stream"))));
                }

                @Override
                public Mono<Boolean> isHealthy() {
                    return Mono.just(true);
                }

                @Override
                public boolean supports(ModelId model) {
                    return true;
                }
            };

            StubProvider backup = new StubProvider("mock-secondary", false);
            ProviderRegistry registry = new ProviderRegistry(
                    List.of(failsMidway, backup), properties("mock", List.of("mock-secondary")));

            StepVerifier.create(registry.stream(REQUEST))
                    .expectNext("partial ")
                    .expectError(GatewayException.class)
                    .verify(Duration.ofSeconds(5));

            assertThat(backup.calls.get())
                    .as("fallback must not engage once output has started")
                    .isZero();
        }
    }

    @Test
    void exposesProvidersById() {
        StubProvider mock = new StubProvider("mock", false);
        ProviderRegistry registry =
                new ProviderRegistry(List.of(mock), properties("mock", List.of()));

        assertThat(registry.byId(ProviderId.of("mock"))).isSameAs(mock);
        assertThat(registry.byId(ProviderId.of("absent"))).isNull();
    }
}
