package io.vasanth.gateway.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.auth.ApiKeyAuthFilter;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Refusing a tenant's traffic while its data is in transit between shards. */
class MigrationPauseFilterTest {

    private static final TenantId ACME = TenantId.of("acme");

    /** Chain that records whether the request was allowed through. */
    private static final class RecordingChain implements WebFilterChain {
        private boolean invoked;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            invoked = true;
            return Mono.empty();
        }
    }

    private static MockServerWebExchange exchangeFor(TenantId tenant) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions"));
        if (tenant != null) {
            exchange.getAttributes().put(ApiKeyAuthFilter.TENANT_ATTRIBUTE, tenant);
        }
        return exchange;
    }

    private static MigrationPauseFilter filterWith(boolean paused, SimpleMeterRegistry meters) {
        MigrationRegistry registry = mock(MigrationRegistry.class);
        when(registry.isPaused(any())).thenReturn(paused);
        return new MigrationPauseFilter(registry, meters);
    }

    @Test
    @DisplayName("a paused tenant is refused with 503 and a backoff")
    void refusesPausedTenant() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filterWith(true, meters).filter(exchangeFor(ACME), chain))
                .verifyErrorSatisfies(thrown -> {
                    GatewayError error = ((GatewayException) thrown).error();
                    assertThat(error).isInstanceOf(GatewayError.TenantMigrating.class);
                    assertThat(error.httpStatus()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("tenant_migrating");
                    // Retryable, and carrying a backoff - a client that honours
                    // Retry-After rides out the migration without a code change.
                    assertThat(error.retryable()).isTrue();
                    assertThat(((GatewayError.TenantMigrating) error).retryAfterSeconds())
                            .isPositive();
                });

        // Refused BEFORE any work: no rate-limit token spent, no cache lookup, no upstream.
        assertThat(chain.invoked).isFalse();
        assertThat(meters.find("gateway.migration.requests_refused").counter().count())
                .isEqualTo(1d);
    }

    @Test
    @DisplayName("an unpaused tenant passes straight through")
    void allowsUnpausedTenant() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RecordingChain chain = new RecordingChain();

        StepVerifier.create(filterWith(false, meters).filter(exchangeFor(ACME), chain))
                .verifyComplete();

        assertThat(chain.invoked).isTrue();
        assertThat(meters.find("gateway.migration.requests_refused").counter().count())
                .isZero();
    }

    @Test
    @DisplayName("a request with no tenant is never blocked")
    void allowsUnauthenticatedRequests() {
        RecordingChain chain = new RecordingChain();

        // Health checks and metrics carry no tenant. Blocking them during a migration
        // would make Kubernetes restart the pod doing the migration.
        StepVerifier.create(
                        filterWith(true, new SimpleMeterRegistry())
                                .filter(exchangeFor(null), chain))
                .verifyComplete();

        assertThat(chain.invoked).isTrue();
    }

    @Test
    @DisplayName("runs immediately after authentication, which resolves the tenant")
    void orderedAfterAuthentication() {
        int auth = ApiKeyAuthFilter.class.getAnnotation(Order.class).value();
        int pause = MigrationPauseFilter.class.getAnnotation(Order.class).value();

        // Asserted rather than assumed: this filter reads the tenant attribute that the
        // auth filter sets, so ordering it first would make it silently do nothing -
        // every tenant would read as null, and no pause would ever apply.
        assertThat(pause).isGreaterThan(auth);
        assertThat(auth).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }
}
