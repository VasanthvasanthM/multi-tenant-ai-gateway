package io.vasanth.gateway.common.llm;

import java.util.List;
import java.util.Objects;

import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * A completion request as the gateway understands it, after parsing and validation.
 *
 * <p>Every field that can change the model's output appears here, because this record is the
 * sole input to cache key derivation. Adding a new request parameter without adding it to
 * this record - and therefore to the key - is how a cache silently starts returning answers
 * computed under different settings.
 *
 * @param tenantId owning tenant; also the shard key
 * @param model target model
 * @param messages conversation turns, never empty
 * @param sampling parameters that affect output
 * @param stream whether the caller wants server-sent events
 * @param toolDefinitions raw tool/function JSON, or {@code ""} when none
 * @param idempotencyKey caller-supplied retry key, or {@code ""} when none
 */
public record CompletionRequest(
        TenantId tenantId,
        ModelId model,
        List<Message> messages,
        SamplingParams sampling,
        boolean stream,
        String toolDefinitions,
        String idempotencyKey) {

    public CompletionRequest {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(messages, "messages must not be null");
        Objects.requireNonNull(sampling, "sampling must not be null");

        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }

        // Defensive copy: records expose their components directly, so without this a caller
        // retaining the original list could mutate a request after its cache key was
        // computed - producing an entry stored under a key that no longer describes it.
        messages = List.copyOf(messages);

        // Absent optional fields are normalised to "" rather than left null. Null here would
        // have to be handled at every key-derivation and serialisation site, and missing one
        // produces either an NPE or - far worse - a key that silently omits the field.
        toolDefinitions = toolDefinitions == null ? "" : toolDefinitions;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
    }

    /** Convenience factory for a single-turn, non-streaming, deterministic request. */
    public static CompletionRequest of(TenantId tenantId, ModelId model, String prompt) {
        return new CompletionRequest(
                tenantId,
                model,
                List.of(Message.user(prompt)),
                SamplingParams.DETERMINISTIC,
                false,
                "",
                "");
    }

    public boolean hasToolDefinitions() {
        return !toolDefinitions.isEmpty();
    }

    public boolean hasIdempotencyKey() {
        return !idempotencyKey.isEmpty();
    }

    /**
     * Whether this request may consult the semantic (Tier 2) cache.
     *
     * <p>Two independent reasons to refuse:
     *
     * <ul>
     *   <li><b>Non-deterministic sampling.</b> The caller asked for variety; returning a
     *       stored answer for a merely similar prompt defeats that.
     *   <li><b>Tool definitions present.</b> Two prompts can be semantically near-identical
     *       while the tools available to the model differ completely, and the correct answer
     *       depends on which tools exist. Similarity over the prompt text alone cannot see
     *       that difference.
     * </ul>
     */
    public boolean isSemanticCacheEligible() {
        return sampling.isDeterministic() && !hasToolDefinitions();
    }
}
