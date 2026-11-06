package io.vasanth.gateway.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.ProviderProperties;
import reactor.test.StepVerifier;

/**
 * The Ollama adapter.
 *
 * <p>A separate class from {@link OpenAiCompatibleProvider} because Ollama's wire format
 * differs in three ways that would otherwise have to be branched around: sampling is
 * nested under {@code options}, the token cap is {@code num_predict} rather than
 * {@code max_tokens}, and streaming is newline-delimited JSON rather than SSE. The tests
 * here pin exactly those three differences, since they are what the class exists for.
 */
class OllamaProviderTest {

    private static WireMockServer server;

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);

    private static final CompletionRequest REQUEST = CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("llama3.1"), "why is the sky blue?");

    private OllamaProvider provider;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        provider = providerFor("http://localhost:" + server.port(), Map.of());
    }

    private static OllamaProvider providerFor(String baseUrl, Map<String, String> aliases) {
        ProviderProperties.Ollama config = new ProviderProperties.Ollama(
                true, baseUrl, Duration.ofSeconds(5), aliases);
        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        return new OllamaProvider(webClient, config, new ObjectMapper(), FIXED);
    }

    private static void stubChat(String body) {
        server.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    // ------------------------------------------------------------------
    // Happy path and the Ollama-specific wire format
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chat response maps to content, usage, tier and provider id")
    void completeMapsTheResponse() {
        stubChat("""
                {
                  "model": "llama3.1",
                  "message": {"role": "assistant", "content": "Rayleigh scattering."},
                  "done": true,
                  "prompt_eval_count": 11,
                  "eval_count": 4
                }
                """);

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("Rayleigh scattering.");
        // Ollama names these prompt_eval_count / eval_count; they map onto the same
        // TokenUsage the ledger bills from.
        assertThat(response.usage().promptTokens()).isEqualTo(11);
        assertThat(response.usage().completionTokens()).isEqualTo(4);
        assertThat(response.servedBy()).isEqualTo(CacheTier.UPSTREAM);
        assertThat(response.provider().value()).isEqualTo("ollama");
        assertThat(response.model()).isEqualTo(ModelId.of("llama3.1"));
    }

    @Test
    @DisplayName("sampling is nested under options, and the cap is num_predict")
    void samplingIsNestedUnderOptions() {
        stubChat("""
                {"message": {"role": "assistant", "content": "ok"}, "done": true}
                """);

        provider.complete(REQUEST).block();

        // The whole reason this class is not the OpenAI adapter with a different base
        // URL. Sending max_tokens at the top level would be silently IGNORED by Ollama,
        // so the token cap would not apply and nothing would report that.
        server.verify(postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.options.num_predict"))
                .withRequestBody(matchingJsonPath("$.options.temperature"))
                .withRequestBody(matchingJsonPath("$.options.top_p"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false"))));
    }

    @Test
    @DisplayName("max_tokens is never sent at the top level, where Ollama ignores it")
    void doesNotSendOpenAiStyleFields() {
        stubChat("""
                {"message": {"role": "assistant", "content": "ok"}, "done": true}
                """);

        provider.complete(REQUEST).block();

        String body = server.getAllServeEvents().get(0).getRequest().getBodyAsString();
        assertThat(body).doesNotContain("max_tokens");
        assertThat(body).contains("num_predict");
    }

    @Test
    @DisplayName("a model alias is resolved before the call")
    void resolvesModelAlias() {
        provider = providerFor("http://localhost:" + server.port(),
                Map.of("llama3.1", "llama3.1:8b-instruct-q4_0"));
        stubChat("""
                {"message": {"role": "assistant", "content": "ok"}, "done": true}
                """);

        CompletionResponse response = provider.complete(REQUEST).block();

        server.verify(postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath(
                        "$.model", equalTo("llama3.1:8b-instruct-q4_0"))));
        assertThat(response).isNotNull();
        assertThat(response.model()).isEqualTo(ModelId.of("llama3.1"));
    }

    @Test
    @DisplayName("a missing message block yields empty content rather than an NPE")
    void missingMessageYieldsEmptyContent() {
        stubChat("{\"done\": true, \"prompt_eval_count\": 3, \"eval_count\": 0}");

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEmpty();
    }

    @Test
    @DisplayName("a null content field is normalised to empty by the record")
    void nullContentIsNormalised() {
        stubChat("{\"message\": {\"role\": \"assistant\", \"content\": null}, \"done\": true}");

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a 404 says which model to pull, because that is the usual cause")
    void notFoundNamesTheFix() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(404)
                .withBody("{\"error\":\"model 'llama3.1' not found\"}")));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.InvalidRequest.class);
                    // A generic "upstream unavailable" would send an operator looking at
                    // the network for what is almost always an un-pulled model.
                    assertThat(gatewayError.message())
                            .contains("ollama pull")
                            .contains("llama3.1");
                });
    }

    @Test
    @DisplayName("a 404 names the RESOLVED model, which is the one to pull")
    void notFoundNamesTheResolvedModel() {
        provider = providerFor("http://localhost:" + server.port(),
                Map.of("llama3.1", "llama3.1:8b-instruct-q4_0"));
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(404)));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> assertThat(
                        ((GatewayException) error).error().message())
                        // Telling an operator to pull the alias would not work.
                        .contains("llama3.1:8b-instruct-q4_0"));
    }

    @Test
    @DisplayName("other error statuses become UpstreamUnavailable")
    void otherErrorsBecomeUnavailable() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500)));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.UpstreamUnavailable.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(503);
                    assertThat(gatewayError.retryable()).isTrue();
                });
    }

    @Test
    @DisplayName("an unreachable Ollama names the address it tried")
    void unreachableNamesTheAddress() {
        OllamaProvider unreachable = providerFor("http://localhost:1", Map.of());

        StepVerifier.create(unreachable.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    assertThat(((GatewayException) error).error())
                            .isInstanceOf(GatewayError.UpstreamUnavailable.class);
                    // Ollama is usually local, so "which address" is the whole diagnosis.
                    assertThat(((GatewayException) error).error().message())
                            .contains("localhost:1");
                });
    }

    // ------------------------------------------------------------------
    // Streaming - newline-delimited JSON, not SSE
    // ------------------------------------------------------------------

    private static void stubStream(String ndjson) {
        server.stubFor(post(urlPathEqualTo("/api/chat"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/x-ndjson")
                        .withBody(ndjson)));
    }

    @Test
    @DisplayName("a stream yields the incremental content of each NDJSON line")
    void streamYieldsDeltas() {
        stubStream("""
                {"message":{"role":"assistant","content":"Rayleigh "},"done":false}
                {"message":{"role":"assistant","content":"scattering."},"done":false}
                {"message":{"role":"assistant","content":""},"done":true,"eval_count":4}
                """);

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("Rayleigh ")
                .expectNext("scattering.")
                // The final done-marker carries empty content and must not be emitted.
                .verifyComplete();
    }

    @Test
    @DisplayName("stream=true is sent, or Ollama returns one buffered object")
    void streamFlagIsSent() {
        stubStream("{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"done\":true}\n");

        provider.stream(REQUEST).blockLast();

        server.verify(postRequestedFor(urlPathEqualTo("/api/chat"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    @DisplayName("a line with no message block produces no event")
    void linesWithoutMessageAreDropped() {
        stubStream("""
                {"done":false}
                {"message":{"role":"assistant","content":"real"},"done":false}
                {"done":true,"eval_count":1}
                """);

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("real")
                .verifyComplete();
    }

    @Test
    @DisplayName("a malformed line is dropped without aborting the stream")
    void malformedLineDoesNotAbort() {
        stubStream("""
                {"message":{"role":"assistant","content":"first "},"done":false}
                {not json at all
                {"message":{"role":"assistant","content":"third"},"done":true}
                """);

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("first ")
                .expectNext("third")
                .verifyComplete();
    }

    @Test
    @DisplayName("a stream error status maps to the taxonomy")
    void streamErrorIsTranslated() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500)));

        StepVerifier.create(provider.stream(REQUEST))
                .verifyErrorSatisfies(error -> assertThat(((GatewayException) error).error())
                        .isInstanceOf(GatewayError.UpstreamUnavailable.class));
    }

    // ------------------------------------------------------------------
    // Health and capability
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isHealthy probes /api/tags")
    void healthyOnSuccess() {
        server.stubFor(get(urlPathEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(200).withBody("{\"models\":[]}")));

        assertThat(provider.isHealthy().block()).isTrue();
    }

    @Test
    @DisplayName("isHealthy is false rather than erroring when Ollama is absent")
    void unhealthyWhenAbsent() {
        // The common case: Ollama is off by default, so the probe must fail quietly
        // rather than making the registry treat it as a gateway fault.
        assertThat(providerFor("http://localhost:1", Map.of()).isHealthy().block()).isFalse();
    }

    @Test
    @DisplayName("isHealthy is false on an error status")
    void unhealthyOnErrorStatus() {
        server.stubFor(get(urlPathEqualTo("/api/tags"))
                .willReturn(aResponse().withStatus(500)));

        assertThat(provider.isHealthy().block()).isFalse();
    }

    @Test
    @DisplayName("every model is supported; Ollama's own 404 is the authority")
    void supportsEveryModel() {
        assertThat(provider.supports(ModelId.of("llama3.1"))).isTrue();
        assertThat(provider.supports(ModelId.of("not-pulled"))).isTrue();
    }

    @Test
    @DisplayName("the provider id is stable")
    void providerIdIsStable() {
        assertThat(provider.id().value()).isEqualTo("ollama");
    }

    @Test
    @DisplayName("a blank base URL falls back to the Ollama default port")
    void blankBaseUrlFallsBackToDefault() {
        ProviderProperties.Ollama config =
                new ProviderProperties.Ollama(false, "  ", Duration.ofSeconds(5), null);

        // Normalised in the record, so a half-configured deployment points at the
        // conventional local address rather than at the empty string.
        assertThat(config.baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(config.modelAliases()).isEmpty();
    }
}
