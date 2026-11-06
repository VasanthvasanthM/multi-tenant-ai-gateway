package io.vasanth.gateway.auth;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.AuthProperties;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Resolves the caller's API key to a tenant and attaches it to the exchange.
 *
 * <h2>Tenant identity comes from the key, never from the request</h2>
 *
 * <p>The tenant is derived solely from the credential and written to an exchange attribute
 * that only this filter sets. Nothing downstream reads a tenant id from the request body,
 * a path variable or a header. That single rule is what makes cross-tenant access
 * impossible by construction rather than by careful review of every handler.
 *
 * <h2>Ordered before everything else</h2>
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} + 10 so no business logic, metric or log
 * line can observe an unauthenticated request. The offset leaves room for tracing and
 * request-id filters, which must wrap authentication so that a rejected request is still
 * traceable.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Exchange attribute holding the resolved {@link TenantId}. */
    public static final String TENANT_ATTRIBUTE = "gateway.tenant";

    /** Reactor context key, so tenant is available for logging deep in a pipeline. */
    public static final String TENANT_CONTEXT_KEY = "tenant";

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Paths served without a key.
     *
     * <p>Health and metrics must stay reachable: a liveness probe cannot hold a tenant
     * credential, and a Prometheus scrape should not need one either. These are expected to
     * be bound to an internal listener or restricted by NetworkPolicy at deploy time, which
     * the Helm chart does.
     */
    private static final Set<String> UNAUTHENTICATED_PREFIXES =
            Set.of("/actuator", "/v3/api-docs", "/swagger-ui", "/webjars");

    private final ApiKeyRegistry registry;
    private final String headerName;

    public ApiKeyAuthFilter(ApiKeyRegistry registry, AuthProperties properties) {
        this.registry = registry;
        this.headerName = properties.headerName();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (isUnauthenticated(path)) {
            return chain.filter(exchange);
        }

        String presentedKey = extractKey(exchange);
        if (presentedKey == null) {
            return Mono.error(new GatewayException(new GatewayError.Unauthenticated(
                    "missing API key; supply it in the " + headerName + " header")));
        }

        return registry
                .resolve(presentedKey)
                .switchIfEmpty(Mono.error(new GatewayException(
                        // The message does not distinguish "unknown key" from "revoked
                        // key". Telling an attacker which of the two they hit confirms that
                        // a key once existed, which is information they should not get.
                        new GatewayError.Unauthenticated("invalid API key"))))
                .flatMap(tenant -> {
                    exchange.getAttributes().put(TENANT_ATTRIBUTE, tenant);
                    log.debug("authenticated tenant={} path={}", tenant, path);
                    return chain.filter(exchange)
                            .contextWrite(Context.of(TENANT_CONTEXT_KEY, tenant.value()));
                });
    }

    /**
     * Reads the key from the configured header, accepting a {@code Bearer} prefix.
     *
     * <p>Supporting both {@code Authorization: Bearer sk-...} and a bare value is what lets
     * an unmodified OpenAI SDK authenticate against this gateway.
     */
    private String extractKey(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();

        String value = headers.getFirst(headerName);
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();

        if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = value.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return value;
    }

    private static boolean isUnauthenticated(String path) {
        for (String prefix : UNAUTHENTICATED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Reads the tenant attached by this filter. */
    public static TenantId tenantOf(ServerWebExchange exchange) {
        TenantId tenant = exchange.getAttribute(TENANT_ATTRIBUTE);
        if (tenant == null) {
            // Only reachable if a route were added outside this filter's scope. Failing
            // loudly beats defaulting to some "anonymous" tenant whose cache every caller
            // would then share.
            throw new IllegalStateException(
                    "no tenant on exchange; a route was reached without authentication");
        }
        return tenant;
    }
}
