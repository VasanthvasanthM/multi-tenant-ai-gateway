package io.vasanth.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Turns a {@link RateLimitDecision} into a pass or an error signal.
 *
 * <p>A thin layer, but it keeps two concerns apart: {@link TokenBucketRateLimiter} answers
 * "is there a token?", and this decides what refusal means to a request. That separation
 * is what lets the limiter be tested against real Redis without dragging in the error
 * taxonomy, and lets this be tested without Redis at all.
 */
@Component
public class TokenBucketGate {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketGate.class);

    private final TokenBucketRateLimiter limiter;
    private final Counter rejected;
    private final Counter degraded;

    public TokenBucketGate(TokenBucketRateLimiter limiter, MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.rejected = Counter.builder("gateway.requests.rejected")
                .tag("reason", "rate_limited")
                .register(meterRegistry);
        // Tracked separately from rejections because this is an incident, not normal
        // operation: it means the limiter could not reach Redis and the configured
        // fail-open policy let traffic through unchecked. Without its own counter that
        // fact is invisible on a dashboard.
        this.degraded = Counter.builder("gateway.ratelimit.degraded")
                .register(meterRegistry);
    }

    /**
     * Consumes one token, or fails the request.
     *
     * @return an empty {@link Mono} when allowed, or an error carrying
     *     {@link GatewayError.RateLimited}
     */
    public Mono<Void> check(TenantId tenant) {
        return limiter.tryAcquire(tenant)
                .flatMap(decision -> {
                    if (decision.degraded()) {
                        degraded.increment();
                        log.warn("rate limiting degraded for tenant {}: limiter unavailable",
                                tenant);
                    }
                    if (decision.allowed()) {
                        return Mono.empty();
                    }
                    rejected.increment();
                    return Mono.<Void>error(new GatewayException(
                            new GatewayError.RateLimited(decision.retryAfter())));
                })
                .then();
    }
}
