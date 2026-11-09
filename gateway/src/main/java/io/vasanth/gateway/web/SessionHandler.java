package io.vasanth.gateway.web;

import java.util.Map;

import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.vasanth.gateway.auth.DashboardSessions;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import reactor.core.publisher.Mono;

/**
 * Exchanges an API key for a short-lived dashboard session cookie.
 *
 * <p>Exists so the dashboard never compiles a credential into its bundle. See
 * {@link DashboardSessions} for the reasoning; this is the HTTP surface.
 */
@Component
public class SessionHandler {

    private static final String BEARER_PREFIX = "Bearer ";

    private final DashboardSessions sessions;

    public SessionHandler(DashboardSessions sessions) {
        this.sessions = sessions;
    }

    /**
     * {@code POST /v1/dashboard/session} - verify a key, set the cookie.
     *
     * <p>The key arrives in the {@code Authorization} header rather than in a JSON body on
     * purpose: a body is logged by proxies far more often than a header, and every HTTP
     * client already knows not to print this one.
     *
     * <p>Returns the tenant id so the UI can show whose data it is displaying, and nothing
     * else. Notably <b>not</b> the session id: it is in an {@code HttpOnly} cookie
     * precisely so that script cannot read it, and echoing it in the body would undo that.
     */
    public Mono<ServerResponse> create(ServerRequest request) {
        String presentedKey = extractKey(request);
        if (presentedKey == null) {
            return Mono.error(new GatewayException(new GatewayError.Unauthenticated(
                    "supply an API key in the Authorization header to start a session")));
        }

        return sessions.create(presentedKey)
                .switchIfEmpty(Mono.error(new GatewayException(
                        new GatewayError.Unauthenticated("invalid API key"))))
                .flatMap(sessionId -> sessions.resolve(sessionId)
                        .flatMap(tenant -> ServerResponse.ok()
                                .cookie(sessionCookie(sessionId, request))
                                .bodyValue(Map.of(
                                        "tenant_id", tenant.value(),
                                        "expires_in_seconds", DashboardSessions.TTL
                                                .toSeconds()))));
    }

    /** {@code DELETE /v1/dashboard/session} - end it, and clear the cookie. */
    public Mono<ServerResponse> destroy(ServerRequest request) {
        HttpCookie cookie = request.cookies().getFirst(DashboardSessions.COOKIE_NAME);
        String sessionId = cookie == null ? null : cookie.getValue();

        // Clears the cookie whether or not the session existed. Logging out must always
        // leave the browser with no credential, even if the server never had one.
        return sessions.revoke(sessionId)
                .then(ServerResponse.noContent()
                        .cookie(expiredCookie(request))
                        .build());
    }

    /**
     * The session cookie.
     *
     * <ul>
     *   <li>{@code HttpOnly} - script cannot read it, so an XSS on the dashboard cannot
     *       exfiltrate a usable credential. This is the single most important flag here.
     *   <li>{@code SameSite=Strict} - not sent on cross-site requests at all, which is what
     *       makes a separate CSRF token unnecessary for these read-only endpoints.
     *   <li>{@code Secure} - set only when the request arrived over HTTPS. Hard-coding it
     *       true would make the cookie silently undeliverable over plain HTTP, so local
     *       development against {@code http://localhost} would appear to log in and then
     *       fail every subsequent request with no clue why.
     *   <li>{@code Path=/v1} - narrower than {@code /}, so it is not attached to requests
     *       for the dashboard's own static assets.
     * </ul>
     */
    private static ResponseCookie sessionCookie(String sessionId, ServerRequest request) {
        return ResponseCookie.from(DashboardSessions.COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(isHttps(request))
                .sameSite("Strict")
                .path("/v1")
                .maxAge(DashboardSessions.TTL)
                .build();
    }

    private static ResponseCookie expiredCookie(ServerRequest request) {
        return ResponseCookie.from(DashboardSessions.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(isHttps(request))
                .sameSite("Strict")
                .path("/v1")
                .maxAge(0)
                .build();
    }

    /**
     * Whether the original request was HTTPS.
     *
     * <p>Reads {@code X-Forwarded-Proto} as well as the URI scheme, because in Kubernetes
     * TLS terminates at the ingress and the request reaching the pod is plain HTTP. Without
     * this the cookie would never carry {@code Secure} in exactly the deployment that needs
     * it. {@code server.forward-headers-strategy=framework} is what makes the header
     * trustworthy here - it is only honoured from a proxy Spring is configured to trust.
     */
    private static boolean isHttps(ServerRequest request) {
        String forwarded = request.headers().firstHeader("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            return "https".equalsIgnoreCase(forwarded.trim());
        }
        return "https".equalsIgnoreCase(request.uri().getScheme());
    }

    private static String extractKey(ServerRequest request) {
        String value = request.headers().firstHeader(HttpHeaders.AUTHORIZATION);
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
}
