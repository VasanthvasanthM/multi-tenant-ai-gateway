package io.vasanth.gateway.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
 * One adapter for every vendor that speaks the OpenAI chat-completions wire format.
 *
 * <p>Groq, OpenRouter, Together, Fireworks, DeepInfra and a local llama.cpp server are all
 * reachable by changing {@code base-url}. Writing a class per vendor would mean six
 * near-identical clients drifting apart, six things to mock, and six upgrade paths.
 *
 * <h2>No retries, no timeouts, no circuit breaking here</h2>
 *
 * <p>All of that lives one layer up in Resilience4j. A provider that quietly retried would
 * multiply with the outer retry policy and turn one upstream failure into an amplified
 * storm - the classic way a partial outage becomes a total one. The only deadline set here
 * is the HTTP client's own response timeout, which exists to stop a socket hanging forever
 * and is deliberately longer than the time limiter above it.
 */
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);

    private static final ProviderId ID = ProviderId.of("openai-compatible");

    /** Terminator the OpenAI streaming protocol sends before closing. */
    private static final String STREAM_SENTINEL = "[DONE]";

    private final WebClient webClient;
    private final ProviderProperties.OpenAiCompatible config;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OpenAiCompatibleProvider(
            WebClient webClient,
            ProviderProperties.OpenAiCompatible config,
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
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(toUpstreamBody(request, false))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> translateStatus(response.statusCode(), body)))
                .bodyToMono(UpstreamCompletion.class)
                .map(upstream -> toDomain(
                        upstream, request, Duration.ofNanos(System.nanoTime() - startNanos)))
                .onErrorMap(OpenAiCompatibleProvider::translateTransport);
    }

    @Override
    public Flux<String> stream(CompletionRequest request) {
        return webClient.post()
                .uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(toUpstreamBody(request, true))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                        response.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> translateStatus(response.statusCode(), body)))
                .bodyToFlux(String.class)
                // Spring strips the "data: " prefix but passes the sentinel through as a
                // literal payload. Forwarding it would append "[DONE]" to the user's text.
                .takeUntil(STREAM_SENTINEL::equals)
                .filter(chunk -> !STREAM_SENTINEL.equals(chunk) && !chunk.isBlank())
                // Each chunk is inspected for BOTH a text delta and the terminal usage
                // frame. deferContextual gives access to the caller's usage cell without
                // threading a sink through the registry and the resilience decorator.
                .transformDeferredContextual((chunks, context) -> chunks
                        .doOnNext(chunk -> recordUsageIfPresent(chunk, context))
                        .mapNotNull(this::extractDelta))
                .onErrorMap(OpenAiCompatibleProvider::translateTransport);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return webClient.get()
                .uri("/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + config.apiKey())
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }

    @Override
    public boolean supports(ModelId model) {
        // Any model the vendor accepts. The gateway cannot enumerate a remote catalogue
        // cheaply, and guessing wrong would exclude a model the vendor does serve - so the
        // vendor's own 404 is the authority.
        return true;
    }

    private Map<String, Object> toUpstreamBody(CompletionRequest request, boolean stream) {
        List<Map<String, String>> messages = request.messages().stream()
                .map(m -> Map.of("role", m.role().wireValue(), "content", m.content()))
                .toList();

        Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of(
                "model", config.resolveModel(request.model().value()),
                "messages", messages,
                "temperature", request.sampling().temperature(),
                "top_p", request.sampling().topP(),
                "max_tokens", request.sampling().maxTokens(),
                "stream", stream));

        if (stream) {
            // Asks the vendor for a terminal usage frame. Without it a streamed response
            // reports no token count at all, and the gateway has to estimate from
            // character length - which is good enough to bound a budget and wrong enough
            // that it should not be the default when the real figure is available for
            // the asking. A vendor that ignores this simply sends no usage frame, and the
            // estimate takes over.
            body.put("stream_options", Map.of("include_usage", true));
        }
        return body;
    }

    /**
     * Pulls the token count out of the terminal usage frame, if this chunk is one.
     *
     * <p>The frame has an empty {@code choices} array and a populated {@code usage}, so it
     * carries no text and {@code extractDelta} already returns null for it - the two
     * concerns read the same chunk and do not interfere.
     */
    private void recordUsageIfPresent(String chunk, reactor.util.context.ContextView context) {
        try {
            JsonNode usage = objectMapper.readTree(chunk).path("usage");
            if (usage.isMissingNode() || usage.isNull()) {
                return;
            }
            long total = usage.path("total_tokens").asLong(0L);
            if (total <= 0L) {
                // Some vendors report only the two components.
                total = usage.path("prompt_tokens").asLong(0L)
                        + usage.path("completion_tokens").asLong(0L);
            }
            io.vasanth.gateway.common.llm.StreamUsage.record(context, total);
        } catch (JsonProcessingException e) {
            // Same posture as a malformed delta: losing the exact count costs accuracy in
            // the budget estimate, and throwing would abort a stream the user is reading.
            log.debug("could not parse usage frame, falling back to estimate: {}",
                    e.toString());
        }
    }

    private CompletionResponse toDomain(
            UpstreamCompletion upstream, CompletionRequest request, Duration latency) {

        String content = upstream.choices() == null || upstream.choices().isEmpty()
                ? ""
                : upstream.choices().get(0).message().content();

        // Vendors occasionally omit usage, particularly on streamed or cached responses.
        // Zero is reported rather than estimated: a fabricated token count would flow
        // straight into billing.
        TokenUsage usage = upstream.usage() == null
                ? TokenUsage.NONE
                : new TokenUsage(upstream.usage().promptTokens(), upstream.usage().completionTokens());

        return new CompletionResponse(
                content,
                request.model(),
                usage,
                CacheTier.UPSTREAM,
                ID,
                Instant.now(clock),
                latency);
    }

    /**
     * Extracts the incremental text from one SSE chunk.
     *
     * <p>Parsed with Jackson rather than scanned by hand. The first version walked the
     * string looking for {@code "content"} and decoded escape sequences itself, which
     * PMD flagged at cognitive complexity 16 - and it was wrong in the ways hand-rolled
     * JSON parsing is always wrong: a {@code "content"} substring appearing inside
     * another field's value would match, and the escape handling covered only the
     * sequences I happened to think of.
     *
     * <p>Returns null - filtered out by {@code mapNotNull} - for keep-alive and
     * role-announcement chunks that carry no text, so empty events are not pushed at the
     * client.
     */
    private String extractDelta(String chunk) {
        try {
            JsonNode root = objectMapper.readTree(chunk);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return null;
            }
            String content = choices.get(0).path("delta").path("content").asText("");
            return content.isEmpty() ? null : content;
        } catch (JsonProcessingException e) {
            // One malformed chunk degrades the response by a few tokens; throwing would
            // abort a stream the user is already reading.
            log.debug("could not parse stream chunk, dropping: {}", e.toString());
            return null;
        }
    }

    /**
     * Maps an upstream HTTP status onto the gateway's own taxonomy.
     *
     * <p>The vendor's body is logged but never forwarded: it can name internal
     * infrastructure, and more practically it would confuse a caller who has no account
     * with that vendor.
     */
    private static Throwable translateStatus(HttpStatusCode status, String body) {
        log.warn("upstream returned {}: {}", status.value(),
                body.length() > 300 ? body.substring(0, 300) + "..." : body);

        if (status.value() == 429) {
            // The vendor is rate limiting us, not the caller. Surfaced as unavailable
            // rather than as our own 429 so the client does not conclude it exceeded a
            // quota it never touched - and so the fallback chain engages.
            return new GatewayException(new GatewayError.UpstreamUnavailable(
                    "upstream provider is rate limiting this gateway"));
        }
        if (status.value() == 401 || status.value() == 403) {
            // A misconfigured gateway credential. An operator problem, not a caller one.
            return new GatewayException(new GatewayError.UpstreamUnavailable(
                    "gateway is not authorised with the upstream provider"));
        }
        if (status.value() == 400 || status.value() == 404 || status.value() == 422) {
            // The caller's request really is unacceptable - an unknown model, or
            // parameters out of range. This one is worth passing back as a 400.
            return new GatewayException(new GatewayError.InvalidRequest(
                    "upstream rejected the request (model or parameters may be unsupported)"));
        }
        return new GatewayException(new GatewayError.UpstreamUnavailable(
                "upstream provider returned " + status.value()));
    }

    private static Throwable translateTransport(Throwable error) {
        if (error instanceof GatewayException) {
            return error;
        }
        if (error instanceof WebClientRequestException) {
            // DNS failure, connection refused, TLS failure, socket reset.
            return new GatewayException(new GatewayError.UpstreamUnavailable(
                    "could not reach the upstream provider"));
        }
        log.warn("unexpected upstream failure: {}", error.toString());
        return new GatewayException(
                new GatewayError.UpstreamUnavailable("upstream call failed"));
    }

    // ---------------------------------------------------------------------
    // Upstream wire format. Unknown fields are ignored because vendors add
    // their own extensions freely, and a strict parser would break on a
    // vendor's release note rather than on anything we did.
    // ---------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UpstreamCompletion(List<Choice> choices, Usage usage) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(UpstreamMessage message) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record UpstreamMessage(String role, String content) {

            UpstreamMessage {
                content = content == null ? "" : content;
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Usage(
                @JsonProperty("prompt_tokens") long promptTokens,
                @JsonProperty("completion_tokens") long completionTokens) {
        }
    }
}
