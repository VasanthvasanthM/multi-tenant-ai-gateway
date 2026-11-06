package io.vasanth.gateway.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.micrometer.core.instrument.MeterRegistry;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Tier 1 - the exact-match Redis cache.
 *
 * <p>The behaviour under test is almost entirely about FAILING WELL. Redis is an
 * optimisation, not a source of truth, so every error path here has to degrade to a
 * miss and let the request reach the provider. A Tier-1 cache that propagates its own
 * failures turns a Redis blip into an outage, which is strictly worse than having no
 * cache at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Tier1CacheTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private MeterRegistry meters;
    private Tier1Cache cache;

    private static final CacheKey KEY = CacheKey.derive(CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("gpt-4o-mini"), "why is the sky blue?"));

    private static CompletionResponse upstreamResponse() {
        return new CompletionResponse(
                "Rayleigh scattering.",
                ModelId.of("gpt-4o-mini"),
                new TokenUsage(10L, 20L),
                CacheTier.UPSTREAM,
                ProviderId.of("mock"),
                Instant.parse("2026-11-06T12:00:00Z"),
                Duration.ofMillis(800));
    }

    private static GatewayProperties properties(boolean tier1Enabled) {
        return new GatewayProperties(
                new GatewayProperties.Cache(
                        tier1Enabled, Duration.ofHours(1), false, Duration.ofHours(24),
                        0.95, false),
                new GatewayProperties.RateLimit(true, 100, 20, true),
                new GatewayProperties.Limits(100, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
    }

    private void build(boolean tier1Enabled) {
        meters = new SimpleMeterRegistry();
        cache = new Tier1Cache(redis, mapper, properties(tier1Enabled), meters);
    }

    private double counter(String name, String result) {
        return Optional.ofNullable(meters.find(name)
                        .tag("tier", CacheTier.TIER1_EXACT.label())
                        .tag("result", result)
                        .counter())
                .map(io.micrometer.core.instrument.Counter::count)
                .orElse(0.0d);
    }

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        build(true);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a stored entry is returned and marked as a tier-1 hit")
    void returnsStoredEntry() throws Exception {
        String json = mapper.writeValueAsString(CachedCompletion.from(upstreamResponse()));
        when(valueOps.get(KEY.redisKey())).thenReturn(Mono.just(json));

        CompletionResponse found = cache.get(KEY).block();

        assertThat(found).isNotNull();
        assertThat(found.content()).isEqualTo("Rayleigh scattering.");
        // Re-tagged as TIER1_EXACT, not the UPSTREAM tier it was generated under, so the
        // caller can tell a cached answer from a fresh one.
        assertThat(found.servedBy()).isEqualTo(CacheTier.TIER1_EXACT);
        // Prompt tokens survive; completion tokens are ZERO. That asymmetry is
        // deliberate (see TokenUsage.cacheHit): the caller's input was still processed,
        // but no model generated anything, so charging for completion would make the
        // cache look worthless while reporting NONE would make the saving invisible.
        // This is what the "tokens saved" figure is computed from.
        assertThat(found.usage().promptTokens()).isEqualTo(10L);
        assertThat(found.usage().completionTokens()).isZero();
        assertThat(counter("gateway.cache.lookup", "hit")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an absent key is an empty Mono and counts as a miss")
    void absentKeyIsAMiss() {
        when(valueOps.get(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(cache.get(KEY)).verifyComplete();

        assertThat(counter("gateway.cache.lookup", "miss")).isEqualTo(1.0d);
        assertThat(counter("gateway.cache.lookup", "hit")).isZero();
    }

    @Test
    @DisplayName("a Redis failure degrades to a miss rather than propagating")
    void redisFailureDegradesToMiss() {
        when(valueOps.get(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("connection reset")));

        // Empty, NOT an error. Propagating would mean a Redis blip fails requests the
        // provider could have served perfectly well.
        StepVerifier.create(cache.get(KEY)).verifyComplete();

        assertThat(counter("gateway.cache.lookup", "error")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("corrupt JSON counts as an error and still serves the request")
    void corruptEntryDegradesToMiss() {
        when(valueOps.get(anyString())).thenReturn(Mono.just("{this is not json"));

        StepVerifier.create(cache.get(KEY)).verifyComplete();

        // Counted as an error so it is visible on a dashboard, but it must not fail the
        // request: the provider can still answer.
        assertThat(counter("gateway.cache.lookup", "error")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an entry from an older schema is treated as a miss, not an error")
    void unreadableEntryIsAMiss() throws Exception {
        // schemaVersion 0 rather than CURRENT_SCHEMA_VERSION: exactly what an entry
        // written by the previous release looks like during a rolling deploy.
        String stale = mapper.writeValueAsString(new CachedCompletion(
                0, "old content", "gpt-4o-mini", 1L, 2L, "mock",
                Instant.parse("2026-11-06T11:00:00Z").toEpochMilli()));
        when(valueOps.get(anyString())).thenReturn(Mono.just(stale));

        StepVerifier.create(cache.get(KEY)).verifyComplete();

        // A miss rather than an error: the format is allowed to evolve without a
        // coordinated cache flush, so this is expected during a rollout.
        assertThat(counter("gateway.cache.lookup", "miss")).isEqualTo(1.0d);
        assertThat(counter("gateway.cache.lookup", "error")).isZero();
    }

    @Test
    @DisplayName("when tier 1 is disabled, Redis is never touched")
    void disabledSkipsRedisEntirely() {
        build(false);

        StepVerifier.create(cache.get(KEY)).verifyComplete();

        // Not merely "returns empty" - it must not issue the call at all, or a disabled
        // cache still costs a round trip on every request.
        verify(valueOps, never()).get(anyString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lookupOutcomes")
    @DisplayName("every lookup increments exactly ONE of hit/miss/error")
    void exactlyOneCounterPerLookup(String scenario, Mono<String> redisResult) {
        when(valueOps.get(anyString())).thenReturn(redisResult);

        cache.get(KEY).onErrorResume(error -> Mono.empty()).block();

        double total = counter("gateway.cache.lookup", "hit")
                + counter("gateway.cache.lookup", "miss")
                + counter("gateway.cache.lookup", "error");

        // The invariant the dashboard depends on. Hit ratio is
        // hits / (hits + misses + errors), so a lookup counted twice silently
        // deflates the headline number of the entire product - and it does so worst
        // during a rolling deploy, when stale-schema entries are most common.
        //
        // This failed before the fix: an unreadable entry scored two misses, because
        // deserialize() counted it and the outer doOnSuccess counted the resulting
        // empty Mono again.
        assertThat(total)
                .as("scenario '%s' must count exactly one outcome", scenario)
                .isEqualTo(1.0d);
    }

    private static java.util.stream.Stream<Arguments> lookupOutcomes() throws Exception {
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        String hit = m.writeValueAsString(CachedCompletion.from(upstreamResponse()));
        String stale = m.writeValueAsString(new CachedCompletion(
                0, "old", "gpt-4o-mini", 1L, 2L, "mock", 0L));
        return java.util.stream.Stream.of(
                Arguments.of("hit", Mono.just(hit)),
                Arguments.of("absent", Mono.<String>empty()),
                Arguments.of("stale schema", Mono.just(stale)),
                Arguments.of("corrupt json", Mono.just("{not json")),
                Arguments.of("redis error",
                        Mono.<String>error(new IllegalStateException("connection reset"))));
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a write stores serialised JSON under the tenant-scoped key with a TTL")
    void writeStoresWithTtl() {
        when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.just(true));

        StepVerifier.create(cache.put(KEY, upstreamResponse())).verifyComplete();

        verify(valueOps).set(eq(KEY.redisKey()), anyString(), eq(Duration.ofHours(1)));
        assertThat(meters.find("gateway.cache.write")
                .tag("tier", CacheTier.TIER1_EXACT.label()).counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the key is tenant-scoped and cluster hash-tagged")
    void keyIsTenantScoped() {
        // The hash tag keeps one tenant's keys in one Redis Cluster slot, and the tenant
        // prefix is what makes cross-tenant reads impossible by construction.
        assertThat(KEY.redisKey()).startsWith("{tenant:acme}:c1:");
    }

    @Test
    @DisplayName("a failed write is swallowed - the response is already being served")
    void failedWriteIsSwallowed() {
        when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("OOM command not allowed")));

        // The upstream call already succeeded and the user is getting their answer.
        // Failing here would discard a paid-for response to report a cache problem.
        StepVerifier.create(cache.put(KEY, upstreamResponse())).verifyComplete();
    }

    @Test
    @DisplayName("when tier 1 is disabled, a write is a no-op")
    void disabledWriteIsNoOp() {
        build(false);

        StepVerifier.create(cache.put(KEY, upstreamResponse())).verifyComplete();

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    // ------------------------------------------------------------------
    // Eviction
    // ------------------------------------------------------------------

    @Test
    @DisplayName("eviction reports whether a key was removed")
    void evictReportsOutcome() {
        when(valueOps.delete(KEY.redisKey())).thenReturn(Mono.just(true));

        assertThat(cache.evict(KEY).block()).isTrue();
    }

    @Test
    @DisplayName("a failed eviction reports false rather than erroring")
    void failedEvictReportsFalse() {
        when(valueOps.delete(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("no connection")));

        // false, not an error: a caller asking to evict wants to know it did not happen,
        // and cannot do anything useful with a Redis exception.
        assertThat(cache.evict(KEY).block()).isFalse();
    }

    @Test
    @DisplayName("eviction runs even when tier 1 is disabled")
    void evictIgnoresTheEnabledFlag() {
        build(false);
        when(valueOps.delete(anyString())).thenReturn(Mono.just(true));

        // Deliberate: entries written while the tier was enabled still need removing
        // after it is switched off, or a later re-enable serves stale content.
        assertThat(cache.evict(KEY).block()).isTrue();
        verify(valueOps).delete(KEY.redisKey());
    }
}
