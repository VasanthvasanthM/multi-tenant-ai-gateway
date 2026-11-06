package io.vasanth.gateway.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.LlmProvider;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.config.ProviderProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Locally hosted models via Ollama.
 *
 * <h2>Off by default, and that is a measurement decision</h2>
 *
 * <p>On a CPU-only machine a small model yields roughly 8-15 tokens per second. That makes
 * demos painful, but the real objection is that it would contaminate every benchmark it
 * took part in: a scalability measurement that includes a 20-second generation is
 * measuring the model, not the gateway. The deterministic mock exists precisely so that
 * variable is removable.
 *
 * <p>It is implemented because the option genuinely matters on a machine with a GPU, and
 * because "runs entirely offline with no vendor account" is a real deployment mode.
 *
 * <h2>Ollama does not speak the OpenAI format</h2>
 *
 * <p>Its {@code /api/chat} endpoint takes a different body and - importantly - streams
 * <b>newline-delimited JSON</b> rather than server-sent events. That difference alone
 * justifies a separate class instead of bending the OpenAI adapter around it.
 */
public class OllamaProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaProvider.class);

    private static final ProviderId ID = ProviderId.of("ollama");

    private final WebClient webClient;
    private final ProviderProperties.Ollama config;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OllamaProvider(
            WebClient webClient,
            ProviderProperties.Ollama config,
            ObjectMapper objectMapper,
            Clock clock) {
        this.webClient = webClient;
        this.config = config;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public ProviderId id() {
        return ID;
    }

    @Override
    public Mono<CompletionResponse> complete(CompletionRequest request) {
        long startNanos = System.nanoTime();

        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toBody(request, false))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> {
                            log.warn("ollama returned {}: {}", response.statusCode().value(), body);
                            // A 404 from Ollama almost always means the model has not been
                            // pulled, which is the single most common setup mistake. Saying
                            // so beats a generic "upstream unavailable".
                            if (response.statusCode().value() == 404) {
                                return new GatewayException(new GatewayError.InvalidRequest(
                                        "model not found in Ollama; run: ollama pull "
                                                + config.resolveModel(request.model().value())));
                            }
                            return new GatewayException(new GatewayError.UpstreamUnavailable(
                                    "ollama returned " + response.statusCode().value()));
                        }))
                .bodyToMono(OllamaChatResponse.class)
                .map(response -> toDomain(
                        response, request, Duration.ofNanos(System.nanoTime() - startNanos)))
                .onErrorMap(this::translate);
    }

    @Override
    public Flux<String> stream(CompletionRequest request) {
        return webClient.post()
                .uri("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toBody(request, true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> new GatewayException(new GatewayError.UpstreamUnavailable(
                                "ollama returned " + response.statusCode().value()))))
                // Newline-delimited JSON, one complete object per line - not SSE. Spring
                // splits on newlines for a Flux<String> over a non-SSE body.
                .bodyToFlux(String.class)
                .mapNotNull(this::extractDelta)
                .onErrorMap(this::translate);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return webClient.get()
                .uri("/api/tags")
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }

    @Override
    public boolean supports(ModelId model) {
        return true;
    }

    private Map<String, Object> toBody(CompletionRequest request, boolean stream) {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role().wireValue(), "content", m.content()))
                .toList();

        return Map.of(
                "model", config.resolveModel(request.model().value()),
                "messages", messages,
                "stream", stream,
                // Ollama nests sampling under "options" and calls the token cap
                // "num_predict" rather than "max_tokens".
                "options", Map.of(
                        "temperature", request.sampling().temperature(),
                        "top_p", request.sampling().topP(),
                        "num_predict", request.sampling().maxTokens()));
    }

    private CompletionResponse toDomain(
            OllamaChatResponse response, CompletionRequest request, Duration latency) {

        String content = response.message() == null ? "" : response.message().content();

        return new CompletionResponse(
                content,
                request.model(),
                new TokenUsage(response.promptEvalCount(), response.evalCount()),
                CacheTier.UPSTREAM,
                ID,
                Instant.now(clock),
                latency);
    }

    private String extractDelta(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            OllamaChatResponse chunk = objectMapper.readValue(line, OllamaChatResponse.class);
            if (chunk.message() == null) {
                return null;
            }
            String delta = chunk.message().content();
            return delta.isEmpty() ? null : delta;
        } catch (JsonProcessingException e) {
            // A partial line, or a shape Ollama changed. Dropping one chunk degrades the
            // response; throwing would abort a stream the user is already reading.
            // Narrowed from `Exception`: catching everything would also swallow an
            // OutOfMemoryError-driven failure as though it were a parse problem.
            log.debug("could not parse ollama chunk, dropping: {}", e.toString());
            return null;
        }
    }

    private Throwable translate(Throwable error) {
        if (error instanceof GatewayException) {
            return error;
        }
        return new GatewayException(new GatewayError.UpstreamUnavailable(
                "could not reach Ollama at " + config.baseUrl()));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OllamaChatResponse(
            OllamaMessage message,
            boolean done,
            @JsonProperty("prompt_eval_count") long promptEvalCount,
            @JsonProperty("eval_count") long evalCount) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record OllamaMessage(String role, String content) {
            OllamaMessage {
                content = content == null ? "" : content;
            }
        }
    }
}
