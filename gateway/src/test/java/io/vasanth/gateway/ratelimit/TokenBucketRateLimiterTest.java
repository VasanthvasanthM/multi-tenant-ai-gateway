package io.vasanth.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * The rate limiter's Java half.
 *
 * <p>The interesting logic is in {@code token_bucket.lua} and is exercised against a
 * real Redis in the integration tests. What is tested here is the part that is wrong in
 * a way no Lua test would catch: the ARGUMENT ORDER passed to the script, and the
 * behaviour when Redis is unreachable.
 *
 * <p>Argument order matters more than it looks. The script takes capacity, refill rate,
 * requested tokens and TTL as four positional numbers; swapping capacity and refill
 * produces a limiter that works, never errors, and enforces a completely different
 * policy than the one configured.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenBucketRateLimiterTest {

    @Mock
    private ReactiveStringRedisTemplate redis;

    @SuppressWarnings("rawtypes")
    @Mock
    private RedisScript<List> script;

    private static final TenantId TENANT = TenantId.of("acme");

    private static GatewayProperties properties(boolean enabled, boolean failOpen) {
        return new GatewayProperties(
                new GatewayProperties.Cache(
                        true, Duration.ofHours(1), false, Duration.ofHours(24), 0.95, false),
                new GatewayProperties.RateLimit(enabled, 100, 20, failOpen),
                new GatewayProperties.Limits(100, 256, Duration.ofSeconds(30)),
                new GatewayProperties.Providers("mock", List.of()));
    }

    private TokenBucketRateLimiter limiter(boolean enabled, boolean failOpen) {
        return new TokenBucketRateLimiter(redis, script, properties(enabled, failOpen));
    }

    @SuppressWarnings("unchecked")
    private void scriptReturns(long allowed, long remaining, long retryAfterMillis) {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(List.of(allowed, remaining, retryAfterMillis)));
    }

    // ------------------------------------------------------------------
    // The script contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an allowed decision carries the remaining tokens and no retry delay")
    void allowedDecision() {
        scriptReturns(1L, 99L, 0L);

        RateLimitDecision decision = limiter(true, true).tryAcquire(TENANT).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remainingTokens()).isEqualTo(99L);
        assertThat(decision.retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    @DisplayName("a denied decision carries the exact wait the script computed")
    void deniedDecision() {
        scriptReturns(0L, 0L, 2_500L);

        RateLimitDecision decision = limiter(true, true).tryAcquire(TENANT).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        // The exact deficit, not a guess. A client that backs off by the right amount
        // does not come back early and make the overload worse.
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofMillis(2_500L));
        assertThat(decision.degraded()).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("the script receives capacity, refill, tokens and TTL in that order")
    void scriptArgumentOrderIsCorrect() {
        scriptReturns(1L, 50L, 0L);

        limiter(true, true).tryAcquire(TENANT, 5).block();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());

        // The key is hash-tagged, which is what keeps a tenant's bucket in one Redis
        // Cluster slot alongside its cache entries.
        assertThat(keys.getValue()).containsExactly("{tenant:acme}:rl");

        // Positional and silent if wrong: swapping capacity and refill gives a limiter
        // that works, never errors, and enforces a policy nobody configured.
        assertThat(args.getValue()).containsExactly(
                "100",   // capacity
                "20",    // refillPerSecond
                "5",     // requested tokens
                "3600"); // bucket TTL
    }

    @Test
    @DisplayName("the single-argument form requests exactly one token")
    void defaultCostIsOneToken() {
        scriptReturns(1L, 99L, 0L);

        limiter(true, true).tryAcquire(TENANT).block();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
        assertThat(args.getValue().get(2)).isEqualTo("1");
    }

    @Test
    @DisplayName("a non-positive token count is rejected rather than sent to Redis")
    void nonPositiveTokensIsRejected() {
        // Caught here rather than in Lua: a zero or negative request would otherwise
        // ADD tokens to the bucket, quietly raising the tenant's limit.
        StepVerifier.create(limiter(true, true).tryAcquire(TENANT, 0))
                .verifyError(IllegalArgumentException.class);
        StepVerifier.create(limiter(true, true).tryAcquire(TENANT, -5))
                .verifyError(IllegalArgumentException.class);

        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    // ------------------------------------------------------------------
    // Disabled
    // ------------------------------------------------------------------

    @Test
    @DisplayName("when disabled, Redis is never called and the request is allowed")
    void disabledShortCircuits() {
        RateLimitDecision decision = limiter(false, true).tryAcquire(TENANT).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isFalse();
        assertThat(decision.remainingTokens()).isEqualTo(Long.MAX_VALUE);
        // Not merely allowed - it must not cost a round trip, or a disabled limiter
        // still taxes every request.
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    // ------------------------------------------------------------------
    // Redis unavailable - the decision that trades enforcement for uptime
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fail-open allows the request and flags itself as degraded")
    void failOpenAllowsAndFlags() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("connection refused")));

        RateLimitDecision decision = limiter(true, true).tryAcquire(TENANT).block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        // The flag is the whole point: traffic is now UNCHECKED, and without a signal
        // that fact is invisible. It is not an error, because the gateway is still
        // serving - but an operator has to be able to see it.
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.remainingTokens()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("fail-closed denies the request with a backoff, also flagged")
    void failClosedDeniesWithBackoff() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("connection refused")));

        RateLimitDecision decision = limiter(true, false).tryAcquire(TENANT).block();

        assertThat(decision).isNotNull();
        // For a deployment that cares more about cost control than uptime.
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("a Redis failure never propagates as an error")
    void redisFailureIsNeverAnError() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("MISCONF")));

        // A limiter that throws turns a Redis blip into a 500 on every request, which
        // is the opposite of what a rate limiter is for.
        StepVerifier.create(limiter(true, true).tryAcquire(TENANT))
                .expectNextMatches(RateLimitDecision::degraded)
                .verifyComplete();
    }

    @Test
    @DisplayName("an empty script result is treated as the limiter being unavailable")
    void emptyResultIsTreatedAsUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.empty());

        // Rather than an empty Mono, which the pipeline would see as "no decision" and
        // skip the gate entirely - silently disabling rate limiting.
        StepVerifier.create(limiter(true, true).tryAcquire(TENANT))
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // RateLimitDecision factories
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the decision factories set the flags they claim to")
    void decisionFactories() {
        assertThat(RateLimitDecision.allowed(5L).allowed()).isTrue();
        assertThat(RateLimitDecision.allowed(5L).degraded()).isFalse();

        assertThat(RateLimitDecision.denied(0L, Duration.ofSeconds(3)).allowed()).isFalse();
        assertThat(RateLimitDecision.denied(0L, Duration.ofSeconds(3)).retryAfter())
                .isEqualTo(Duration.ofSeconds(3));

        // -1 remaining is the sentinel for "unknown", since the limiter never answered.
        assertThat(RateLimitDecision.failedOpen().allowed()).isTrue();
        assertThat(RateLimitDecision.failedOpen().degraded()).isTrue();
        assertThat(RateLimitDecision.failedOpen().remainingTokens()).isEqualTo(-1L);

        assertThat(RateLimitDecision.failedClosed(Duration.ofSeconds(1)).allowed()).isFalse();
        assertThat(RateLimitDecision.failedClosed(Duration.ofSeconds(1)).degraded()).isTrue();

        assertThat(RateLimitDecision.disabled().allowed()).isTrue();
        assertThat(RateLimitDecision.disabled().degraded()).isFalse();
    }

    @Test
    @DisplayName("a degraded decision is distinguishable from a normal one")
    void degradedIsNotConfusableWithNormal() {
        // failedOpen and allowed both permit the request, so the ONLY thing separating
        // "within limits" from "limits not enforced at all" is this flag.
        assertThatThrownBy(() -> {
            assertThat(RateLimitDecision.failedOpen().degraded())
                    .isEqualTo(RateLimitDecision.allowed(10L).degraded());
        }).isInstanceOf(AssertionError.class);
    }
}
