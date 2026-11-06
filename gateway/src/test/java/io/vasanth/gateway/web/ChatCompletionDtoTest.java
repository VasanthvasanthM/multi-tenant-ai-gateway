package io.vasanth.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.web.dto.ChatCompletionRequestDto;
import io.vasanth.gateway.web.dto.ChatCompletionResponseDto;
import io.vasanth.gateway.web.dto.ErrorResponseDto;

/** The external contract: OpenAI wire format in, OpenAI wire format out. */
class ChatCompletionDtoTest {

    private static final TenantId ACME = TenantId.of("acme");

    private static ChatCompletionRequestDto dto(
            Double temperature, Double topP, Integer maxTokens, Boolean stream) {
        return new ChatCompletionRequestDto(
                "llama-3.1-8b",
                List.of(new ChatCompletionRequestDto.MessageDto("user", "hello")),
                temperature, topP, maxTokens, stream, null);
    }

    @Nested
    @DisplayName("inbound mapping")
    class Inbound {

        @Test
        @DisplayName("temperature defaults to 0, NOT OpenAI's 1")
        void defaultsToDeterministicSampling() {
            // The one intentional divergence from the upstream contract, and the right
            // default for a caching gateway: at temperature 1 nothing is reproducible, so
            // the semantic cache can never engage and the product does not work.
            CompletionRequest request = dto(null, null, null, null).toDomain(ACME, "");

            assertThat(request.sampling().temperature()).isZero();
            assertThat(request.sampling().isDeterministic()).isTrue();
            assertThat(request.isSemanticCacheEligible()).isTrue();
        }

        @Test
        void explicitSamplingIsHonoured() {
            CompletionRequest request = dto(0.7d, 0.9d, 512, null).toDomain(ACME, "");

            assertThat(request.sampling().temperature()).isEqualTo(0.7d);
            assertThat(request.sampling().topP()).isEqualTo(0.9d);
            assertThat(request.sampling().maxTokens()).isEqualTo(512);
            assertThat(request.isSemanticCacheEligible())
                    .as("non-deterministic sampling must disqualify Tier 2")
                    .isFalse();
        }

        @Test
        @DisplayName("tenant comes from the filter, never from the request body")
        void tenantIsNotTakenFromTheBody() {
            // The rule that makes cross-tenant access impossible by construction rather
            // than by reviewing every handler.
            CompletionRequest request = dto(null, null, null, null)
                    .toDomain(TenantId.of("globex"), "");

            assertThat(request.tenantId().value()).isEqualTo("globex");
        }

        @ParameterizedTest(name = "role \"{0}\" maps to {1}")
        @CsvSource({"user, USER", "system, SYSTEM", "assistant, ASSISTANT",
                    "USER, USER", "System, SYSTEM"})
        void parsesRolesCaseInsensitively(String wire, Message.Role expected) {
            ChatCompletionRequestDto request = new ChatCompletionRequestDto(
                    "llama-3.1-8b",
                    List.of(new ChatCompletionRequestDto.MessageDto(wire, "hi")),
                    null, null, null, null, null);

            assertThat(request.toDomain(ACME, "").messages().get(0).role()).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"tool", "function", "bot", ""})
        void rejectsUnknownRoles(String wire) {
            ChatCompletionRequestDto request = new ChatCompletionRequestDto(
                    "llama-3.1-8b",
                    List.of(new ChatCompletionRequestDto.MessageDto(wire, "hi")),
                    null, null, null, null, null);

            assertThatThrownBy(() -> request.toDomain(ACME, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsupported role");
        }

        @Test
        void toolDefinitionsDisqualifyTheSemanticCache() {
            // Two prompts can be near-identical while the available tools differ
            // completely, and the correct answer depends on which tools exist.
            ChatCompletionRequestDto withTools = new ChatCompletionRequestDto(
                    "llama-3.1-8b",
                    List.of(new ChatCompletionRequestDto.MessageDto("user", "weather?")),
                    null, null, null, null, List.of("get_weather"));

            CompletionRequest request = withTools.toDomain(ACME, "");

            assertThat(request.hasToolDefinitions()).isTrue();
            assertThat(request.isSemanticCacheEligible()).isFalse();
        }

        @Test
        void streamFlagIsDetected() {
            assertThat(dto(null, null, null, true).wantsStream()).isTrue();
            assertThat(dto(null, null, null, false).wantsStream()).isFalse();
            assertThat(dto(null, null, null, null).wantsStream())
                    .as("absent means not streaming").isFalse();
        }

        @Test
        void absentIdempotencyKeyBecomesEmptyRatherThanNull() {
            assertThat(dto(null, null, null, null).toDomain(ACME, null).idempotencyKey())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("outbound mapping")
    class Outbound {

        private CompletionResponse response(CacheTier tier, Instant createdAt) {
            return new CompletionResponse(
                    "the answer", ModelId.of("llama-3.1-8b"),
                    new TokenUsage(10L, 20L), tier,
                    tier == CacheTier.UPSTREAM ? ProviderId.of("mock") : ProviderId.CACHE,
                    createdAt, Duration.ofMillis(42));
        }

        @Test
        void producesTheOpenAiEnvelope() {
            ChatCompletionResponseDto dto = ChatCompletionResponseDto.from(
                    response(CacheTier.UPSTREAM, Instant.parse("2026-11-02T10:00:00Z")),
                    Instant.parse("2026-11-02T10:00:00Z").getEpochSecond());

            assertThat(dto.object()).isEqualTo("chat.completion");
            assertThat(dto.id()).startsWith("chatcmpl-");
            assertThat(dto.choices()).hasSize(1);
            assertThat(dto.choices().get(0).message().role()).isEqualTo("assistant");
            assertThat(dto.choices().get(0).finishReason()).isEqualTo("stop");
            assertThat(dto.usage().totalTokens()).isEqualTo(30L);
        }

        @Test
        @DisplayName("content age exposes staleness to the caller")
        void reportsContentAgeForCachedAnswers() {
            Instant generated = Instant.parse("2026-11-02T10:00:00Z");
            long servedAt = generated.plusSeconds(300).getEpochSecond();

            ChatCompletionResponseDto dto = ChatCompletionResponseDto.from(
                    response(CacheTier.TIER1_EXACT, generated), servedAt);

            // Lets a caller decide a cached answer is too old for their purpose, rather
            // than that being knowable only to the operator.
            assertThat(dto.gateway().contentAgeSeconds()).isEqualTo(300L);
            assertThat(dto.gateway().cacheHit()).isTrue();
            assertThat(dto.gateway().servedBy()).isEqualTo("tier1_exact");
            assertThat(dto.gateway().provider()).isEqualTo("cache");
        }

        @Test
        void freshUpstreamAnswerHasZeroAge() {
            Instant now = Instant.parse("2026-11-02T10:00:00Z");

            ChatCompletionResponseDto dto = ChatCompletionResponseDto.from(
                    response(CacheTier.UPSTREAM, now), now.getEpochSecond());

            assertThat(dto.gateway().contentAgeSeconds()).isZero();
            assertThat(dto.gateway().cacheHit()).isFalse();
        }

        @Test
        @DisplayName("a clock skew cannot produce a negative age")
        void clampsNegativeAge() {
            Instant generated = Instant.parse("2026-11-02T10:00:00Z");

            ChatCompletionResponseDto dto = ChatCompletionResponseDto.from(
                    response(CacheTier.TIER1_EXACT, generated),
                    generated.minusSeconds(60).getEpochSecond());

            assertThat(dto.gateway().contentAgeSeconds()).isZero();
        }
    }

    @Nested
    @DisplayName("error envelope")
    class Errors {

        @ParameterizedTest(name = "{0} -> type {1}")
        @CsvSource({
            "401, authentication_error",
            "403, authentication_error",
            "429, rate_limit_error",
            "500, server_error",
            "503, server_error",
            "400, invalid_request_error",
            "413, invalid_request_error",
        })
        void classifiesByStatus(int status, String expectedType) {
            GatewayError error = switch (status) {
                case 401 -> new GatewayError.Unauthenticated("no key");
                case 403 -> new GatewayError.Forbidden("not allowed");
                case 429 -> new GatewayError.RateLimited(Duration.ofSeconds(1));
                case 500 -> GatewayError.Internal.generic();
                case 503 -> new GatewayError.UpstreamUnavailable("down");
                case 413 -> new GatewayError.PayloadTooLarge(100, 50);
                default -> new GatewayError.InvalidRequest("bad");
            };

            assertThat(ErrorResponseDto.from(error).error().type()).isEqualTo(expectedType);
        }

        @Test
        @DisplayName("retryable is explicit, because the status code is not enough")
        void exposesRetryability() {
            // 402 budget_exhausted and 429 rate_limited are both "you hit a limit", but
            // retrying the first is pointless until the budget is topped up. Leaving
            // clients to infer that from the status produces retry storms.
            ErrorResponseDto limited =
                    ErrorResponseDto.from(new GatewayError.RateLimited(Duration.ofSeconds(1)));
            ErrorResponseDto exhausted =
                    ErrorResponseDto.from(new GatewayError.BudgetExhausted(100L, 100L));

            assertThat(limited.error().retryable()).isTrue();
            assertThat(exhausted.error().retryable()).isFalse();
        }

        @Test
        void carriesTheStableMachineReadableCode() {
            assertThat(ErrorResponseDto.from(GatewayError.Internal.generic()).error().code())
                    .isEqualTo("internal_error");
        }
    }
}
