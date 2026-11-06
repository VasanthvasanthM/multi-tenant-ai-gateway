package io.vasanth.gateway.web.dto;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.SamplingParams;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Inbound request, shaped to match the OpenAI chat-completions API.
 *
 * <h2>Why copy someone else's wire format</h2>
 *
 * <p>Every LLM client library, CLI and framework integration already speaks this shape.
 * Adopting it means a team can point an existing application at this gateway by changing a
 * base URL - no code change, no new SDK. Inventing a cleaner schema would be a better API
 * in isolation and a worse product, because adoption cost would exceed the benefit of
 * caching.
 *
 * <p>This DTO is deliberately separate from {@link CompletionRequest}. The external
 * contract is fixed by compatibility; the internal model is free to change. Collapsing them
 * would mean every internal refactor becomes a breaking API change.
 *
 * @param model model identifier
 * @param messages conversation turns
 * @param temperature randomness; defaults to 0, which is the only setting a cache can
 *     fully trust, and differs from OpenAI's default of 1 deliberately - see
 *     {@link #toDomain}
 * @param topP nucleus sampling cutoff
 * @param maxTokens generation cap
 * @param stream whether to return server-sent events
 * @param tools raw tool definitions, passed through and hashed into the cache key
 */
public record ChatCompletionRequestDto(
        @NotBlank String model,
        @NotEmpty List<MessageDto> messages,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @JsonProperty("top_p") @DecimalMin("0.0") @DecimalMax("1.0") Double topP,
        @JsonProperty("max_tokens") @Min(1) @Max(131_072) Integer maxTokens,
        Boolean stream,
        Object tools) {

    private static final double DEFAULT_TEMPERATURE = 0.0d;
    private static final double DEFAULT_TOP_P = 1.0d;
    private static final int DEFAULT_MAX_TOKENS = 1024;

    /**
     * One conversation turn.
     *
     * @param role one of {@code system}, {@code user}, {@code assistant}
     * @param content the text
     */
    public record MessageDto(@NotBlank String role, @NotBlank String content) {

        Message toDomain() {
            return new Message(parseRole(role), content);
        }

        private static Message.Role parseRole(String value) {
            for (Message.Role candidate : Message.Role.values()) {
                if (candidate.wireValue().equalsIgnoreCase(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException(
                    "unsupported role '" + value + "'; expected system, user or assistant");
        }
    }

    /**
     * Converts to the internal model.
     *
     * <p><b>Default temperature is 0, not OpenAI's 1.</b> This is the one intentional
     * divergence from the upstream contract, and it is the right default for a caching
     * gateway: at temperature 1 nothing is reproducible, so the semantic cache can never
     * engage and the product does not work. Callers who want sampling must ask for it
     * explicitly, which also means they have consciously opted out of Tier 2.
     *
     * @param tenantId resolved by the authentication filter, never taken from the body -
     *     accepting a client-supplied tenant id would let any caller read any tenant's
     *     cache
     * @param idempotencyKey from the request header, or {@code ""}
     */
    public CompletionRequest toDomain(TenantId tenantId, String idempotencyKey) {
        return new CompletionRequest(
                tenantId,
                ModelId.of(model),
                messages.stream().map(MessageDto::toDomain).toList(),
                new SamplingParams(
                        temperature != null ? temperature : DEFAULT_TEMPERATURE,
                        topP != null ? topP : DEFAULT_TOP_P,
                        maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS),
                Boolean.TRUE.equals(stream),
                serializeTools(),
                idempotencyKey == null ? "" : idempotencyKey);
    }

    /**
     * Tool definitions become a stable string for cache-key purposes.
     *
     * <p>{@code toString()} on the parsed JSON tree is sufficient because Jackson preserves
     * member order from the input. Two callers sending semantically identical tools with
     * differently ordered keys get different cache keys - a missed hit, never a wrong
     * answer, which is the correct direction to err.
     */
    private String serializeTools() {
        return tools == null ? "" : tools.toString();
    }

    public boolean wantsStream() {
        return Boolean.TRUE.equals(stream);
    }
}
