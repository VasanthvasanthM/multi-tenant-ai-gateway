package io.vasanth.gateway.config;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Upstream provider connection settings.
 *
 * <p>One adapter serves every OpenAI-compatible vendor. Groq, OpenRouter, Together,
 * Fireworks, DeepInfra, Google AI Studio's compatibility endpoint and a local llama.cpp
 * server all speak the same wire format, so switching vendor is a {@code base-url} change
 * rather than a new class. That is worth more than a per-vendor SDK: vendor-specific
 * clients drift, break on upgrade, and each one is a separate thing to mock in tests.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.providers")
public record ProviderProperties(
        @NotNull OpenAiCompatible openaiCompatible,
        @NotNull Ollama ollama) {

    /**
     * @param enabled whether the adapter is registered at all
     * @param baseUrl vendor root, e.g. {@code https://api.groq.com/openai}
     * @param apiKey credential; read from the environment and never logged
     * @param connectTimeout TCP connect deadline
     * @param responseTimeout whole-response deadline, distinct from the per-attempt
     *     time limiter so that a vendor hanging after headers is still cut off
     * @param maxConnections connection pool ceiling
     * @param modelAliases maps a gateway model id to the vendor's name for it, so callers
     *     are not forced to change their requests when the vendor is switched
     */
    public record OpenAiCompatible(
            boolean enabled,
            String baseUrl,
            String apiKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration responseTimeout,
            @Min(1) int maxConnections,
            Map<String, String> modelAliases) {

        public OpenAiCompatible {
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            modelAliases = modelAliases == null ? Map.of() : Map.copyOf(modelAliases);

            if (enabled && baseUrl.isEmpty()) {
                throw new IllegalArgumentException(
                        "gateway.providers.openai-compatible.base-url is required when enabled");
            }
            // Checked rather than assumed: a blank key against a vendor that requires one
            // produces a 401 on every request, which looks like an outage. Failing at
            // startup names the cause.
            if (enabled && apiKey.isEmpty()) {
                throw new IllegalArgumentException(
                        "gateway.providers.openai-compatible.api-key is required when enabled; "
                                + "set OPENAI_COMPATIBLE_API_KEY in the environment");
            }
        }

        /** Resolves a gateway model id to the vendor's name, defaulting to identity. */
        public String resolveModel(String modelId) {
            return modelAliases.getOrDefault(modelId, modelId);
        }
    }

    /**
     * Ollama, for a locally hosted model.
     *
     * <p>Disabled by default, deliberately. On a CPU-only machine a 0.5B model yields
     * roughly 8-15 tokens per second, which makes every demo painful and - more
     * importantly - would contaminate any benchmark it took part in. It is implemented so
     * the option exists on a machine with a GPU, not because it is a good default here.
     */
    public record Ollama(
            boolean enabled,
            String baseUrl,
            @NotNull Duration responseTimeout,
            Map<String, String> modelAliases) {

        public Ollama {
            baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "http://localhost:11434"
                    : baseUrl.trim();
            modelAliases = modelAliases == null ? Map.of() : Map.copyOf(modelAliases);
        }

        public String resolveModel(String modelId) {
            return modelAliases.getOrDefault(modelId, modelId);
        }
    }
}
