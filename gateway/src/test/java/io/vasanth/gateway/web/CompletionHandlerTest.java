package io.vasanth.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.vasanth.gateway.auth.ApiKeyAuthFilter;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.pipeline.CompletionPipeline;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The completions endpoint, through a real router and a real HTTP exchange.
 *
 * <p>Driven by {@link WebTestClient} bound to the router rather than by calling the handler
 * directly, because everything worth testing here is HTTP-shaped: the response headers, the
 * SSE framing, and the fact that streaming is selected by a body field rather than by an
 * {@code Accept} header.
 *
 * <h2>The headers are a contract, not a convenience</h2>
 *
 * <p>{@code X-Cache-Tier} and {@code X-Provider} are documented in
 * {@code docs/api/openapi.yaml}, and this repository has already shipped a documented header
 * the server never sent ({@code X-Gateway-Served-By}, invented and later removed) while
 * these two real ones went undocumented. A handler with no test is exactly where that
 * happens, so each is asserted against the value the domain object carries rather than
 * against a literal.
 *
 * <p>{@link CompletionPipeline} is stubbed by subclassing rather than mocked: it is a
 * concrete class with a nine-argument constructor, and this test cares only about what the
 * handler does with what the pipeline returns.
 */
class CompletionHandlerTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Records what reached the pipeline, and returns what the test chose. */
    private static final class StubPipeline extends CompletionPipeline {

        private final AtomicReference<CompletionRequest> executed = new AtomicReference<>();
        private final AtomicReference<CompletionRequest> streamed = new AtomicReference<>();

        private Mono<CompletionResponse> response = Mono.empty();
        private Flux<String> deltas = Flux.empty();

        StubPipeline() {
            // Every collaborator is null: none is reachable, because execute() and stream()
            // are both overridden. Constructing a real pipeline here would mean standing up
            // Redis, pgvector, an embedding model and a provider registry to test header
            // emission.
            super(null, null, null, null, null, null, java.util.Optional.empty(), FIXED,
                    CompletionHandlerTest.properties(), new io.micrometer.core.instrument
                            .simple.SimpleMeterRegistry());
        }

        @Override
        public Mono<CompletionResponse> execute(CompletionRequest request) {
            executed.set(request);
            return response;
        }

        @Override
        public Flux<String> stream(CompletionRequest request) {
            streamed.set(request);
            return deltas;
        }
    }

    static io.vasanth.gateway.config.GatewayProperties properties() {
        return new io.vasanth.gateway.config.GatewayProperties(
                new io.vasanth.gateway.config.GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, true),
                new io.vasanth.gateway.config.GatewayProperties.RateLimit(true, 100, 20, true),
                new io.vasanth.gateway.config.GatewayProperties.Limits(
                        32_000, 256, Duration.ofSeconds(30)),
                new io.vasanth.gateway.config.GatewayProperties.Providers("mock", List.of()));
    }

    private StubPipeline pipeline;
    private CompletionHandler handler;
    private WebTestClient client;

    /** Puts an authenticated tenant on the exchange, as ApiKeyAuthFilter would. */
    private static WebFilter authenticatedAs(TenantId tenant) {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ATTRIBUTE, tenant);
            return chain.filter(exchange);
        };
    }

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        pipeline = new StubPipeline();
        handler = new CompletionHandler(pipeline, validator, FIXED);

        RouterFunction<ServerResponse> routes =
                new CompletionRouter().completionRoutes(handler);
        client = WebTestClient.bindToRouterFunction(routes)
                .webFilter(authenticatedAs(ACME))
                .build();
    }

    private static CompletionResponse responseServedBy(CacheTier tier, ProviderId provider) {
        return new CompletionResponse(
                "the answer",
                ModelId.of("gpt-4o-mini"),
                new TokenUsage(11L, 4L),
                tier,
                provider,
                NOW.minusSeconds(30),
                Duration.ofMillis(12));
    }

    private static String body(String extra) {
        return "{\"model\":\"gpt-4o-mini\",\"messages\":"
                + "[{\"role\":\"user\",\"content\":\"hello\"}]" + extra + "}";
    }

    // ------------------------------------------------------------------
    // Buffered responses
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("buffered")
    class Buffered {

        @Test
        @DisplayName("a 200 carries the documented cache-tier and provider headers")
        void headersAreEmitted() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.TIER2_SEMANTIC, ProviderId.CACHE));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body(""))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.APPLICATION_JSON)
                    // Asserted against the enum's own label rather than a literal, so a
                    // rename of the label cannot leave the header and the docs disagreeing
                    // while this test still passes.
                    .expectHeader().valueEquals("X-Cache-Tier", CacheTier.TIER2_SEMANTIC.label())
                    .expectHeader().valueEquals("X-Provider", ProviderId.CACHE.value());
        }

        @Test
        @DisplayName("an upstream answer reports tier upstream, not a cache tier")
        void upstreamHeader() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.UPSTREAM, ProviderId.of("openai")));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body(""))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().valueEquals("X-Cache-Tier", "upstream")
                    .expectHeader().valueEquals("X-Provider", "openai");
        }

        @Test
        @DisplayName("the body carries the OpenAI-shaped fields a client expects")
        void bodyShape() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.TIER1_EXACT, ProviderId.CACHE));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body(""))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.object").isEqualTo("chat.completion")
                    .jsonPath("$.model").isEqualTo("gpt-4o-mini")
                    .jsonPath("$.choices[0].message.content").isEqualTo("the answer")
                    .jsonPath("$.usage.prompt_tokens").isEqualTo(11)
                    .jsonPath("$.usage.completion_tokens").isEqualTo(4)
                    // created comes from the injected clock, so it is assertable at all.
                    .jsonPath("$.created").isEqualTo(NOW.getEpochSecond());
        }
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("streaming")
    class Streaming {

        @Test
        @DisplayName("stream:true in the BODY selects SSE, not the Accept header")
        void bodyFieldSelectsStreaming() {
            pipeline.deltas = Flux.just("Hel", "lo");

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    // Accept says JSON. The OpenAI protocol selects streaming through the
                    // body field, so the handler must ignore this - which is also why the
                    // router accepts both content types on one route.
                    .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body(",\"stream\":true"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);

            assertThat(pipeline.streamed.get()).isNotNull();
            assertThat(pipeline.executed.get()).isNull();
        }

        @Test
        @DisplayName("the stream ends with the [DONE] sentinel clients stop on")
        void doneSentinel() {
            pipeline.deltas = Flux.just("Hel", "lo");

            List<String> events = client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body(",\"stream\":true"))
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class)
                    .getResponseBody()
                    .collectList()
                    .block(Duration.ofSeconds(5));

            // Without the terminator an OpenAI-compatible client waits for a frame that
            // never arrives and the request appears to hang rather than finish.
            assertThat(events).containsExactly("Hel", "lo", "[DONE]");
        }

        @Test
        @DisplayName("the anti-buffering headers are set, or streaming silently stops working")
        void antiBufferingHeaders() {
            pipeline.deltas = Flux.just("x");

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body(",\"stream\":true"))
                    .exchange()
                    .expectStatus().isOk()
                    // Nginx buffers proxied responses by default, which holds the whole
                    // stream until the handler completes - turning streaming into a slow
                    // buffered response with no error anywhere. The symptom is "streaming
                    // is broken in the cluster but fine locally".
                    .expectHeader().valueEquals("X-Accel-Buffering", "no")
                    .expectHeader().valueEquals("Cache-Control", "no-cache");
        }

        @Test
        @DisplayName("an empty stream still terminates with [DONE]")
        void emptyStreamStillTerminates() {
            pipeline.deltas = Flux.empty();

            List<String> events = client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body(",\"stream\":true"))
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(String.class)
                    .getResponseBody()
                    .collectList()
                    .block(Duration.ofSeconds(5));

            assertThat(events).containsExactly("[DONE]");
        }

        @Test
        @DisplayName("stream:false takes the buffered path")
        void explicitFalseIsBuffered() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.UPSTREAM, ProviderId.of("mock")));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body(",\"stream\":false"))
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.APPLICATION_JSON);

            assertThat(pipeline.executed.get()).isNotNull();
            assertThat(pipeline.streamed.get()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // What reaches the pipeline
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("request mapping")
    class Mapping {

        @Test
        @DisplayName("the tenant comes from the exchange, never from the body")
        void tenantIsNotTakenFromTheBody() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.UPSTREAM, ProviderId.of("mock")));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    // A hostile body claiming another tenant. The DTO has no tenant field
                    // at all, which is the actual defence; this pins that the resolved
                    // tenant is what reaches the pipeline. Accepting a client-supplied
                    // tenant id would let any caller read any tenant's cache.
                    .bodyValue("{\"model\":\"gpt-4o-mini\",\"tenantId\":\"globex\","
                            + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")
                    .exchange()
                    .expectStatus().isOk();

            assertThat(pipeline.executed.get().tenantId()).isEqualTo(ACME);
        }

        @Test
        @DisplayName("the Idempotency-Key header reaches the domain request")
        void idempotencyKeyIsForwarded() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.UPSTREAM, ProviderId.of("mock")));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", "abc-123")
                    .bodyValue(body(""))
                    .exchange()
                    .expectStatus().isOk();

            assertThat(pipeline.executed.get().idempotencyKey()).isEqualTo("abc-123");
        }

        @Test
        @DisplayName("an absent Idempotency-Key becomes empty, never null")
        void absentIdempotencyKeyIsEmpty() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.UPSTREAM, ProviderId.of("mock")));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body(""))
                    .exchange()
                    .expectStatus().isOk();

            // CompletionRequest hashes this into the cache key; a null would NPE there.
            assertThat(pipeline.executed.get().idempotencyKey()).isEmpty();
        }

        @Test
        @DisplayName("sampling defaults are applied when the body omits them")
        void samplingDefaults() {
            pipeline.response = Mono.just(
                    responseServedBy(CacheTier.UPSTREAM, ProviderId.of("mock")));

            client.post().uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body(""))
                    .exchange()
                    .expectStatus().isOk();

            // temperature 0 is deliberate: a cache is far more useful when the model is
            // asked to be deterministic, and these defaults are part of the cache key.
            assertThat(pipeline.executed.get().sampling().temperature()).isZero();
            assertThat(pipeline.executed.get().sampling().topP()).isEqualTo(1.0d);
            assertThat(pipeline.executed.get().sampling().maxTokens()).isEqualTo(1024);
        }
    }

    // ------------------------------------------------------------------
    // Rejections
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rejections")
    class Rejections {

        /**
         * Calls the handler directly.
         *
         * <p>{@code WebTestClient.bindToRouterFunction} does not install
         * {@code GatewayErrorWebExceptionHandler}, so a thrown {@code GatewayException}
         * surfaces as a raw error rather than the mapped status. Asserting the error itself
         * is the honest thing to do here; the status mapping has its own test in
         * {@code GatewayErrorWebExceptionHandlerTest}.
         */
        private Mono<ServerResponse> invoke(String json) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/v1/chat/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(json));
            exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ATTRIBUTE, ACME);
            return handler.completions(ServerRequest.create(
                    exchange, HandlerStrategies.withDefaults().messageReaders()));
        }

        private void expectInvalidRequest(String json, String expectedFragment) {
            StepVerifier.create(invoke(json))
                    .verifyErrorSatisfies(thrown -> {
                        assertThat(thrown).isInstanceOf(GatewayException.class);
                        GatewayError error = ((GatewayException) thrown).error();
                        assertThat(error).isInstanceOf(GatewayError.InvalidRequest.class);
                        assertThat(error.httpStatus()).isEqualTo(400);
                        // Resending the same malformed body cannot work.
                        assertThat(error.retryable()).isFalse();
                        assertThat(error.message()).contains(expectedFragment);
                    });
        }

        @Test
        @DisplayName("a zero-length body is refused by the codec, before the handler")
        void zeroLengthBodyIsRejectedByTheCodec() {
            // Not the handler's switchIfEmpty: Jackson cannot read a zero-length body at
            // all, so the codec raises ServerWebInputException first. Still a 400, so the
            // caller is correctly refused - but the handler comment used to claim an empty
            // body "deserialises to null rather than failing", which is not what happens.
            StepVerifier.create(invoke(""))
                    .verifyErrorSatisfies(thrown -> {
                        assertThat(thrown).isInstanceOf(
                                org.springframework.web.server.ServerWebInputException.class);
                        assertThat(((org.springframework.web.server.ServerWebInputException)
                                thrown).getStatusCode().value()).isEqualTo(400);
                    });
        }

        @Test
        @DisplayName("a body that parses to JSON null hits the handler's own guard")
        void jsonNullBodyIsRejected() {
            // THIS is what switchIfEmpty is for: four valid bytes that parse to nothing.
            expectInvalidRequest("null", "request body is required");
        }

        @Test
        @DisplayName("a missing model is rejected")
        void missingModel() {
            expectInvalidRequest(
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "model");
        }

        @Test
        @DisplayName("an empty messages array is rejected")
        void emptyMessages() {
            expectInvalidRequest("{\"model\":\"m\",\"messages\":[]}", "messages");
        }

        @Test
        @DisplayName("a blank message role is rejected by validation, naming the field")
        void blankRole() {
            // Regression test for a real bug: `messages` carried @NotEmpty but not @Valid,
            // so Bean Validation never descended into the list and MessageDto's @NotBlank
            // constraints were dead. A blank role passed validation and then threw
            // IllegalArgumentException("unsupported role ''") from deep in toDomain().
            // The caller still saw a 400 - only because the exception handler of last
            // resort catches IllegalArgumentException - with domain wording instead of a
            // field name. Asserting on "messages[0].role" is what pins the cascade.
            expectInvalidRequest(
                    "{\"model\":\"m\",\"messages\":[{\"role\":\"\",\"content\":\"hi\"}]}",
                    "messages[0].role");
        }

        @Test
        @DisplayName("blank message content is rejected too, by the same cascade")
        void blankContent() {
            expectInvalidRequest(
                    "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"\"}]}",
                    "messages[0].content");
        }

        @Test
        @DisplayName("an out-of-range temperature is rejected")
        void temperatureOutOfRange() {
            expectInvalidRequest(body(",\"temperature\":2.5"), "temperature");
        }

        @Test
        @DisplayName("a max_tokens below 1 is rejected")
        void maxTokensTooSmall() {
            expectInvalidRequest(body(",\"max_tokens\":0"), "maxTokens");
        }

        @Test
        @DisplayName("multiple violations are reported together, in a deterministic order")
        void violationsAreSortedAndCombined() {
            StepVerifier.create(invoke("{\"messages\":[],\"temperature\":9.0}"))
                    .verifyErrorSatisfies(thrown -> {
                        String message = ((GatewayException) thrown).error().message();
                        // All three, so a caller fixes them in one round trip instead of
                        // discovering them one at a time.
                        assertThat(message).contains("messages").contains("model")
                                .contains("temperature");
                        // Sorted. Unsorted, the order varies between runs - which makes the
                        // failure impossible to assert on and noisy in logs.
                        assertThat(message.indexOf("messages"))
                                .isLessThan(message.indexOf("model"));
                        assertThat(message.indexOf("model"))
                                .isLessThan(message.indexOf("temperature"));
                    });
        }

        @Test
        @DisplayName("nothing reaches the pipeline when validation fails")
        void pipelineUntouchedOnRejection() {
            StepVerifier.create(invoke("{\"model\":\"m\",\"messages\":[]}"))
                    .verifyError(GatewayException.class);

            assertThat(pipeline.executed.get()).isNull();
            assertThat(pipeline.streamed.get()).isNull();
        }
    }
}
