package io.vasanth.gateway.web.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.vasanth.gateway.common.llm.CompletionResponse;

/**
 * Outbound response in the OpenAI chat-completions shape, plus gateway-specific extras.
 *
 * <p>The {@code gateway} member is an addition, not a replacement: clients that ignore
 * unknown fields - which every OpenAI SDK does - are unaffected, while clients that care
 * can see which tier served them and how stale the content is. Exposing this rather than
 * hiding it in server logs makes cache behaviour auditable from the caller's side.
 */
public record ChatCompletionResponseDto(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage,
        GatewayMetadata gateway) {

    public record Choice(
            int index,
            @JsonProperty("message") MessageDto message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    public record MessageDto(String role, String content) {
    }

    public record Usage(
            @JsonProperty("prompt_tokens") long promptTokens,
            @JsonProperty("completion_tokens") long completionTokens,
            @JsonProperty("total_tokens") long totalTokens) {
    }

    /**
     * Gateway-specific response metadata.
     *
     * @param servedBy which tier answered: {@code tier1_exact}, {@code tier2_semantic} or
     *     {@code upstream}
     * @param provider upstream that produced the content, or {@code cache}
     * @param cacheHit convenience flag
     * @param contentAgeSeconds how long ago the content was originally generated; zero for
     *     a fresh upstream call. This is what lets a caller decide a cached answer is too
     *     old for their purpose
     * @param latencyMillis wall-clock time this request took
     */
    public record GatewayMetadata(
            @JsonProperty("served_by") String servedBy,
            String provider,
            @JsonProperty("cache_hit") boolean cacheHit,
            @JsonProperty("content_age_seconds") long contentAgeSeconds,
            @JsonProperty("latency_ms") long latencyMillis) {
    }

    public static ChatCompletionResponseDto from(CompletionResponse response, long nowEpochSecond) {
        long contentAge = Math.max(0L, nowEpochSecond - response.createdAt().getEpochSecond());

        return new ChatCompletionResponseDto(
                "chatcmpl-" + UUID.randomUUID(),
                "chat.completion",
                nowEpochSecond,
                response.model().value(),
                List.of(new Choice(
                        0,
                        new MessageDto("assistant", response.content()),
                        "stop")),
                new Usage(
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        response.usage().total()),
                new GatewayMetadata(
                        response.servedBy().label(),
                        response.provider().value(),
                        response.wasCacheHit(),
                        contentAge,
                        response.latency().toMillis()));
    }
}
