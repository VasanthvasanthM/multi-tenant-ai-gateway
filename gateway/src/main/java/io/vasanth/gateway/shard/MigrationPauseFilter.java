package io.vasanth.gateway.shard;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.auth.ApiKeyAuthFilter;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Refuses a tenant's requests while its data is being moved between shards.
 *
 * <h2>Why a filter and not a stage in the pipeline</h2>
 *
 * <p>Two call paths produce writes - {@code execute} and {@code stream} - and a pipeline
 * stage would have to be added to both, which is precisely how streaming came to bypass
 * admission control. A filter covers every current and future endpoint by construction, and
 * it refuses before any work is done: no rate-limit token spent, no budget read, no cache
 * lookup for a request that is about to be rejected.
 *
 * <h2>Ordering</h2>
 *
 * <p>Immediately after {@link ApiKeyAuthFilter}, which resolves the tenant this filter
 * needs. An unauthenticated request has no tenant attribute and passes straight through -
 * health checks and metrics must never be affected by a migration, and an unauthenticated
 * request writes nothing anyway.
 *
 * <h2>What the client sees</h2>
 *
 * <p>{@code 503} with {@code Retry-After}, and the code {@code tenant_migrating}. Only
 * tenants in {@code PAUSE} mode are refused - a dual-writing tenant is served normally,
 * which is the whole point of that mode. The pause
 * is measured in seconds, and {@code Retry-After} is a header an OpenAI-compatible SDK
 * already honours, so a well-behaved client rides it out without a code change.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class MigrationPauseFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(MigrationPauseFilter.class);

    /**
     * Advertised backoff.
     *
     * <p>Five seconds: long enough that a client is not hammering a tenant that is still
     * being copied, short enough that it recovers promptly once the cutover lands. The copy
     * itself is the thing being protected, and it is measured in seconds for a tenant small
     * enough that a pause is the right strategy at all.
     */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(5);

    private final MigrationRegistry registry;
    private final Counter refused;

    public MigrationPauseFilter(MigrationRegistry registry, MeterRegistry meters) {
        this.registry = registry;
        // Separate from the rate-limit rejection counter on purpose. Both refuse traffic;
        // one is the customer's doing and one is ours, and a dashboard that cannot tell
        // them apart turns a planned migration into an apparent abuse spike.
        this.refused = Counter.builder("gateway.migration.requests_refused")
                .description("Requests refused because the tenant is mid-migration")
                .register(meters);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        TenantId tenant = exchange.getAttribute(ApiKeyAuthFilter.TENANT_ATTRIBUTE);

        if (tenant == null || !registry.isPaused(tenant)) {
            return chain.filter(exchange);
        }

        refused.increment();
        // DEBUG, not WARN: during a migration this is the expected response for every
        // request from that tenant, and at their request rate a WARN would bury the log.
        // The counter is the signal; the INFO on the pause set changing is the event.
        log.debug("refusing request: tenant {} is mid-migration", tenant);

        return Mono.error(new GatewayException(
                new GatewayError.TenantMigrating(tenant.value(), RETRY_AFTER)));
    }
}
