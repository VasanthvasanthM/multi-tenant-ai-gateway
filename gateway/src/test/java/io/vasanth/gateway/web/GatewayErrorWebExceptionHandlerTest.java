package io.vasanth.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import reactor.test.StepVerifier;

/**
 * The single place every failure becomes an HTTP response.
 *
 * <p>Two things are being protected here. The first is the contract in
 * {@code docs/api/openapi.yaml}: a client written against that document must get the
 * status, code and {@code retryable} flag it promises. The second is that internal
 * detail never escapes - an unexpected exception must become a bare 500, not a stack
 * trace or a message naming internal state.
 */
class GatewayErrorWebExceptionHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SimpleMeterRegistry meters;
    private GatewayErrorWebExceptionHandler handler;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        handler = new GatewayErrorWebExceptionHandler(mapper, meters);
    }

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions"));
    }

    private JsonNode handleAndReadBody(MockServerWebExchange exchange, Throwable error) {
        StepVerifier.create(handler.handle(exchange, error)).verifyComplete();
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isNotNull();
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("response body was not JSON: " + body, e);
        }
    }

    // ------------------------------------------------------------------
    // The documented contract
    // ------------------------------------------------------------------

    private static Stream<Arguments> gatewayErrors() {
        return Stream.of(
                Arguments.of(new GatewayError.Unauthenticated("no key"),
                        401, "unauthenticated", "authentication_error", false),
                Arguments.of(new GatewayError.Forbidden("revoked"),
                        403, "forbidden", "authentication_error", false),
                Arguments.of(new GatewayError.InvalidRequest("messages must not be empty"),
                        400, "invalid_request", "invalid_request_error", false),
                Arguments.of(new GatewayError.PayloadTooLarge(48_213, 32_000),
                        413, "payload_too_large", "invalid_request_error", false),
                Arguments.of(new GatewayError.RateLimited(Duration.ofSeconds(3)),
                        429, "rate_limited", "rate_limit_error", true),
                Arguments.of(new GatewayError.BudgetExhausted(1_000_000L, 1_050_000L),
                        402, "budget_exhausted", "invalid_request_error", false),
                Arguments.of(new GatewayError.UpstreamUnavailable("all providers down"),
                        503, "upstream_unavailable", "server_error", true),
                Arguments.of(new GatewayError.UpstreamTimeout(Duration.ofSeconds(30)),
                        504, "upstream_timeout", "server_error", true),
                Arguments.of(new GatewayError.CircuitOpen("openai"),
                        503, "circuit_open", "server_error", true),
                Arguments.of(new GatewayError.Overloaded(64),
                        503, "overloaded", "server_error", true),
                Arguments.of(GatewayError.Internal.generic(),
                        500, "internal_error", "server_error", true));
    }

    @ParameterizedTest(name = "{1} {2}")
    @MethodSource("gatewayErrors")
    @DisplayName("every error maps to its documented status, code, type and retryable flag")
    void errorsMapToTheDocumentedContract(
            GatewayError error, int status, String code, String type, boolean retryable) {

        MockServerWebExchange exchange = exchange();
        JsonNode body = handleAndReadBody(exchange, new GatewayException(error));

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.valueOf(status));
        assertThat(exchange.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);

        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("error").path("type").asText()).isEqualTo(type);
        // The flag exists so a client does not have to infer retryability from the
        // status, where the inference is wrong in both directions: 402 is a 4xx that
        // never succeeds on retry, and 503 is a 5xx that usually does.
        assertThat(body.path("error").path("retryable").asBoolean()).isEqualTo(retryable);
        assertThat(body.path("error").path("message").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("402 and 429 are distinguishable, and only one is retryable")
    void budgetAndRateLimitAreDistinct() {
        JsonNode budget = handleAndReadBody(exchange(),
                new GatewayException(new GatewayError.BudgetExhausted(100L, 150L)));
        JsonNode rate = handleAndReadBody(exchange(),
                new GatewayException(new GatewayError.RateLimited(Duration.ofSeconds(1))));

        // Both mean "not now". Only one is worth retrying, and a client that cannot
        // tell them apart retries a budget failure forever.
        assertThat(budget.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(rate.path("error").path("retryable").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------
    // Retry-After
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a 429 carries Retry-After in seconds")
    void rateLimitedCarriesRetryAfter() {
        MockServerWebExchange exchange = exchange();

        handleAndReadBody(exchange,
                new GatewayException(new GatewayError.RateLimited(Duration.ofSeconds(3))));

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("a sub-second wait rounds UP, so a compliant client is not early")
    void retryAfterRoundsUp() {
        MockServerWebExchange exchange = exchange();

        handleAndReadBody(exchange,
                new GatewayException(new GatewayError.RateLimited(Duration.ofMillis(1_400))));

        // Truncating to 1 would have the client return before the bucket refilled,
        // get another 429, and conclude the gateway is lying to it.
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("only a 429 gets Retry-After")
    void otherErrorsHaveNoRetryAfter() {
        MockServerWebExchange exchange = exchange();

        handleAndReadBody(exchange,
                new GatewayException(new GatewayError.UpstreamUnavailable("down")));

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isNull();
    }

    // ------------------------------------------------------------------
    // Non-gateway exceptions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a framework 4xx becomes InvalidRequest, echoing its own reason")
    void frameworkClientErrorBecomesInvalidRequest() {
        JsonNode body = handleAndReadBody(exchange(), new ResponseStatusException(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type 'text/plain' not supported"));

        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_request");
        // Framework-generated, so it describes the request rather than internal state
        // and is safe to pass back.
        assertThat(body.path("error").path("message").asText())
                .contains("text/plain");
    }

    @Test
    @DisplayName("a framework 4xx with no reason still produces a usable message")
    void frameworkClientErrorWithoutReason() {
        JsonNode body = handleAndReadBody(exchange(),
                new ResponseStatusException(HttpStatus.BAD_REQUEST));

        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_request");
        assertThat(body.path("error").path("message").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("a framework 5xx becomes a generic 500, not an echoed reason")
    void frameworkServerErrorBecomesGeneric() {
        JsonNode body = handleAndReadBody(exchange(), new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR, "pool exhausted on shard db-3"));

        assertThat(body.path("error").path("code").asText()).isEqualTo("internal_error");
        // A 5xx reason describes OUR internals, so unlike the 4xx case it must not be
        // echoed.
        assertThat(body.path("error").path("message").asText())
                .doesNotContain("shard", "pool");
    }

    @Test
    @DisplayName("an IllegalArgumentException from a value type becomes a 400")
    void illegalArgumentBecomesInvalidRequest() {
        JsonNode body = handleAndReadBody(exchange(),
                new IllegalArgumentException("unsupported role 'wizard'"));

        // Thrown by the domain value types for an invalid tenant id, model id or role.
        // Those messages describe the caller's input.
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_request");
        assertThat(body.path("error").path("message").asText()).contains("wizard");
    }

    @Test
    @DisplayName("an unexpected exception becomes a bare 500 with nothing leaked")
    void unexpectedExceptionIsOpaque() {
        MockServerWebExchange exchange = exchange();
        JsonNode body = handleAndReadBody(exchange, new IllegalStateException(
                "jdbc:postgresql://db-shard-3.internal:5432 refused connection for user aigw"));

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body.path("error").path("code").asText()).isEqualTo("internal_error");
        // The whole point: no hostnames, no credentials, no stack trace.
        String message = body.path("error").path("message").asText();
        assertThat(message).isEqualTo("internal error");
        assertThat(message).doesNotContain("postgresql", "db-shard-3", "aigw", "5432");
    }

    // ------------------------------------------------------------------
    // A committed response
    // ------------------------------------------------------------------

    @Test
    @DisplayName("once the response is committed, the error propagates instead")
    void committedResponsePropagates() throws Exception {
        MockServerWebExchange exchange = exchange();
        // Simulates a failure partway through an SSE stream: the headers are already on
        // the wire, so the status cannot be rewritten.
        exchange.getResponse().setComplete().block();

        GatewayException error =
                new GatewayException(new GatewayError.UpstreamTimeout(Duration.ofSeconds(30)));

        // Propagating closes the connection and the client sees a truncated stream,
        // which is the only honest outcome - writing a JSON error body into the middle
        // of an event stream would produce a response that parses as neither.
        StepVerifier.create(handler.handle(exchange, error))
                .verifyErrorSatisfies(thrown -> assertThat(thrown).isSameAs(error));
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    @Test
    @DisplayName("each error increments gateway.errors tagged with code and status")
    void errorsAreCounted() {
        handleAndReadBody(exchange(),
                new GatewayException(new GatewayError.CircuitOpen("openai")));

        assertThat(meters.find("gateway.errors")
                .tag("code", "circuit_open")
                .tag("status", "503")
                .counter())
                .isNotNull()
                .extracting(io.micrometer.core.instrument.Counter::count)
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the same code is counted on one series, not a new one per request")
    void repeatedErrorsShareASeries() {
        for (int i = 0; i < 3; i++) {
            handleAndReadBody(exchange(),
                    new GatewayException(new GatewayError.RateLimited(Duration.ofSeconds(1))));
        }

        // Tags are code and status only - never the tenant or the prompt. A per-tenant
        // tag would make the series count grow with the customer list, which is how a
        // Prometheus instance is killed by cardinality.
        assertThat(meters.find("gateway.errors").tag("code", "rate_limited")
                .counter().count()).isEqualTo(3.0d);
        assertThat(meters.find("gateway.errors").counters()).hasSize(1);
    }

    @Test
    @DisplayName("the written body is valid JSON of the documented shape")
    void bodyIsWellFormed() {
        MockServerWebExchange exchange = exchange();
        handleAndReadBody(exchange,
                new GatewayException(new GatewayError.InvalidRequest("bad")));

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isNotNull();
        assertThat(body.getBytes(StandardCharsets.UTF_8).length).isPositive();
        // One top-level "error" object, matching ErrorResponse in the OpenAPI spec.
        JsonNode root = handleAndReadBody(exchange(),
                new GatewayException(new GatewayError.InvalidRequest("bad")));
        assertThat(root.fieldNames()).toIterable().containsExactly("error");
    }

    @Test
    @DisplayName("a no-message IllegalArgumentException still yields a documented 400")
    void nullMessageIsHandled() {
        // Regression test. getMessage() is NULL here, and InvalidRequest requires a
        // non-null message - so this threw an NPE from inside the exception handler,
        // which is the one place an exception has nowhere left to go. The client got an
        // unstructured framework error rather than the documented contract.
        MockServerWebExchange exchange = exchange();
        JsonNode body = handleAndReadBody(exchange, new IllegalArgumentException());

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_request");
        assertThat(body.path("error").path("message").asText()).isNotEmpty();
    }

    @Test
    @DisplayName("a blank-message IllegalArgumentException is handled the same way")
    void blankMessageIsHandled() {
        JsonNode body = handleAndReadBody(exchange(), new IllegalArgumentException("   "));

        assertThat(body.path("error").path("code").asText()).isEqualTo("invalid_request");
        // Not a blank message either - an empty string in the body is as useless to a
        // caller as a missing field.
        assertThat(body.path("error").path("message").asText()).isNotBlank();
    }
}
