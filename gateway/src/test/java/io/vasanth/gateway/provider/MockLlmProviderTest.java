package io.vasanth.gateway.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.SamplingParams;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.MockProviderProperties;
import reactor.test.StepVerifier;

/**
 * The mock is the measurement instrument for every benchmark in this project, so its own
 * properties - determinism above all - need to hold.
 */
class MockLlmProviderTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC);

    private static final CompletionRequest REQUEST = CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("llama-3.1-8b"), "what is a semantic cache?");

    private static MockLlmProvider provider(MockProviderProperties properties) {
        return new MockLlmProvider(ProviderId.of("mock"), properties, FIXED);
    }

    @Test
    @DisplayName("the same prompt always produces the same answer")
    void isDeterministic() {
        // The property the whole benchmarking approach rests on. With a real model at
        // non-zero temperature, no assertion comparing a cached response to a freshly
        // generated one would be possible.
        MockLlmProvider mock = provider(MockProviderProperties.instant());

        String first = mock.complete(REQUEST).block(Duration.ofSeconds(5)).content();
        for (int i = 0; i < 50; i++) {
            assertThat(mock.complete(REQUEST).block(Duration.ofSeconds(5)).content())
                    .isEqualTo(first);
        }
    }

    @Test
    void differentPromptsProduceDifferentAnswers() {
        MockLlmProvider mock = provider(MockProviderProperties.instant());

        CompletionRequest other = CompletionRequest.of(
                TenantId.of("acme"), ModelId.of("llama-3.1-8b"), "something else entirely");

        assertThat(mock.complete(other).block(Duration.ofSeconds(5)).content())
                .isNotEqualTo(mock.complete(REQUEST).block(Duration.ofSeconds(5)).content());
    }

    @Test
    void reportsItsOwnIdAndUpstreamTier() {
        CompletionResponse response = provider(MockProviderProperties.instant())
                .complete(REQUEST).block(Duration.ofSeconds(5));

        assertThat(response.provider().value()).isEqualTo("mock");
        assertThat(response.servedBy()).isEqualTo(CacheTier.UPSTREAM);
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-11-02T10:00:00Z"));
    }

    @Test
    void estimatesNonZeroTokenCounts() {
        CompletionResponse response = provider(MockProviderProperties.instant())
                .complete(REQUEST).block(Duration.ofSeconds(5));

        assertThat(response.usage().promptTokens()).isPositive();
        assertThat(response.usage().completionTokens()).isPositive();
        assertThat(response.usage().total())
                .isEqualTo(response.usage().promptTokens()
                        + response.usage().completionTokens());
    }

    @Test
    @DisplayName("injected failures are what make the resilience demos possible")
    void alwaysFailsAtFailureRateOne() {
        MockLlmProvider mock = provider(
                new MockProviderProperties(Duration.ZERO, Duration.ZERO, 1.0d, true));

        StepVerifier.create(mock.complete(REQUEST))
                .expectError(GatewayException.class)
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void neverFailsAtFailureRateZero() {
        MockLlmProvider mock = provider(MockProviderProperties.instant());

        for (int i = 0; i < 100; i++) {
            assertThat(mock.complete(REQUEST).block(Duration.ofSeconds(5))).isNotNull();
        }
    }

    @Test
    void healthIsConfigurable() {
        assertThat(provider(new MockProviderProperties(
                Duration.ZERO, Duration.ZERO, 0.0d, false))
                .isHealthy().block(Duration.ofSeconds(5))).isFalse();
    }

    @Test
    void supportsEveryModelSoItCanServeAsAUniversalFallback() {
        MockLlmProvider mock = provider(MockProviderProperties.instant());

        assertThat(mock.supports(ModelId.of("llama-3.1-8b"))).isTrue();
        assertThat(mock.supports(ModelId.of("some/unknown-model"))).isTrue();
    }

    @Test
    @DisplayName("concatenating stream deltas reproduces the buffered answer exactly")
    void streamDeltasConcatenateToTheFullResponse() {
        // Deltas are incremental, not cumulative snapshots - otherwise bandwidth would be
        // quadratic in response length.
        MockLlmProvider mock = provider(MockProviderProperties.instant());

        List<String> deltas = mock.stream(REQUEST).collectList().block(Duration.ofSeconds(5));
        String buffered = mock.complete(REQUEST).block(Duration.ofSeconds(5)).content();

        assertThat(String.join("", deltas)).isEqualTo(buffered);
    }

    @Test
    void countsCallsSoTestsCanAssertTheCachePreventedOne() {
        MockLlmProvider mock = provider(MockProviderProperties.instant());
        mock.resetCallCount();

        mock.complete(REQUEST).block(Duration.ofSeconds(5));
        mock.complete(REQUEST).block(Duration.ofSeconds(5));

        assertThat(mock.callCount()).isEqualTo(2L);
    }

    @Test
    void usesTheLastUserMessageForContent() {
        CompletionRequest conversation = new CompletionRequest(
                TenantId.of("acme"),
                ModelId.of("llama-3.1-8b"),
                List.of(Message.system("be brief"),
                        Message.user("first question"),
                        Message.assistant("first answer"),
                        Message.user("the actual question")),
                SamplingParams.DETERMINISTIC, false, "", "");

        String content = provider(MockProviderProperties.instant())
                .complete(conversation).block(Duration.ofSeconds(5)).content();

        assertThat(content).contains("the actual question").doesNotContain("first question");
    }
}
