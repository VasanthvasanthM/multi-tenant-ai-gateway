package io.vasanth.gateway.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * The OpenAI-compatible adapter, against a real HTTP server.
 *
 * <p>WireMock rather than a mocked {@code WebClient}. A mocked WebClient asserts that
 * the code calls the methods the test expects, which is a restatement of the
 * implementation; the interesting behaviour here is all in what happens to real bytes
 * and real status codes - SSE framing, the {@code [DONE]} sentinel, a malformed chunk
 * mid-stream, and the status-to-taxonomy mapping.
 *
 * <p>The status mapping is the part worth testing hardest, because every entry in it is
 * a deliberate choice that a reasonable person would get wrong: a 429 from the vendor
 * must NOT become a 429 to the caller.
 */
class OpenAiCompatibleProviderTest {

    private static WireMockServer server;

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);

    private static final CompletionRequest REQUEST = CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("gpt-4o-mini"), "why is the sky blue?");

    private OpenAiCompatibleProvider provider;

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

    private static OpenAiCompatibleProvider providerFor(String baseUrl, Map<String, String> aliases) {
        ProviderProperties.OpenAiCompatible config = new ProviderProperties.OpenAiCompatible(
                true, baseUrl, "sk-test-key",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 8, aliases);

        WebClient webClient = WebClient.builder().baseUrl(baseUrl).build();
        return new OpenAiCompatibleProvider(webClient, config, new ObjectMapper(), FIXED);
    }

    private static void stubCompletion(String body) {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a successful completion carries content, usage, tier and provider id")
    void completeMapsTheResponse() {
        stubCompletion("""
                {
                  "id": "chatcmpl-1",
                  "model": "gpt-4o-mini",
                  "choices": [
                    {"index": 0, "message": {"role": "assistant", "content": "Rayleigh scattering."}}
                  ],
                  "usage": {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}
                }
                """);

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("Rayleigh scattering.");
        assertThat(response.usage().promptTokens()).isEqualTo(7);
        assertThat(response.usage().completionTokens()).isEqualTo(3);
        // UPSTREAM, not a cache tier - this response was generated, not served.
        assertThat(response.servedBy()).isEqualTo(CacheTier.UPSTREAM);
        assertThat(response.provider().value()).isEqualTo("openai-compatible");
        // The model echoed back is the one the CALLER asked for, not the alias the
        // vendor was given, so a vendor switch is invisible to clients.
        assertThat(response.model()).isEqualTo(ModelId.of("gpt-4o-mini"));
        assertThat(response.latency()).isGreaterThan(Duration.ZERO);
    }

    @Test
    @DisplayName("the outgoing body carries the sampling parameters and stream=false")
    void sendsSamplingParameters() {
        stubCompletion("""
                {"choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}}]}
                """);

        provider.complete(REQUEST).block();

        server.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.stream", equalTo("false")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.messages[0].role", equalTo("user")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.messages[0].content",
                                equalTo("why is the sky blue?"))));
    }

    @Test
    @DisplayName("a model alias is resolved before the call, invisibly to the caller")
    void resolvesModelAlias() {
        provider = providerFor("http://localhost:" + server.port(),
                Map.of("gpt-4o-mini", "llama-3.1-8b-instant"));
        stubCompletion("""
                {"choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}}]}
                """);

        CompletionResponse response = provider.complete(REQUEST).block();

        server.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.model", equalTo("llama-3.1-8b-instant"))));
        // The caller still sees their own model id.
        assertThat(response).isNotNull();
        assertThat(response.model()).isEqualTo(ModelId.of("gpt-4o-mini"));
    }

    // ------------------------------------------------------------------
    // Responses that are valid but incomplete
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing usage block reports ZERO tokens rather than an estimate")
    void missingUsageIsNotFabricated() {
        stubCompletion("""
                {"choices": [{"index": 0, "message": {"role": "assistant", "content": "no usage"}}]}
                """);

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        // This matters more than it looks: a fabricated token count would flow straight
        // into the usage ledger and onto an invoice.
        assertThat(response.usage().promptTokens()).isZero();
        assertThat(response.usage().completionTokens()).isZero();
        assertThat(response.usage().total()).isZero();
    }

    @Test
    @DisplayName("an empty choices array yields empty content rather than an error")
    void emptyChoicesYieldsEmptyContent() {
        stubCompletion("{\"choices\": [], \"usage\": {\"prompt_tokens\": 1, \"completion_tokens\": 0}}");

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEmpty();
    }

    @Test
    @DisplayName("unknown vendor fields are ignored, not rejected")
    void toleratesUnknownFields() {
        stubCompletion("""
                {
                  "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"},
                               "logprobs": null, "vendor_extension": {"anything": [1,2,3]}}],
                  "x_groq": {"id": "req_123"},
                  "system_fingerprint": "fp_abc"
                }
                """);

        CompletionResponse response = provider.complete(REQUEST).block();

        assertThat(response).isNotNull();
        assertThat(response.content()).isEqualTo("ok");
    }

    // ------------------------------------------------------------------
    // Status mapping - every row here is a deliberate decision
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a 429 from the vendor becomes UpstreamUnavailable, NOT RateLimited")
    void vendorRateLimitIsNotTheCallersRateLimit() {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(429).withBody("{\"error\":\"slow down\"}")));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    GatewayError gatewayError = ((GatewayException) error).error();
                    // The caller never touched a quota of their own. Reporting a 429
                    // would tell them to back off when the correct action is for the
                    // gateway to try another provider - which only happens because this
                    // maps to a 503.
                    assertThat(gatewayError).isInstanceOf(GatewayError.UpstreamUnavailable.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(503);
                    assertThat(gatewayError.retryable()).isTrue();
                });
    }

    @ParameterizedTest(name = "upstream {0} -> {1}")
    @CsvSource({
            "401, 503",   // our credential is wrong: an operator problem, not a caller one
            "403, 503",
            "500, 503",
            "502, 503",
            "503, 503",
    })
    @DisplayName("operator and vendor faults surface as 503, so the fallback chain engages")
    void operatorFaultsBecomeUnavailable(int upstreamStatus, int expectedStatus) {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(upstreamStatus)));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> assertThat(
                        ((GatewayException) error).error().httpStatus()).isEqualTo(expectedStatus));
    }

    @ParameterizedTest(name = "upstream {0} -> 400 InvalidRequest")
    @CsvSource({"400", "404", "422"})
    @DisplayName("a genuinely unacceptable request is passed back as a 400")
    void callerFaultsBecomeInvalidRequest(int upstreamStatus) {
        server.stubFor(post(anyUrl()).willReturn(aResponse()
                .withStatus(upstreamStatus).withBody("unknown model")));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.InvalidRequest.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(400);
                    assertThat(gatewayError.retryable()).isFalse();
                });
    }

    @Test
    @DisplayName("the vendor's error body is never forwarded to the caller")
    void vendorBodyIsNotLeaked() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500).withBody(
                "internal-host-7.vendor.local: postgres://user:pw@db-shard-3 timed out")));

        StepVerifier.create(provider.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    String message = ((GatewayException) error).error().message();
                    // It can name internal infrastructure, and it would confuse a caller
                    // who has no account with that vendor.
                    assertThat(message).doesNotContain("vendor.local", "postgres", "db-shard");
                    assertThat(message).contains("500");
                });
    }

    @Test
    @DisplayName("an unreachable host becomes UpstreamUnavailable, not a raw transport error")
    void unreachableHostIsTranslated() {
        // Port 1 is privileged and nothing listens there, so the connection is refused
        // immediately rather than after a timeout.
        OpenAiCompatibleProvider unreachable = providerFor("http://localhost:1", Map.of());

        StepVerifier.create(unreachable.complete(REQUEST))
                .verifyErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    assertThat(((GatewayException) error).error())
                            .isInstanceOf(GatewayError.UpstreamUnavailable.class);
                });
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    private static void stubStream(String sseBody) {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody(sseBody)));
    }

    @Test
    @DisplayName("a stream yields only the text deltas")
    void streamYieldsDeltas() {
        stubStream("""
                data: {"choices":[{"delta":{"content":"Rayleigh "}}]}

                data: {"choices":[{"delta":{"content":"scattering."}}]}

                data: [DONE]

                """);

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("Rayleigh ")
                .expectNext("scattering.")
                .verifyComplete();
    }

    @Test
    @DisplayName("the [DONE] sentinel is never emitted as content")
    void sentinelIsNotEmitted() {
        stubStream("""
                data: {"choices":[{"delta":{"content":"hello"}}]}

                data: [DONE]

                """);

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("hello")
                // Forwarding the sentinel would append the literal text "[DONE]" to
                // whatever the user was reading.
                .verifyComplete();
    }

    @Test
    @DisplayName("role announcements and keep-alives produce no events")
    void emptyDeltasAreDropped() {
        stubStream("""
                data: {"choices":[{"delta":{"role":"assistant"}}]}

                data: {"choices":[{"delta":{}}]}

                data: {"choices":[]}

                data: {"choices":[{"delta":{"content":"actual text"}}]}

                data: [DONE]

                """);

        StepVerifier.create(provider.stream(REQUEST))
                // Pushing empty events at the client would make a streaming UI render
                // nothing repeatedly.
                .expectNext("actual text")
                .verifyComplete();
    }

    @Test
    @DisplayName("one malformed chunk is dropped without aborting the stream")
    void malformedChunkDoesNotAbortTheStream() {
        stubStream("""
                data: {"choices":[{"delta":{"content":"first "}}]}

                data: {this is not json

                data: {"choices":[{"delta":{"content":"third"}}]}

                data: [DONE]

                """);

        StepVerifier.create(provider.stream(REQUEST))
                // Throwing would abort a response the user is already reading, to save a
                // few tokens of a partial answer.
                .expectNext("first ")
                .expectNext("third")
                .verifyComplete();
    }

    @Test
    @DisplayName("a content substring inside another field is not mistaken for a delta")
    void doesNotMatchContentSubstringElsewhere() {
        // The hand-rolled parser this replaced scanned for the literal "content" and
        // would have matched inside refusal, emitting the wrong text.
        stubStream("""
                data: {"choices":[{"delta":{"refusal":"the content policy applies"}}]}

                data: {"choices":[{"delta":{"content":"real"}}]}

                data: [DONE]

                """);

        StepVerifier.create(provider.stream(REQUEST))
                .expectNext("real")
                .verifyComplete();
    }

    @Test
    @DisplayName("a stream that errors before any data still maps to the taxonomy")
    void streamStatusErrorIsTranslated() {
        server.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(429)));

        StepVerifier.create(provider.stream(REQUEST))
                .verifyErrorSatisfies(error -> assertThat(((GatewayException) error).error())
                        .isInstanceOf(GatewayError.UpstreamUnavailable.class));
    }

    // ------------------------------------------------------------------
    // Health and capability
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isHealthy is true when /v1/models answers 2xx")
    void healthyOnSuccess() {
        server.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        assertThat(provider.isHealthy().block()).isTrue();
        server.verify(getRequestedFor(urlPathEqualTo("/v1/models"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key")));
    }

    @Test
    @DisplayName("isHealthy is false rather than erroring when the vendor is down")
    void unhealthyOnError() {
        server.stubFor(get(urlPathEqualTo("/v1/models"))
                .willReturn(aResponse().withStatus(500)));

        // false, not an error: a health probe that throws makes the registry treat an
        // unhealthy provider the same as a broken gateway.
        assertThat(provider.isHealthy().block()).isFalse();
    }

    @Test
    @DisplayName("isHealthy is false when the host cannot be reached at all")
    void unhealthyWhenUnreachable() {
        assertThat(providerFor("http://localhost:1", Map.of()).isHealthy().block()).isFalse();
    }

    @Test
    @DisplayName("every model is supported - the vendor's own 404 is the authority")
    void supportsEveryModel() {
        // The gateway cannot enumerate a remote catalogue cheaply, and guessing wrong
        // would refuse a model the vendor does serve.
        assertThat(provider.supports(ModelId.of("gpt-4o-mini"))).isTrue();
        assertThat(provider.supports(ModelId.of("something-invented"))).isTrue();
    }

    @Test
    @DisplayName("the provider id is stable")
    void providerIdIsStable() {
        // It is persisted on every cached entry and every usage row, so changing it
        // would orphan history.
        assertThat(provider.id().value()).isEqualTo("openai-compatible");
    }
}
