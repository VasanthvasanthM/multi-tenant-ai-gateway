package io.vasanth.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.AuthProperties;
import reactor.core.publisher.Mono;

/**
 * Resolves API keys from Redis, with a configured bootstrap map for local development.
 *
 * <h2>Keys are never stored in plaintext</h2>
 *
 * <p>Only the SHA-256 of a key is ever written down, here or in Postgres. An attacker who
 * reads the Redis keyspace or a database backup therefore obtains hashes, not usable
 * credentials. API keys are high-entropy random strings rather than user-chosen passwords,
 * so a plain digest is appropriate - a slow KDF like bcrypt exists to defeat dictionary
 * attacks on guessable input, and would add milliseconds to a 2 ms budget for no security
 * gain against 256 bits of randomness.
 *
 * <h2>Comparison is constant-time</h2>
 *
 * <p>The bootstrap map is checked with {@link MessageDigest#isEqual}, not
 * {@link String#equals}. String comparison short-circuits on the first differing byte, and
 * the timing difference is measurable across enough requests - it lets an attacker recover
 * a valid hash one byte at a time. The Redis path is immune by construction, since a lookup
 * either finds the key or does not.
 */
@Component
public class RedisApiKeyRegistry implements ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(RedisApiKeyRegistry.class);

    /** Not hash-tagged: this index is global, not tenant-scoped. */
    private static final String KEY_PREFIX = "apikey:";

    private static final HexFormat HEX = HexFormat.of();

    private final ReactiveStringRedisTemplate redis;

    /** Digest to tenant, from configuration. Empty in production. */
    private final Map<String, TenantId> bootstrapKeys;

    public RedisApiKeyRegistry(ReactiveStringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.bootstrapKeys = properties.hashedBootstrapKeys();

        if (!bootstrapKeys.isEmpty()) {
            // Loud on purpose. Static keys are a development convenience and shipping them
            // to production is a credential leak, so it must be impossible to miss in a log.
            log.warn(
                    "{} static bootstrap API key(s) configured. These are for local "
                            + "development only and MUST NOT be set in a deployed environment.",
                    bootstrapKeys.size());
        }
    }

    @Override
    public Mono<TenantId> resolve(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Mono.empty();
        }

        String digest = sha256Hex(presentedKey);

        TenantId bootstrap = matchBootstrap(digest);
        if (bootstrap != null) {
            return Mono.just(bootstrap);
        }

        return redis.opsForValue()
                .get(KEY_PREFIX + digest)
                .map(TenantId::of)
                .onErrorResume(error -> {
                    // Authentication fails closed, unconditionally. Unlike the rate limiter,
                    // which trades enforcement for availability, there is no acceptable
                    // reading of "Redis is down so let everyone in".
                    log.error("api key lookup failed, denying request: {}", error.toString());
                    return Mono.empty();
                });
    }

    private TenantId matchBootstrap(String digest) {
        if (bootstrapKeys.isEmpty()) {
            return null;
        }
        byte[] presented = digest.getBytes(StandardCharsets.UTF_8);

        // Every entry is compared, with no early exit, so that total time does not reveal
        // how many entries were examined before a match.
        TenantId found = null;
        for (Map.Entry<String, TenantId> entry : bootstrapKeys.entrySet()) {
            byte[] candidate = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(presented, candidate)) {
                found = entry.getValue();
            }
        }
        return found;
    }

    /** SHA-256 of a key, lowercase hex. Public so the control plane can index identically. */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }
}
