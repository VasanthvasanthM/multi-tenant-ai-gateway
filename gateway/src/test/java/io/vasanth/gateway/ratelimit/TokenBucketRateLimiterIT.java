package io.vasanth.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * {@code token_bucket.lua} against real Redis.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code TokenBucketRateLimiterTest} mocks {@code redis.execute} outright, so it asserts
 * which arguments the limiter passes - not what the script does with them. Every claim the
 * script makes was therefore untested: that it is atomic, that a first request starts full,
 * that refill is computed from Redis's clock rather than the caller's, and that tokens are
 * clamped to capacity.
 *
 * <p>{@link #exactlyCapacityRequestsSucceedUnderConcurrency()} is the one that matters.
 * The script exists <em>only</em> because the naive GET/compute/SET is a read-modify-write
 * across a network round trip, so two replicas serving one tenant both read the same count
 * and both proceed - making the effective limit (configured limit x replica count). Under
 * load that is the normal case, not a rare race. No mock can demonstrate it, and nothing
 * else in this repository does.
 *
 * <p>Pattern copied from {@code TokenBudgetLedgerIT}: {@code GenericContainer}, a
 * hand-built connection factory, no Spring context, no new Maven dependency.
 */
@Testcontainers
class TokenBucketRateLimiterIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private static final TenantId ACME = TenantId.of("acme");
    private static final TenantId GLOBEX = TenantId.of("globex");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script;

    @BeforeAll
    @SuppressWarnings("rawtypes")
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);

        DefaultRedisScript<List> bucket = new DefaultRedisScript<>();
        bucket.setLocation(new ClassPathResource("scripts/token_bucket.lua"));
        bucket.setResultType(List.class);
        script = bucket;
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushAll()
                .block(PATIENCE);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A limiter with the given bucket shape.
     *
     * <p>{@code capacity >= refillPerSecond} is enforced by {@code RateLimit}, so a slow
     * refill needs a low rate rather than a small capacity.
     */
    private TokenBucketRateLimiter limiter(int capacity, int refillPerSecond) {
        GatewayProperties properties = new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, true),
                new GatewayProperties.RateLimit(true, capacity, refillPerSecond, true),
                new GatewayProperties.Limits(4000, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
        return new TokenBucketRateLimiter(redis, script, properties);
    }

    private TokenBucketRateLimiter disabledLimiter() {
        GatewayProperties properties = new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, true),
                new GatewayProperties.RateLimit(false, 10, 1, true),
                new GatewayProperties.Limits(4000, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
        return new TokenBucketRateLimiter(redis, script, properties);
    }

    private static String bucketKey(TenantId tenant) {
        return tenant.redisHashTag() + ":rl";
    }

    // ------------------------------------------------------------------
    // The reason the script is a script
    // ------------------------------------------------------------------

    @Test
    @DisplayName("exactly `capacity` requests succeed when 200 arrive at once")
    void exactlyCapacityRequestsSucceedUnderConcurrency() {
        // Refill of 1/s means roughly 0.05 tokens accrue over the ~50 ms this takes, so
        // the allowed count is decided by capacity alone and the assertion can be exact.
        TokenBucketRateLimiter limiter = limiter(10, 1);

        long allowed = Flux.range(0, 200)
                .flatMap(i -> limiter.tryAcquire(ACME).subscribeOn(Schedulers.parallel()), 200)
                .filter(RateLimitDecision::allowed)
                .count()
                .block(PATIENCE);

        // THE test. A read-modify-write implementation lets far more than 10 through here,
        // and the excess scales with concurrency - which is precisely the production
        // failure, where "concurrency" is the replica count and the limit silently becomes
        // limit x replicas. Redis runs the script atomically against the key, so the
        // read, the refill computation and the write cannot interleave.
        assertThat(allowed).isEqualTo(10L);
    }

    @Test
    @DisplayName("concurrent acquires never report the same remaining count twice")
    void remainingCountsAreDistinct() {
        TokenBucketRateLimiter limiter = limiter(20, 1);

        Set<Long> remaining = ConcurrentHashMap.newKeySet();
        Flux.range(0, 20)
                .flatMap(i -> limiter.tryAcquire(ACME).subscribeOn(Schedulers.parallel()), 20)
                .filter(RateLimitDecision::allowed)
                .doOnNext(decision -> remaining.add(decision.remainingTokens()))
                .blockLast(PATIENCE);

        // Each allowed acquire must observe its own post-decrement value. Duplicates mean
        // two callers decremented from the same read - the lost-update that atomicity is
        // there to prevent, visible even when the total happens to come out right.
        assertThat(remaining).hasSize(20);
        assertThat(remaining).containsExactlyInAnyOrderElementsOf(
                Flux.range(0, 20).map(Long::valueOf).collectList().block());
    }

    // ------------------------------------------------------------------
    // Bucket behaviour
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a bucket")
    class Bucket {

        @Test
        @DisplayName("starts FULL on first contact, so a first request never fails")
        void startsFull() {
            RateLimitDecision decision = limiter(10, 1).tryAcquire(ACME).block(PATIENCE);

            assertThat(decision.allowed()).isTrue();
            // Starting empty would make a tenant's very first request 429, which looks
            // exactly like an outage to them. 9 = capacity 10 minus this one token.
            assertThat(decision.remainingTokens()).isEqualTo(9L);
            assertThat(decision.retryAfter()).isEqualTo(Duration.ZERO);
            assertThat(decision.degraded()).isFalse();
        }

        @Test
        @DisplayName("denies with a positive Retry-After once drained")
        void deniesWhenDrained() {
            TokenBucketRateLimiter limiter = limiter(3, 1);

            for (int i = 0; i < 3; i++) {
                assertThat(limiter.tryAcquire(ACME).block(PATIENCE).allowed()).isTrue();
            }

            RateLimitDecision denied = limiter.tryAcquire(ACME).block(PATIENCE);
            assertThat(denied.allowed()).isFalse();
            // A real wait, so a well-behaved client backs off by the right amount instead
            // of guessing and making the overload worse. At 1 token/s this is ~1000 ms.
            assertThat(denied.retryAfter()).isPositive();
            assertThat(denied.retryAfter()).isLessThanOrEqualTo(Duration.ofSeconds(2));
            assertThat(denied.degraded()).isFalse();
        }

        @Test
        @DisplayName("refills over real elapsed time, from Redis's own clock")
        void refillsOverTime() throws InterruptedException {
            // 20/s, so ~400 ms should return ~8 tokens. Asserted as a range because this
            // measures wall-clock behaviour and a tight equality would be flaky by design.
            TokenBucketRateLimiter limiter = limiter(20, 20);

            // Drained in ONE call, not twenty. Twenty sequential round trips take 30-60 ms,
            // and at 20 tokens/s that is more than a whole token refilled while the loop
            // runs - so the bucket was never actually empty and the assertion below saw an
            // allowed request. The test was racing its own refill; the script was right.
            assertThat(limiter.tryAcquire(ACME, 20).block(PATIENCE).allowed()).isTrue();
            assertThat(limiter.tryAcquire(ACME).block(PATIENCE).allowed()).isFalse();

            Thread.sleep(400L);

            // The timestamp comes from redis.call('TIME'), not from this JVM. That is what
            // makes refill correct across replicas whose clocks drift independently - a pod
            // running fast would otherwise persist a future timestamp and every other pod
            // would compute negative elapsed time and refill nothing.
            RateLimitDecision afterWait = limiter.tryAcquire(ACME).block(PATIENCE);
            assertThat(afterWait.allowed()).isTrue();
            assertThat(afterWait.remainingTokens()).isBetween(1L, 15L);
        }

        @Test
        @DisplayName("tokens are clamped to capacity, however long it idles")
        void clampsToCapacity() throws InterruptedException {
            TokenBucketRateLimiter limiter = limiter(5, 5);

            limiter.tryAcquire(ACME).block(PATIENCE);
            // Long enough at 5/s to have accrued far more than capacity.
            Thread.sleep(2_000L);

            // Without the min() the bucket would accumulate unboundedly while idle, and a
            // tenant silent for an hour could then fire an hour's allowance in one burst -
            // which is exactly the spike a rate limiter exists to prevent.
            assertThat(limiter.tryAcquire(ACME).block(PATIENCE).remainingTokens())
                    .isEqualTo(4L);
        }

        @Test
        @DisplayName("gets a TTL, so dormant tenants cost no memory")
        void bucketExpires() {
            limiter(10, 1).tryAcquire(ACME).block(PATIENCE);

            // Zero would mean no expiry - see the note in TokenBudgetLedgerIT on Spring
            // Data normalising Redis's -1 sentinel. A million one-off tenants must not
            // occupy Redis permanently.
            assertThat(redis.getExpire(bucketKey(ACME)).block(PATIENCE).toSeconds())
                    .isPositive();
        }

        @Test
        @DisplayName("an expired bucket is recreated full, not empty")
        void expiredBucketIsRecreatedFull() {
            TokenBucketRateLimiter limiter = limiter(10, 1);
            limiter.tryAcquire(ACME).block(PATIENCE);

            // Simulates the idle expiry. Safe precisely because a missing bucket starts
            // full, which is the same state an hour of refilling would have produced.
            redis.delete(bucketKey(ACME)).block(PATIENCE);

            assertThat(limiter.tryAcquire(ACME).block(PATIENCE).remainingTokens())
                    .isEqualTo(9L);
        }
    }

    // ------------------------------------------------------------------
    // Isolation and configuration
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one tenant cannot drain another's bucket")
    void tenantsAreIsolated() {
        TokenBucketRateLimiter limiter = limiter(2, 1);

        limiter.tryAcquire(ACME).block(PATIENCE);
        limiter.tryAcquire(ACME).block(PATIENCE);
        assertThat(limiter.tryAcquire(ACME).block(PATIENCE).allowed()).isFalse();

        // The property that makes the limiter a multi-tenancy control rather than a global
        // throttle: one noisy tenant must not be able to refuse another's traffic.
        assertThat(limiter.tryAcquire(GLOBEX).block(PATIENCE).allowed()).isTrue();
    }

    @Test
    @DisplayName("the bucket key is cluster hash-tagged with the tenant")
    void bucketKeyIsHashTagged() {
        limiter(10, 1).tryAcquire(ACME).block(PATIENCE);

        // Required for the script to be legal in Redis Cluster mode, where one script may
        // only touch keys in a single slot. Sharing the tenant's tag puts the bucket in
        // the same slot as its cache entries and budget counter.
        assertThat(redis.hasKey("{tenant:acme}:rl").block(PATIENCE)).isTrue();
    }

    @Test
    @DisplayName("a multi-token acquire draws down that many")
    void multiTokenAcquire() {
        RateLimitDecision decision = limiter(10, 1).tryAcquire(ACME, 4).block(PATIENCE);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingTokens()).isEqualTo(6L);
    }

    @Test
    @DisplayName("an acquire larger than capacity is refused rather than wrapping")
    void acquireLargerThanCapacity() {
        RateLimitDecision decision = limiter(5, 1).tryAcquire(ACME, 9).block(PATIENCE);

        assertThat(decision.allowed()).isFalse();
        // And the bucket is NOT debited by a refused acquire, or a request that can never
        // succeed would drain the tenant on every attempt.
        assertThat(limiter(5, 1).tryAcquire(ACME).block(PATIENCE).allowed()).isTrue();
    }

    @Test
    @DisplayName("disabled means no Redis traffic at all")
    void disabledSkipsRedis() {
        RateLimitDecision decision = disabledLimiter().tryAcquire(ACME).block(PATIENCE);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingTokens()).isEqualTo(Long.MAX_VALUE);
        assertThat(redis.hasKey(bucketKey(ACME)).block(PATIENCE)).isFalse();
    }
}
