package io.vasanth.gateway.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Short-lived browser sessions for the dashboard, so an API key never reaches the bundle.
 *
 * <h2>The problem</h2>
 *
 * <p>The dashboard previously read its credential from {@code import.meta.env.VITE_API_KEY},
 * which Vite <b>compiles into the bundle</b>. Every visitor could read the tenant's API key
 * out of devtools, which made the dashboard a local-development tool and nothing else.
 *
 * <h2>The exchange</h2>
 *
 * <p>A human pastes the key once; the gateway verifies it, stores an opaque random session
 * id against the resolved tenant, and returns it as an {@code HttpOnly} cookie. The browser
 * can then read usage endpoints without JavaScript ever holding the key - {@code HttpOnly}
 * means script cannot read the cookie either, so an XSS on the dashboard cannot exfiltrate
 * a long-lived credential. The key itself is never written to {@code localStorage}, never
 * persisted, and never compiled in.
 *
 * <h2>Why the session id is opaque and random rather than a signed token</h2>
 *
 * <p>A JWT would avoid a Redis lookup and could not be revoked. Here revocation matters
 * more than the lookup: a session is a credential handed to a browser, and the operator has
 * to be able to end it. Redis already holds the API key index, so the lookup is the same
 * round trip the header path already pays.
 *
 * <h2>Scope, deliberately narrow</h2>
 *
 * <p>A session authenticates <b>read-only usage endpoints only</b>. It cannot be used to
 * spend money on completions - see {@link ApiKeyAuthFilter}. That bounds what a stolen
 * cookie is worth and is why {@code SameSite=Strict} plus GET-only endpoints is adequate
 * here without a separate CSRF token: there is no state-changing request a forged
 * cross-site call could make.
 */
@Component
public class DashboardSessions {

    private static final Logger log = LoggerFactory.getLogger(DashboardSessions.class);

    /** Cookie name. The {@code __Host-} prefix is not used because it forbids a path. */
    public static final String COOKIE_NAME = "aigw_dashboard";

    /**
     * Session lifetime.
     *
     * <p>Eight hours - a working day, so an operator is not re-authenticating hourly, and
     * short enough that a cookie copied off a machine stops working the same day. Renewal
     * is a fresh exchange rather than a sliding window: a sliding window on a read-only
     * session is effectively permanent for anyone who keeps the tab open.
     */
    public static final Duration TTL = Duration.ofHours(8);

    private static final String KEY_PREFIX = "dash:session:";

    /**
     * 256 bits from a CSPRNG.
     *
     * <p>{@link SecureRandom}, never {@code Math.random()} or a UUID: a session id is a
     * bearer credential, and {@code UUID.randomUUID()} gives 122 bits from an unspecified
     * source. There is no reason to economise on 16 extra bytes.
     */
    private static final int SESSION_ID_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final ReactiveStringRedisTemplate redis;
    private final ApiKeyRegistry registry;

    public DashboardSessions(ReactiveStringRedisTemplate redis, ApiKeyRegistry registry) {
        this.redis = redis;
        this.registry = registry;
    }

    /**
     * Verifies an API key and issues a session.
     *
     * @return the new session id, or an empty {@link Mono} when the key is not valid. The
     *     caller turns that into a 401 - this deliberately does not distinguish an unknown
     *     key from a revoked one, for the same reason {@link ApiKeyAuthFilter} does not.
     */
    public Mono<String> create(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Mono.empty();
        }

        return registry.resolve(presentedKey)
                .flatMap(tenant -> {
                    String sessionId = newSessionId();
                    return redis.opsForValue()
                            .set(redisKey(sessionId), tenant.value(), TTL)
                            .doOnSuccess(stored -> log.info(
                                    "dashboard session issued for tenant {} (ttl {})",
                                    tenant, TTL))
                            .thenReturn(sessionId);
                });
    }

    /** The tenant a session belongs to, or empty when it is unknown or expired. */
    public Mono<TenantId> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }
        return redis.opsForValue()
                .get(redisKey(sessionId))
                .filter(value -> !value.isBlank())
                .map(TenantId::of);
    }

    /** Ends a session. Idempotent: revoking an unknown session is a no-op, not an error. */
    public Mono<Boolean> revoke(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Mono.just(false);
        }
        return redis.delete(redisKey(sessionId)).map(removed -> removed > 0L);
    }

    private static String newSessionId() {
        byte[] bytes = new byte[SESSION_ID_BYTES];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    /**
     * Redis key for a session.
     *
     * <p>Not hash-tagged by tenant: the lookup starts from the session id and does not know
     * the tenant yet, exactly as the {@code apikey:} index does.
     */
    static String redisKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
