package io.vasanth.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.config.GatewayProperties;
import reactor.core.publisher.Mono;

/**
 * Per-tenant token bucket, evaluated atomically in Redis.
 *
 * <p>All the interesting logic is in {@code scripts/token_bucket.lua}; this class is the
 * typed wrapper and the failure policy. See that script for why the computation cannot live
 * here in Java.
 */
@Component
// Spring Data's RedisScript has no generic form for a heterogeneous multi-bulk
// reply, so the raw List is imposed by the API rather than chosen here.
@SuppressWarnings("rawtypes")
public class TokenBucketRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /**
     * Idle buckets expire after an hour. Long enough that a tenant with sparse traffic is
     * not constantly re-initialised to full, short enough that a million one-off tenants do
     * not permanently occupy Redis memory. Expiry is safe because a missing bucket is
     * recreated full, which is the same state an hour of refilling would have produced.
     */
    private static final long BUCKET_TTL_SECONDS = 3_600L;

    /** Backoff advertised when the limiter itself is broken and configured to fail closed. */
    private static final Duration UNAVAILABLE_BACKOFF = Duration.ofSeconds(1);

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<List> tokenBucketScript;
    private final GatewayProperties.RateLimit config;

    public TokenBucketRateLimiter(
            ReactiveStringRedisTemplate redis,
            RedisScript<List> tokenBucketScript,
            GatewayProperties properties) {
        this.redis = redis;
        this.tokenBucketScript = tokenBucketScript;
        this.config = properties.rateLimit();
    }

    /**
     * Attempts to consume one token for {@code tenant}.
     *
     * <p>Never signals an error. A limiter that propagates Redis failures would turn a
     * degraded dependency into a total outage, which inverts the point of having a limiter
     * at all - so infrastructure failure resolves to the configured fail-open or fail-closed
     * policy instead.
     */
    public Mono<RateLimitDecision> tryAcquire(TenantId tenant) {
        return tryAcquire(tenant, 1);
    }

    /**
     * Attempts to consume {@code tokens} for {@code tenant}.
     *
     * @param tokens cost of this request; lets an expensive call draw down more than one
     */
    public Mono<RateLimitDecision> tryAcquire(TenantId tenant, int tokens) {
        if (!config.enabled()) {
            return Mono.just(RateLimitDecision.disabled());
        }
        if (tokens < 1) {
            return Mono.error(new IllegalArgumentException("tokens must be at least 1"));
        }

        String key = bucketKey(tenant);

        return redis.execute(
                        tokenBucketScript,
                        List.of(key),
                        List.of(
                                Integer.toString(config.capacity()),
                                Integer.toString(config.refillPerSecond()),
                                Integer.toString(tokens),
                                Long.toString(BUCKET_TTL_SECONDS)))
                .next()
                .map(TokenBucketRateLimiter::toDecision)
                .onErrorResume(error -> Mono.just(onLimiterUnavailable(tenant, error)));
    }

    /**
     * Redis key for a tenant's bucket.
     *
     * <p>Shares the tenant's cluster hash tag with its cache entries, so both live in the
     * same slot. That is required for the Lua script to be legal in Redis Cluster mode,
     * where a script may only touch keys in one slot.
     */
    private static String bucketKey(TenantId tenant) {
        return tenant.redisHashTag() + ":rl";
    }

    private static RateLimitDecision toDecision(List result) {
        // The script returns { allowed, remaining, retryAfterMillis }.
        long allowed = ((Number) result.get(0)).longValue();
        long remaining = ((Number) result.get(1)).longValue();
        long retryAfterMillis = ((Number) result.get(2)).longValue();

        return allowed == 1L
                ? RateLimitDecision.allowed(remaining)
                : RateLimitDecision.denied(remaining, Duration.ofMillis(retryAfterMillis));
    }

    private RateLimitDecision onLimiterUnavailable(TenantId tenant, Throwable error) {
        // Logged at WARN, not ERROR: the gateway is still serving. An operator needs to see
        // this, but it should not page as though traffic had stopped.
        log.warn(
                "rate limiter unavailable for tenant {}, failing {}: {}",
                tenant,
                config.failOpen() ? "open" : "closed",
                error.toString());

        // Fail-open trades enforcement for availability. It is the right default for a
        // gateway whose job is to keep serving - a Redis blip should not take the product
        // down - but it does mean a Redis outage temporarily removes abuse protection. A
        // deployment that cares more about cost control than uptime should set
        // gateway.rate-limit.fail-open=false. The `degraded` flag makes either choice
        // visible on the dashboard rather than silent.
        return config.failOpen()
                ? RateLimitDecision.failedOpen()
                : RateLimitDecision.failedClosed(UNAVAILABLE_BACKOFF);
    }
}
