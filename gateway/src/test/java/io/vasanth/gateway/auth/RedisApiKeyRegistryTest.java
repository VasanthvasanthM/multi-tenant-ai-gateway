package io.vasanth.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.AuthProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * API key resolution.
 *
 * <p>Two properties here are security properties rather than behaviour:
 *
 * <ul>
 *   <li>the RAW key is never used as a Redis key - only its SHA-256, so a dump of the
 *       key space does not yield usable credentials;
 *   <li>authentication fails CLOSED when Redis is unreachable. This is the deliberate
 *       opposite of the rate limiter, which fails open.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisApiKeyRegistryTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private RedisApiKeyRegistry registry(Map<String, String> bootstrapKeys) {
        return new RedisApiKeyRegistry(redis, new AuthProperties("Authorization", bootstrapKeys));
    }

    // ------------------------------------------------------------------
    // Redis-backed lookup
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a key is looked up by its SHA-256, never by its raw value")
    void looksUpByDigestNotRawKey() {
        String raw = "sk-live-abcdef";
        String expectedRedisKey = "apikey:" + RedisApiKeyRegistry.sha256Hex(raw);
        when(valueOps.get(expectedRedisKey)).thenReturn(Mono.just("acme"));

        TenantId tenant = registry(Map.of()).resolve(raw).block();

        assertThat(tenant).isEqualTo(TenantId.of("acme"));
        verify(valueOps).get(expectedRedisKey);
        // The security property: a Redis dump yields digests, not usable credentials.
        verify(valueOps, never()).get("apikey:" + raw);
    }

    @Test
    @DisplayName("an unknown key resolves to empty")
    void unknownKeyIsEmpty() {
        when(valueOps.get(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(registry(Map.of()).resolve("sk-nope")).verifyComplete();
    }

    @Test
    @DisplayName("authentication fails CLOSED when Redis is unreachable")
    void redisFailureDeniesTheRequest() {
        when(valueOps.get(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("connection refused")));

        // Empty means "deny". Deliberately the opposite of the rate limiter's
        // fail-open: there is no acceptable reading of "Redis is down, so let everyone
        // in". The cost of failing closed is an outage; the cost of failing open is
        // unauthenticated access to every tenant's cache.
        StepVerifier.create(registry(Map.of()).resolve("sk-live-abcdef")).verifyComplete();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("a blank credential is rejected without touching Redis")
    void blankCredentialSkipsRedis(String presented) {
        StepVerifier.create(registry(Map.of()).resolve(presented)).verifyComplete();

        verify(valueOps, never()).get(anyString());
    }

    // ------------------------------------------------------------------
    // Bootstrap keys - a development convenience that must stay loud
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a configured bootstrap key resolves without reaching Redis")
    void bootstrapKeyResolvesLocally() {
        RedisApiKeyRegistry registry = registry(Map.of("sk-dev-local", "devtenant"));

        TenantId tenant = registry.resolve("sk-dev-local").block();

        assertThat(tenant).isEqualTo(TenantId.of("devtenant"));
        verify(valueOps, never()).get(anyString());
    }

    @Test
    @DisplayName("a non-bootstrap key still falls through to Redis")
    void nonBootstrapKeyFallsThroughToRedis() {
        RedisApiKeyRegistry registry = registry(Map.of("sk-dev-local", "devtenant"));
        String raw = "sk-live-real";
        when(valueOps.get("apikey:" + RedisApiKeyRegistry.sha256Hex(raw)))
                .thenReturn(Mono.just("acme"));

        assertThat(registry.resolve(raw).block()).isEqualTo(TenantId.of("acme"));
    }

    @Test
    @DisplayName("bootstrap keys are stored hashed, not in plain text")
    void bootstrapKeysAreHashed() {
        AuthProperties properties = new AuthProperties(
                "Authorization", Map.of("sk-dev-local", "devtenant"));

        Map<String, TenantId> hashed = properties.hashedBootstrapKeys();

        // Hashed once at startup, so the raw value is not re-read on every request and
        // the comparison is digest-to-digest.
        assertThat(hashed).containsOnlyKeys(RedisApiKeyRegistry.sha256Hex("sk-dev-local"));
        assertThat(hashed).doesNotContainKey("sk-dev-local");
        assertThat(hashed.values()).containsExactly(TenantId.of("devtenant"));
    }

    @Test
    @DisplayName("a wrong key is not matched against a bootstrap entry")
    void wrongKeyDoesNotMatchBootstrap() {
        RedisApiKeyRegistry registry = registry(Map.of("sk-dev-local", "devtenant"));
        when(valueOps.get(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(registry.resolve("sk-dev-locaL")).verifyComplete();
    }

    @Test
    @DisplayName("several bootstrap keys each resolve to their own tenant")
    void multipleBootstrapKeys() {
        RedisApiKeyRegistry registry = registry(Map.of(
                "sk-dev-acme", "acme",
                "sk-dev-globex", "globex"));

        assertThat(registry.resolve("sk-dev-acme").block()).isEqualTo(TenantId.of("acme"));
        assertThat(registry.resolve("sk-dev-globex").block()).isEqualTo(TenantId.of("globex"));
        // Tenant isolation holds for the development path too, or a local test would
        // not exercise the property that matters most.
        assertThat(registry.resolve("sk-dev-acme").block())
                .isNotEqualTo(TenantId.of("globex"));
    }

    // ------------------------------------------------------------------
    // The digest itself
    // ------------------------------------------------------------------

    @Test
    @DisplayName("sha256Hex is the standard digest, lowercase hex")
    void digestIsStandard() {
        // A known vector, so a change to the hashing would be caught rather than
        // silently invalidating every stored key in every deployment.
        assertThat(RedisApiKeyRegistry.sha256Hex("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(RedisApiKeyRegistry.sha256Hex("")).isEqualTo(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("the digest is deterministic and differs per input")
    void digestIsDeterministic() {
        assertThat(RedisApiKeyRegistry.sha256Hex("sk-a"))
                .isEqualTo(RedisApiKeyRegistry.sha256Hex("sk-a"))
                .isNotEqualTo(RedisApiKeyRegistry.sha256Hex("sk-b"));
    }

    @Test
    @DisplayName("the digest is 64 lowercase hex characters")
    void digestShape() {
        assertThat(RedisApiKeyRegistry.sha256Hex("anything"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }
}
