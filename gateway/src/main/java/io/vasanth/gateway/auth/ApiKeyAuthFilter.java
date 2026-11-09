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

    /**
     * Paths a dashboard SESSION COOKIE may authenticate.
     *
     * <p>Read-only usage endpoints, and nothing else. A session is a credential held by a
     * browser, so it must not be able to spend money: a cookie cannot reach
     * {@code /v1/chat/completions}, which still requires the API key in a header. That
     * bound is also what makes {@code SameSite=Strict} sufficient without a separate CSRF
     * token - there is no state-changing request a forged cross-site call could make.
     *
     * <p>Listed explicitly rather than derived by excluding completions. An allow-list
     * fails closed when a new endpoint is added; a deny-list silently admits it.
     */
    private static final Set<String> SESSION_ALLOWED_PREFIXES = Set.of("/v1/usage");

    private final ApiKeyRegistry registry;
    private final DashboardSessions sessions;
    private final String headerName;

    /**
     * Header-only authentication, with no dashboard session support.
     *
     * <p>For callers that do not wire a session store. A cookie then never authenticates
     * anything, which fails closed.
     */
    public ApiKeyAuthFilter(ApiKeyRegistry registry, AuthProperties properties) {
        this(registry, null, properties);
    }

    /**
     * The constructor Spring uses.
     *
     * <p>{@code @Autowired} is load-bearing once a second constructor exists: with two
     * candidates and neither annotated, Spring has no no-arg fallback to take and the
     * context fails to start. The two-argument overload below is for callers that wire
     * the filter by hand, and must never be the injection point - it disables session
     * authentication entirely.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyAuthFilter(
            ApiKeyRegistry registry, DashboardSessions sessions, AuthProperties properties) {
        this.registry = registry;
        this.sessions = sessions;
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
            // No header. A dashboard session cookie is the only other way in, and only
            // for the read-only paths above - checked AFTER the header so an explicit
            // key always wins and a stale cookie can never shadow it.
            return authenticateBySession(exchange, chain, path);
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
     * Falls back to the dashboard session cookie.
     *
     * <p>Refuses with the same "missing API key" message on a non-usage path, rather than
     * saying a cookie was present but not permitted here. Confirming that a valid session
     * exists tells an attacker their cookie is real, which is information they should not
     * get from an endpoint they cannot use anyway.
     */
    private Mono<Void> authenticateBySession(
            ServerWebExchange exchange, WebFilterChain chain, String path) {

        if (sessions == null || !sessionAllowed(path)) {
            return Mono.error(new GatewayException(new GatewayError.Unauthenticated(
                    "missing API key; supply it in the " + headerName + " header")));
        }

        org.springframework.http.HttpCookie cookie =
                exchange.getRequest().getCookies().getFirst(DashboardSessions.COOKIE_NAME);
        if (cookie == null || cookie.getValue().isBlank()) {
            return Mono.error(new GatewayException(new GatewayError.Unauthenticated(
                    "missing API key; supply it in the " + headerName + " header")));
        }

        return sessions.resolve(cookie.getValue())
                .switchIfEmpty(Mono.error(new GatewayException(
                        // Same wording as an invalid key: an expired session and a forged
                        // one are indistinguishable to the caller on purpose.
                        new GatewayError.Unauthenticated("invalid or expired session"))))
                .flatMap(tenant -> {
                    exchange.getAttributes().put(TENANT_ATTRIBUTE, tenant);
                    log.debug("authenticated tenant={} path={} via dashboard session",
                            tenant, path);
                    return chain.filter(exchange)
                            .contextWrite(Context.of(TENANT_CONTEXT_KEY, tenant.value()));
                });
    }

    private static boolean sessionAllowed(String path) {
        for (String prefix : SESSION_ALLOWED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
