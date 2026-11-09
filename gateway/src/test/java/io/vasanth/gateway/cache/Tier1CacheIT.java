package io.vasanth.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import reactor.test.StepVerifier;

/**
 * Tier 1 against real Redis.
 *
 * <h2>What a mock could not reach</h2>
 *
 * <p>{@code Tier1CacheTest} stubs {@code ReactiveStringRedisTemplate}, so three things it
 * asserts are assertions about the stub rather than about Redis:
 *
 * <ul>
 *   <li><b>the TTL is actually applied</b> - a mock records that {@code set(key, value, ttl)}
 *       was called; only a server can show the key subsequently expires;
 *   <li><b>the entry round-trips through JSON</b> - the serialised form has to survive a
 *       real string codec, including the {@code Instant} fields, which need a configured
 *       mapper and silently become float seconds without one;
 *   <li><b>the {@code {tenant:x}} hash tag lands in the real keyspace</b>, which is what
 *       keeps a tenant's entries in one Redis Cluster slot.
 * </ul>
 *
 * <p>Lower risk than the rate limiter was - plain GET/SET, no Lua, no concurrency
 * invariant - which is why it came last of the three Redis suites. Pattern copied from
 * {@code TokenBucketRateLimiterIT}.
 */
@Testcontainers
class Tier1CacheIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private static final TenantId ACME = TenantId.of("acme");
    private static final TenantId GLOBEX = TenantId.of("globex");
    private static final ModelId MODEL = ModelId.of("gpt-4o-mini");
    private static final Instant CREATED = Instant.parse("2026-11-09T11:59:30Z");
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;
    private static ObjectMapper mapper;

    private SimpleMeterRegistry meters;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);

        // The mapper Boot would build. A bare ObjectMapper writes an Instant as float
        // seconds (1.7939664E9), which round-trips to a different value - the kind of
        // defect that only shows up as a timestamp drifting by milliseconds.
        mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
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
        meters = new SimpleMeterRegistry();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Tier1Cache cache(boolean enabled, Duration ttl) {
        GatewayProperties properties = new GatewayProperties(
                new GatewayProperties.Cache(
                        enabled, ttl, false, Duration.ofHours(24), 0.95, true),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(32_000, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
        return new Tier1Cache(redis, mapper, properties, meters);
    }

    private Tier1Cache cache() {
        return cache(true, Duration.ofHours(1));
    }

    private static CacheKey keyFor(TenantId tenant, String prompt) {
        return CacheKey.derive(CompletionRequest.of(tenant, MODEL, prompt));
    }

    private static CompletionResponse response(String content) {
        return new CompletionResponse(
                content, MODEL, new TokenUsage(11L, 4L), CacheTier.UPSTREAM,
                ProviderId.of("openai"), CREATED, Duration.ofMillis(820));
    }

    private double counter(String result) {
        return Optional.ofNullable(meters.find("gateway.cache.lookup")
                        .tag("tier", CacheTier.TIER1_EXACT.label())
                        .tag("result", result)
                        .counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    private double totalOutcomes() {
        return counter("hit") + counter("miss") + counter("error");
    }

    // ------------------------------------------------------------------
    // Round trip
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("a stored response comes back intact, re-tagged as a tier-1 hit")
        void storeThenGet() {
            Tier1Cache cache = cache();
            CacheKey key = keyFor(ACME, "how do I reset my password?");

            StepVerifier.create(cache.put(key, response("the answer"))).verifyComplete();

            CompletionResponse served = cache.get(key).block(PATIENCE);

            assertThat(served).isNotNull();
            assertThat(served.content()).isEqualTo("the answer");
            assertThat(served.model()).isEqualTo(MODEL);
            // Re-tagged: the entry was stored from an UPSTREAM response, and what comes
            // back must say it was served from Tier 1, or the hit-ratio metric and the
            // X-Cache-Tier header would both lie.
            assertThat(served.servedBy()).isEqualTo(CacheTier.TIER1_EXACT);
            // CACHE, not the original provider - deliberately, and the same as Tier 2:
            // no vendor was called to serve this, so attributing it to openai would make
            // per-provider request counts and spend wrong. The original IS preserved in
            // the stored entry (field "p") for analysis; it is simply not what the served
            // response claims. The assertion below pins both halves.
            assertThat(served.provider()).isEqualTo(ProviderId.CACHE);
            // And completion tokens are zeroed, because nothing was generated.
            assertThat(served.usage().completionTokens()).isZero();
            // Through a real string codec and a real JSON round trip, to the second.
            assertThat(served.createdAt()).isEqualTo(CREATED);
            assertThat(served.usage().promptTokens()).isEqualTo(11L);

            assertThat(counter("hit")).isEqualTo(1.0d);
            assertThat(totalOutcomes()).isEqualTo(1.0d);

            // The stored entry keeps the real upstream, under the short wire name.
            String stored = redis.opsForValue().get(key.redisKey()).block(PATIENCE);
            assertThat(stored).contains("\"p\":\"openai\"");
        }

        @Test
        @DisplayName("a miss is counted exactly once")
        void missIsCountedOnce() {
            StepVerifier.create(cache().get(keyFor(ACME, "never stored"))).verifyComplete();

            // The invariant the counter-placement bug broke: one lookup must increment
            // exactly one gateway.cache.lookup counter, because the hit ratio is hits over
            // their sum. Tier 2 counts the same way.
            assertThat(counter("miss")).isEqualTo(1.0d);
            assertThat(totalOutcomes()).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("a write is counted")
        void writeIsCounted() {
            StepVerifier.create(cache().put(keyFor(ACME, "p"), response("a")))
                    .verifyComplete();

            assertThat(meters.find("gateway.cache.write")
                    .tag("tier", CacheTier.TIER1_EXACT.label())
                    .counter().count()).isEqualTo(1.0d);
        }
    }

    // ------------------------------------------------------------------
    // The TTL, which only a real server can show
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("expiry")
    class Expiry {

        @Test
        @DisplayName("the configured TTL is applied to the key")
        void ttlIsApplied() {
            CacheKey key = keyFor(ACME, "p");
            cache(true, Duration.ofMinutes(30)).put(key, response("a")).block(PATIENCE);

            long ttl = redis.getExpire(key.redisKey()).block(PATIENCE).toSeconds();

            // Zero would mean no expiry at all - see the note in TokenBudgetLedgerIT on
            // Spring Data normalising Redis's -1 sentinel. An entry with no TTL never
            // leaves the cache, so a stale answer could be served indefinitely.
            assertThat(ttl).isPositive().isLessThanOrEqualTo(1_800L);
            assertThat(ttl).isGreaterThan(1_700L);
        }

        @Test
        @DisplayName("an entry past its TTL is a miss, not a stale hit")
        void expiredEntryIsAMiss() throws InterruptedException {
            Tier1Cache cache = cache(true, Duration.ofSeconds(1));
            CacheKey key = keyFor(ACME, "p");

            cache.put(key, response("a")).block(PATIENCE);
            assertThat(cache.get(key).block(PATIENCE)).isNotNull();

            // A real second of real expiry. The only alternative is trusting that the
            // argument passed to a mock meant something to the server.
            Thread.sleep(1_200L);

            StepVerifier.create(cache.get(key)).verifyComplete();
            assertThat(counter("miss")).isEqualTo(1.0d);
        }
    }

    // ------------------------------------------------------------------
    // Keys and isolation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        @DisplayName("one tenant cannot read another's entry for the same prompt")
        void tenantsAreIsolated() {
            Tier1Cache cache = cache();
            String prompt = "how do I reset my password?";

            cache.put(keyFor(ACME, prompt), response("acme answer")).block(PATIENCE);

            // Identical prompt, identical model, different tenant. The tenant is hashed
            // into the key, so these are different keys - and if they were not, the
            // product would not be shippable at any scale.
            StepVerifier.create(cache.get(keyFor(GLOBEX, prompt))).verifyComplete();

            assertThat(cache.get(keyFor(ACME, prompt)).block(PATIENCE).content())
                    .isEqualTo("acme answer");
        }

        @Test
        @DisplayName("the key carries the tenant hash tag in the real keyspace")
        void keyIsHashTagged() {
            Tier1Cache cache = cache();
            cache.put(keyFor(ACME, "p"), response("a")).block(PATIENCE);

            List<String> keys = redis.keys("{tenant:acme}:c1:*")
                    .collectList()
                    .block(PATIENCE);

            // Puts the entry in the same Redis Cluster slot as that tenant's token bucket
            // and budget counter. Asserted against the live keyspace rather than against
            // CacheKey.redisKey(), so a change to either would have to change both.
            assertThat(keys).hasSize(1);
        }

        @Test
        @DisplayName("a different prompt is a different key")
        void differentPromptDifferentKey() {
            Tier1Cache cache = cache();
            cache.put(keyFor(ACME, "first"), response("one")).block(PATIENCE);

            StepVerifier.create(cache.get(keyFor(ACME, "second"))).verifyComplete();
        }
    }

    // ------------------------------------------------------------------
    // Eviction, corruption, and the disabled switch
    // ------------------------------------------------------------------

    @Test
    @DisplayName("evict removes the entry and reports whether there was one")
    void evict() {
        Tier1Cache cache = cache();
        CacheKey key = keyFor(ACME, "p");
        cache.put(key, response("a")).block(PATIENCE);

        assertThat(cache.evict(key).block(PATIENCE)).isTrue();
        StepVerifier.create(cache.get(key)).verifyComplete();
        // Nothing left to remove: false rather than an error, so the control plane's
        // per-tenant purge is idempotent.
        assertThat(cache.evict(key).block(PATIENCE)).isFalse();
    }

    @Test
    @DisplayName("a corrupt entry is an error and a miss, never a 500")
    void corruptEntryDegrades() {
        Tier1Cache cache = cache();
        CacheKey key = keyFor(ACME, "p");

        // Something else wrote over the key, or an older format left it unreadable.
        redis.opsForValue().set(key.redisKey(), "{not json at all").block(PATIENCE);

        StepVerifier.create(cache.get(key)).verifyComplete();

        // Counted as an error so it is visible on a dashboard, and the request still
        // falls through to upstream rather than failing.
        assertThat(counter("error")).isEqualTo(1.0d);
        assertThat(counter("hit")).isZero();
    }

    @Test
    @DisplayName("disabled means no reads, no writes and no keys")
    void disabledDoesNothing() {
        Tier1Cache cache = cache(false, Duration.ofHours(1));
        CacheKey key = keyFor(ACME, "p");

        StepVerifier.create(cache.put(key, response("a"))).verifyComplete();
        StepVerifier.create(cache.get(key)).verifyComplete();

        assertThat(redis.hasKey(key.redisKey()).block(PATIENCE)).isFalse();
        assertThat(totalOutcomes()).isZero();
    }
}
