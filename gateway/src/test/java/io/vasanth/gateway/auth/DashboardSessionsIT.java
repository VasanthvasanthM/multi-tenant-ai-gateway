package io.vasanth.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.AuthProperties;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Dashboard sessions against real Redis.
 *
 * <p>The properties worth proving are all about the credential: that a session id is
 * unguessable, that it expires, that revoking it works, and that an invalid key yields
 * nothing at all. Each is a statement about Redis state, so a mock would only restate the
 * stub.
 */
@Testcontainers
class DashboardSessionsIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private static final Duration PATIENCE = Duration.ofSeconds(10);
    private static final String GOOD_KEY = "sk-test-acme-0000000000000000000000";

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;

    private DashboardSessions sessions;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getReactiveConnection()
                .serverCommands().flushAll().block(PATIENCE);

        // A real registry over the bootstrap map, so the key verification path is the
        // same one production uses rather than a stub that always says yes.
        AuthProperties properties = new AuthProperties(
                "Authorization", Map.of(GOOD_KEY, "acme"));
        sessions = new DashboardSessions(
                redis, new RedisApiKeyRegistry(redis, properties));
    }

    @Test
    @DisplayName("a valid key yields a session that resolves to its tenant")
    void createAndResolve() {
        String sessionId = sessions.create(GOOD_KEY).block(PATIENCE);

        assertThat(sessionId).isNotNull();
        assertThat(sessions.resolve(sessionId).block(PATIENCE))
                .isEqualTo(TenantId.of("acme"));
    }

    @Test
    @DisplayName("the session id is 256 bits of hex, not a UUID")
    void sessionIdIsHighEntropy() {
        String sessionId = sessions.create(GOOD_KEY).block(PATIENCE);

        // 32 bytes as hex. A session id is a bearer credential; a UUID would be 122 bits
        // from an unspecified source, and there is no reason to economise here.
        assertThat(sessionId).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("two sessions never collide")
    void idsAreDistinct() {
        String first = sessions.create(GOOD_KEY).block(PATIENCE);
        String second = sessions.create(GOOD_KEY).block(PATIENCE);

        assertThat(first).isNotEqualTo(second);
        // Both remain valid: logging in on a second device must not end the first.
        assertThat(sessions.resolve(first).block(PATIENCE)).isNotNull();
        assertThat(sessions.resolve(second).block(PATIENCE)).isNotNull();
    }

    @Test
    @DisplayName("an invalid key yields no session")
    void invalidKeyIsRefused() {
        StepVerifier.create(sessions.create("sk-not-a-real-key")).verifyComplete();
        StepVerifier.create(sessions.create("")).verifyComplete();
        StepVerifier.create(sessions.create(null)).verifyComplete();

        // Nothing written, so a brute-force attempt leaves no residue in Redis.
        assertThat(redis.keys("dash:session:*").collectList().block(PATIENCE)).isEmpty();
    }

    @Test
    @DisplayName("the session carries a TTL, so it cannot outlive the working day")
    void sessionExpires() {
        String sessionId = sessions.create(GOOD_KEY).block(PATIENCE);

        long ttl = redis.getExpire(DashboardSessions.redisKey(sessionId))
                .block(PATIENCE).toSeconds();

        // Zero would mean no expiry - a permanent credential handed to a browser.
        assertThat(ttl).isPositive()
                .isLessThanOrEqualTo(DashboardSessions.TTL.toSeconds());
    }

    @Test
    @DisplayName("an unknown session resolves to nothing")
    void unknownSessionIsEmpty() {
        StepVerifier.create(sessions.resolve("deadbeef".repeat(8))).verifyComplete();
        StepVerifier.create(sessions.resolve("")).verifyComplete();
        StepVerifier.create(sessions.resolve(null)).verifyComplete();
    }

    @Test
    @DisplayName("revoking ends the session, and is idempotent")
    void revoke() {
        String sessionId = sessions.create(GOOD_KEY).block(PATIENCE);

        assertThat(sessions.revoke(sessionId).block(PATIENCE)).isTrue();
        StepVerifier.create(sessions.resolve(sessionId)).verifyComplete();
        // Second revoke is false rather than an error: logging out twice must not fail.
        assertThat(sessions.revoke(sessionId).block(PATIENCE)).isFalse();
    }

    @Test
    @DisplayName("revoking one session leaves others alone")
    void revokeIsScoped() {
        String keep = sessions.create(GOOD_KEY).block(PATIENCE);
        String drop = sessions.create(GOOD_KEY).block(PATIENCE);

        sessions.revoke(drop).block(PATIENCE);

        assertThat(sessions.resolve(keep).block(PATIENCE)).isEqualTo(TenantId.of("acme"));
    }

    @Test
    @DisplayName("a session key is not tenant-hash-tagged, because lookup starts from the id")
    void keyShape() {
        assertThat(DashboardSessions.redisKey("abc")).isEqualTo("dash:session:abc");
    }

    @Test
    @DisplayName("revoking a null session is a no-op rather than an error")
    void revokeNull() {
        assertThat(sessions.revoke(null).block(PATIENCE)).isFalse();
        StepVerifier.create(Mono.just(true)).expectNext(true).verifyComplete();
    }
}
