package io.vasanth.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.vasanth.gateway.auth.ApiKeyAuthFilter;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.usage.UsagePoint;
import io.vasanth.gateway.usage.UsagePricing;
import io.vasanth.gateway.usage.UsageQueryService;
import io.vasanth.gateway.usage.UsageSummary;
import reactor.core.publisher.Mono;

/**
 * The usage endpoints, through a real router and a real HTTP exchange.
 *
 * <p>Driven by {@link WebTestClient} bound to the router rather than by calling the
 * handler directly, because the parts worth testing are the HTTP-shaped ones: query
 * parameter parsing, the JSON field names the dashboard reads, and the status code for
 * a malformed window.
 *
 * <p>{@link UsageQueryService} is an interface precisely so this test needs no
 * {@code DatabaseClient} stubbing - the SQL has its own integration test.
 */
class UsageHandlerTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final Instant FROM = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-07T00:00:00Z");

    /** Records what the handler asked for, and returns fixed data. */
    private static final class StubQueryService implements UsageQueryService {
        private int requestedHours;
        private TenantId requestedTenant;
        private UsageSummary summary =
                new UsageSummary(FROM, TO, 1_000L, 700L, 600_000L, 400_000L, 300_000L);
        private List<UsagePoint> points = List.of(
                new UsagePoint(FROM, 400L, 280L, 240_000L, 160_000L, 120_000L),
                new UsagePoint(FROM.plusSeconds(3600), 600L, 420L, 360_000L, 240_000L, 180_000L));

        @Override
        public Mono<UsageSummary> summary(TenantId tenant, int hours) {
            this.requestedTenant = tenant;
            this.requestedHours = hours;
            return Mono.just(summary);
        }

        @Override
        public Mono<List<UsagePoint>> series(TenantId tenant, int hours) {
            this.requestedTenant = tenant;
            this.requestedHours = hours;
            return Mono.just(points);
        }
    }

    private StubQueryService queryService;
    private WebTestClient client;

    /** Puts an authenticated tenant on the exchange, as ApiKeyAuthFilter would. */
    private static WebFilter authenticatedAs(TenantId tenant) {
        return (ServerWebExchange exchange, org.springframework.web.server.WebFilterChain chain) -> {
            exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ATTRIBUTE, tenant);
            return chain.filter(exchange);
        };
    }

    private void build(UsagePricing pricing) {
        queryService = new StubQueryService();
        UsageHandler handler = new UsageHandler(queryService, pricing);
        RouterFunction<ServerResponse> routes = new UsageRouter().usageRoutes(handler);

        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        client = WebTestClient.bindToRouterFunction(routes)
                .webFilter(authenticatedAs(ACME))
                .handlerStrategies(org.springframework.web.reactive.function.server.HandlerStrategies
                        .builder()
                        .codecs(configurer -> {
                            configurer.defaultCodecs()
                                    .jackson2JsonEncoder(
                                            new org.springframework.http.codec.json.Jackson2JsonEncoder(mapper));
                            configurer.defaultCodecs()
                                    .jackson2JsonDecoder(
                                            new org.springframework.http.codec.json.Jackson2JsonDecoder(mapper));
                        })
                        .build())
                .build();
    }

    @BeforeEach
    void setUp() {
        build(UsagePricing.defaults());
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the summary returns the documented snake_case fields")
    void summaryFields() {
        client.get().uri("/v1/usage/summary")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.tenant_id").isEqualTo("acme")
                .jsonPath("$.window_hours").isEqualTo(24)
                .jsonPath("$.requests").isEqualTo(1_000)
                .jsonPath("$.cache_hits").isEqualTo(700)
                .jsonPath("$.hit_ratio").isEqualTo(0.7)
                .jsonPath("$.total_tokens").isEqualTo(1_000_000)
                .jsonPath("$.upstream_tokens").isEqualTo(300_000)
                .jsonPath("$.tokens_saved").isEqualTo(700_000)
                // 700,000 saved at $0.375/1M.
                // Rounded server-side: the raw double is 0.26249999999999996, which is
                // correct to the limit of a double and unacceptable in an API response.
                .jsonPath("$.estimated_saving_usd").isEqualTo(0.2625);
    }

    @Test
    @DisplayName("the tenant comes from the authenticated key, never from the request")
    void tenantComesFromAuthentication() {
        // A ?tenant= parameter must be ignored entirely. If it were honoured, a
        // cross-tenant read would be a URL away and the mistake would look like a
        // feature.
        client.get().uri("/v1/usage/summary?tenant=globex&hours=12")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenant_id").isEqualTo("acme");

        assertThat(queryService.requestedTenant).isEqualTo(ACME);
    }

    @Test
    @DisplayName("the money figure is omitted when no price is configured")
    void moneyOmittedWhenFree() {
        build(new UsagePricing(0.0d));

        client.get().uri("/v1/usage/summary")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // Absent, not zero. A self-hosted model has no per-token cost, so the
                // UI should hide the tile rather than render a confident $0.00.
                .jsonPath("$.estimated_saving_usd").doesNotExist()
                .jsonPath("$.tokens_saved").isEqualTo(700_000);
    }

    // ------------------------------------------------------------------
    // The window parameter
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hours defaults to 24")
    void defaultWindow() {
        client.get().uri("/v1/usage/summary")
                .accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk();

        assertThat(queryService.requestedHours).isEqualTo(24);
    }

    @Test
    @DisplayName("an explicit window is passed through")
    void explicitWindow() {
        client.get().uri("/v1/usage/summary?hours=168")
                .accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.window_hours").isEqualTo(168);

        assertThat(queryService.requestedHours).isEqualTo(168);
    }

    @Test
    @DisplayName("an over-large window is CLAMPED, not rejected")
    void oversizedWindowIsClamped() {
        client.get().uri("/v1/usage/summary?hours=999999")
                .accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk();

        // Asking for more history than exists is not an error - the caller should get
        // everything there is. Rejecting would make the UI responsible for knowing the
        // limit. The clamp also stops ?hours=1e9 being a table scan anyone can request.
        assertThat(queryService.requestedHours).isEqualTo(24 * 90);
    }

    @ParameterizedTest(name = "hours={0}")
    @ValueSource(strings = {"undefined", "NaN", "abc", "1.5", "0", "-1", "-24"})
    @DisplayName("a malformed window raises InvalidRequest, which maps to 400")
    void malformedWindowIsRejected(String raw) {
        // ?hours=undefined is what a JavaScript bug sends. Falling back to the default
        // would render 24 hours of data under a label claiming something else, and
        // nobody would notice.
        //
        // Asserted as a GatewayError rather than as an HTTP status, because
        // WebTestClient.bindToRouterFunction does not install the application's
        // ErrorWebExceptionHandler - so a raw status here would be 500 and would say
        // nothing about the contract. The error-to-status mapping has its own
        // exhaustive test in GatewayErrorWebExceptionHandlerTest; what matters here is
        // that the handler raises the RIGHT error.
        UsageHandler handler = new UsageHandler(new StubQueryService(), UsagePricing.defaults());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/usage/summary?hours=" + raw).build());
        exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ATTRIBUTE, ACME);

        reactor.test.StepVerifier
                .create(handler.summary(
                        org.springframework.web.reactive.function.server.ServerRequest
                                .create(exchange,
                                        org.springframework.web.reactive.function.server
                                                .HandlerStrategies.withDefaults()
                                                .messageReaders())))
                .verifyErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.InvalidRequest.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(400);
                    // Not retryable: resending the same malformed parameter cannot work.
                    assertThat(gatewayError.retryable()).isFalse();
                    assertThat(gatewayError.message()).contains("hours");
                });
    }

    @ParameterizedTest(name = "hours=\"{0}\"")
    @ValueSource(strings = {"", "   "})
    @DisplayName("a BLANK window takes the default, because that is how a form sends 'unset'")
    void blankWindowTakesTheDefault(String raw) {
        client.get().uri("/v1/usage/summary?hours=" + raw)
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.window_hours").isEqualTo(24);
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the series returns points oldest first with per-hour derivations")
    void seriesFields() {
        client.get().uri("/v1/usage/series?hours=2")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tenant_id").isEqualTo("acme")
                .jsonPath("$.window_hours").isEqualTo(2)
                .jsonPath("$.points.length()").isEqualTo(2)
                .jsonPath("$.points[0].requests").isEqualTo(400)
                .jsonPath("$.points[0].hit_ratio").isEqualTo(0.7)
                .jsonPath("$.points[0].tokens_saved").isEqualTo(280_000)
                // Oldest first, so a chart can plot it without sorting - and so two
                // clients cannot disagree about the direction.
                .jsonPath("$.points[1].requests").isEqualTo(600);
    }

    @Test
    @DisplayName("timestamps are ISO-8601 strings, not epoch numbers")
    void timestampsAreIso() {
        client.get().uri("/v1/usage/series")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                // A bare ObjectMapper writes an Instant as a floating-point epoch.
                // Spring Boot disables that; the dashboard parses these with
                // `new Date(...)`, which cannot read 1.7939664E9.
                .jsonPath("$.points[0].hour").isEqualTo("2026-11-06T00:00:00Z");
    }

    @Test
    @DisplayName("an unauthenticated exchange fails loudly rather than guessing a tenant")
    void unauthenticatedExchangeThrows() {
        // Reachable only if a route were registered outside the auth filter's scope.
        // tenantOf throws rather than inventing an anonymous tenant, whose usage every
        // caller would then share.
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/usage/summary")
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .build());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ApiKeyAuthFilter.tenantOf(exchange))
                .isInstanceOf(IllegalStateException.class);
    }
}
