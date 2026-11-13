package io.vasanth.gateway.provider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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

import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.ProviderProperties;

/**
 * How an upstream HTTP status becomes one of our errors.
 *
 * <h2>Why this mapping deserves its own test</h2>
 *
 * <p>A mutation run left four survivors inside {@code translateStatus}, two of them
 * {@code RemoveConditional} - meaning a whole status branch could be deleted and the
 * suite stayed green. The existing provider tests cover the happy path and transport
 * failures thoroughly; what none of them pinned was WHICH error each status produces.
 *
 * <p>That mapping is not cosmetic, because two things downstream read it:
 *
 * <ul>
 *   <li>{@code GatewayError.retryable()} and the resilience decorator decide whether to
 *       try the next provider in the fallback chain. Classify a 400 as
 *       {@code UpstreamUnavailable} and the gateway retries a request that will be
 *       rejected identically every time, burning the fallback budget on it.
 *   <li>The caller sees the mapped status. Classify a 429 FROM the vendor as our own
 *       rate-limit error and the client concludes it exceeded a quota it never touched.
 *       That one is called out in the method's own comment, and nothing enforced it.
 * </ul>
 *
 * <p>Each case below therefore asserts the error TYPE, not merely that something threw.
 */
class OpenAiStatusTranslationTest {

    private static WireMockServer server;

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

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
        String baseUrl = "http://localhost:" + server.port();
        ProviderProperties.OpenAiCompatible config = new ProviderProperties.OpenAiCompatible(
                true, baseUrl, "sk-test-key",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 8, Map.of());
        provider = new OpenAiCompatibleProvider(
                WebClient.builder().baseUrl(baseUrl).build(), config, new ObjectMapper(), FIXED);
    }

    private void stubStatus(int status, String body) {
        server.stubFor(post(urlPathEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(status).withBody(body)));
    }

    /** Invokes complete() and returns the GatewayError the provider mapped the status to. */
    private GatewayError errorFor(int status, String body) {
        stubStatus(status, body);

        Throwable thrown = org.assertj.core.api.Assertions
                .catchThrowable(() -> provider.complete(REQUEST).block());

        assertThat(thrown).isNotNull();
        GatewayException gatewayException = (GatewayException) findGatewayException(thrown);
        assertThat(gatewayException)
                .as("status %d should map to a GatewayException, got %s", status, thrown)
                .isNotNull();
        return gatewayException.error();
    }

    private static Throwable findGatewayException(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof GatewayException) {
                return t;
            }
        }
        return null;
    }

    @Test
    @DisplayName("429 from the vendor is UpstreamUnavailable, NOT our own rate-limit error")
    void vendorRateLimit() {
        GatewayError error = errorFor(429, "{\"error\":{\"message\":\"slow down\"}}");

        // The distinction the method comment insists on: the VENDOR is throttling this
        // gateway, and the caller has not exceeded anything. Reporting RateLimited here
        // would tell a tenant it hit a quota it never touched, and would also stop the
        // fallback chain from engaging - UpstreamUnavailable is what makes it try the
        // next provider.
        assertThat(error).isInstanceOf(GatewayError.UpstreamUnavailable.class);
        assertThat(error).isNotInstanceOf(GatewayError.RateLimited.class);
        assertThat(error.message()).contains("rate limiting");
    }

    @Test
    @DisplayName("401 and 403 are an operator problem, surfaced as UpstreamUnavailable")
    void badGatewayCredential() {
        // Our credential is wrong, not the caller's. Surfacing Unauthenticated would
        // tell the tenant their own key was rejected and send them to support over a
        // misconfiguration on our side.
        for (int status : new int[] {401, 403}) {
            GatewayError error = errorFor(status, "unauthorised");

            assertThat(error)
                    .as("status %d", status)
                    .isInstanceOf(GatewayError.UpstreamUnavailable.class);
            assertThat(error.message()).contains("not authorised");
            assertThat(error).isNotInstanceOf(GatewayError.Unauthenticated.class);
        }
    }

    @Test
    @DisplayName("400, 404 and 422 are the caller's fault, passed back as InvalidRequest")
    void callerRequestIsUnacceptable() {
        // These are the ones that must NOT be retried: an unknown model or an
        // out-of-range parameter fails identically on every provider, so classifying
        // them as unavailable would spend the whole fallback chain on a request that
        // cannot succeed.
        for (int status : new int[] {400, 404, 422}) {
            GatewayError error = errorFor(status, "bad request");

            assertThat(error)
                    .as("status %d", status)
                    .isInstanceOf(GatewayError.InvalidRequest.class);
            assertThat(error.retryable())
                    .as("status %d must not be retryable", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("any other error status falls through to UpstreamUnavailable, naming the code")
    void unmappedStatus() {
        // The default branch. 500 and 503 are the common cases, and the status value is
        // included in the message because "upstream provider returned" on its own gives
        // an operator nothing to search for.
        for (int status : new int[] {500, 502, 503}) {
            GatewayError error = errorFor(status, "boom");

            assertThat(error)
                    .as("status %d", status)
                    .isInstanceOf(GatewayError.UpstreamUnavailable.class);
            assertThat(error.message())
                    .as("status %d should name the code", status)
                    .contains(String.valueOf(status));
        }
    }

    @Test
    @DisplayName("an empty error body still maps, rather than failing on the body read")
    void emptyBody() {
        // The provider reads the body to log it and uses defaultIfEmpty(""). A 503 with
        // no body is what a load balancer in front of the vendor actually returns, and
        // it must not turn into a different failure on the read path.
        GatewayError error = errorFor(503, "");

        assertThat(error).isInstanceOf(GatewayError.UpstreamUnavailable.class);
    }
}
