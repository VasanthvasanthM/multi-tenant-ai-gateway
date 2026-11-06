package io.vasanth.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.AuthProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The authentication filter.
 *
 * <p>This is a security boundary, so the tests are written around the things that would
 * be a vulnerability rather than a defect: a request reaching the pipeline with no
 * tenant, an error message that confirms a key once existed, and a tenant id taken from
 * anywhere other than the key.
 */
class ApiKeyAuthFilterTest {

    private static final TenantId ACME = TenantId.of("acme");

    /** Registry that knows exactly one key. */
    private static ApiKeyRegistry registryWith(String validKey, TenantId tenant) {
        return presented -> validKey.equals(presented) ? Mono.just(tenant) : Mono.empty();
    }

    private static AuthProperties properties(String headerName) {
        return new AuthProperties(headerName, Map.of());
    }

    /** Chain that records whether it ran, and what tenant was on the exchange. */
    private static final class RecordingChain implements WebFilterChain {
        private boolean invoked;
        private final AtomicReference<Object> seenTenant = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            invoked = true;
            seenTenant.set(exchange.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE));
            return Mono.empty();
        }
    }

    private static MockServerWebExchange exchange(String path, String header, String value) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post(path);
        if (value != null) {
            builder = builder.header(header, value);
        }
        return MockServerWebExchange.from(builder.build());
    }

    // ------------------------------------------------------------------
    // Accepted credentials
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a valid bearer key resolves the tenant onto the exchange")
    void validBearerKeyAuthenticates() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();
        MockServerWebExchange exchange =
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, "Bearer sk-live-123");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(chain.invoked).isTrue();
        assertThat(chain.seenTenant.get()).isEqualTo(ACME);
    }

    @Test
    @DisplayName("a bare key value is accepted as well as a Bearer prefix")
    void bareKeyIsAccepted() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, "sk-live-123"),
                chain)).verifyComplete();

        assertThat(chain.seenTenant.get()).isEqualTo(ACME);
    }

    @ParameterizedTest(name = "prefix \"{0}\"")
    @ValueSource(strings = {"Bearer ", "bearer ", "BEARER ", "BeArEr "})
    @DisplayName("the Bearer prefix is matched case-insensitively, as HTTP requires")
    void bearerPrefixIsCaseInsensitive(String prefix) {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION,
                        prefix + "sk-live-123"),
                chain)).verifyComplete();

        assertThat(chain.seenTenant.get()).isEqualTo(ACME);
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed from the credential")
    void whitespaceIsTrimmed() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION,
                        "  Bearer   sk-live-123  "),
                chain)).verifyComplete();

        assertThat(chain.seenTenant.get()).isEqualTo(ACME);
    }

    @Test
    @DisplayName("a custom header name is honoured")
    void customHeaderName() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties("X-Api-Key"));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", "X-Api-Key", "sk-live-123"), chain))
                .verifyComplete();

        assertThat(chain.seenTenant.get()).isEqualTo(ACME);
    }

    // ------------------------------------------------------------------
    // Rejected credentials
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing header is rejected and the chain never runs")
    void missingHeaderIsRejected() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, null), chain))
                .verifyErrorSatisfies(error -> {
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.Unauthenticated.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(401);
                    // Naming the header is actionable and leaks nothing.
                    assertThat(gatewayError.message()).contains(HttpHeaders.AUTHORIZATION);
                });

        // The important half: nothing downstream saw the request.
        assertThat(chain.invoked).isFalse();
    }

    @ParameterizedTest(name = "header value \"{0}\"")
    @ValueSource(strings = {"", "   ", "Bearer", "Bearer ", "Bearer    "})
    @DisplayName("a blank or prefix-only credential counts as missing")
    void blankCredentialsAreRejected(String value) {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, value), chain))
                .verifyError(GatewayException.class);

        assertThat(chain.invoked).isFalse();
    }

    @Test
    @DisplayName("an unknown key is rejected without revealing whether it ever existed")
    void unknownKeyIsRejectedOpaquely() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, "Bearer sk-wrong"),
                chain))
                .verifyErrorSatisfies(error -> {
                    String message = ((GatewayException) error).error().message();
                    // "invalid API key" and nothing more. Distinguishing "unknown" from
                    // "revoked" would confirm to an attacker that a key once existed,
                    // which is a real information leak from a probing attack.
                    assertThat(message).isEqualTo("invalid API key");
                    assertThat(message).doesNotContain("revoked", "expired", "unknown");
                });

        assertThat(chain.invoked).isFalse();
    }

    @Test
    @DisplayName("the presented key never appears in the error message")
    void presentedKeyIsNotEchoed() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));

        StepVerifier.create(filter.filter(
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION,
                        "Bearer sk-secret-value-abc"),
                new RecordingChain()))
                .verifyErrorSatisfies(error -> assertThat(
                        ((GatewayException) error).error().message())
                        // Echoing it would put a credential into whatever logs or error
                        // trackers the caller aggregates.
                        .doesNotContain("sk-secret-value-abc"));
    }

    // ------------------------------------------------------------------
    // Paths that bypass authentication
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is reachable unauthenticated")
    @ValueSource(strings = {
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/prometheus",
            "/v3/api-docs",
            "/swagger-ui/index.html",
            "/webjars/swagger-ui/favicon.png",
    })
    @DisplayName("probes and docs bypass authentication")
    void unauthenticatedPrefixesBypass(String path) {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        // Kubernetes probes cannot carry a tenant credential, and a 401 on
        // /actuator/health would make every pod permanently unready.
        StepVerifier.create(filter.filter(
                exchange(path, HttpHeaders.AUTHORIZATION, null), chain)).verifyComplete();

        assertThat(chain.invoked).isTrue();
        // And crucially, no tenant is attached - so nothing downstream can mistake an
        // unauthenticated probe for a tenant request.
        assertThat(chain.seenTenant.get()).isNull();
    }

    @Test
    @DisplayName("a path merely CONTAINING an exempt segment is still authenticated")
    void exemptionIsPrefixOnly() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(
                registryWith("sk-live-123", ACME), properties(HttpHeaders.AUTHORIZATION));
        RecordingChain chain = new RecordingChain();

        // startsWith, not contains. If it were `contains`, then
        // /v1/chat/completions?x=/actuator would bypass authentication entirely.
        StepVerifier.create(filter.filter(
                exchange("/v1/actuator/chat", HttpHeaders.AUTHORIZATION, null), chain))
                .verifyError(GatewayException.class);

        assertThat(chain.invoked).isFalse();
    }

    // ------------------------------------------------------------------
    // tenantOf
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tenantOf returns the authenticated tenant")
    void tenantOfReturnsTheTenant() {
        MockServerWebExchange exchange =
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, null);
        exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ATTRIBUTE, ACME);

        assertThat(ApiKeyAuthFilter.tenantOf(exchange)).isEqualTo(ACME);
    }

    @Test
    @DisplayName("tenantOf throws rather than inventing an anonymous tenant")
    void tenantOfFailsLoudly() {
        MockServerWebExchange exchange =
                exchange("/v1/chat/completions", HttpHeaders.AUTHORIZATION, null);

        // Only reachable if a route were added outside the filter's scope. Defaulting to
        // an "anonymous" tenant would give every unauthenticated caller the SAME cache
        // namespace - a cross-tenant read by construction.
        assertThatThrownBy(() -> ApiKeyAuthFilter.tenantOf(exchange))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no tenant on exchange");
    }
}
