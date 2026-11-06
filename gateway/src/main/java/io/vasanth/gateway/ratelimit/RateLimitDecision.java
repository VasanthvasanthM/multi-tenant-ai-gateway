package io.vasanth.gateway.ratelimit;

import java.time.Duration;

/**
 * Outcome of a rate-limit check.
 *
 * @param allowed whether the request may proceed
 * @param remainingTokens tokens left in the bucket, surfaced as {@code X-RateLimit-Remaining}
 * @param retryAfter how long until the request would succeed; {@link Duration#ZERO} when
 *     allowed. Surfaced as the {@code Retry-After} header so a client backs off by the
 *     correct amount rather than guessing and worsening the overload
 * @param degraded true when the limiter could not reach Redis and the configured fail-open
 *     policy let the request through. Separated from {@code allowed} because an operator
 *     needs to distinguish "within limit" from "limit not enforced right now" - the second
 *     is an incident, and without this flag it is invisible
 */
public record RateLimitDecision(
        boolean allowed, long remainingTokens, Duration retryAfter, boolean degraded) {

    public static RateLimitDecision allowed(long remainingTokens) {
        return new RateLimitDecision(true, remainingTokens, Duration.ZERO, false);
    }

    public static RateLimitDecision denied(long remainingTokens, Duration retryAfter) {
        return new RateLimitDecision(false, remainingTokens, retryAfter, false);
    }

    /** Redis was unreachable and {@code failOpen} is configured. */
    public static RateLimitDecision failedOpen() {
        return new RateLimitDecision(true, -1L, Duration.ZERO, true);
    }

    /** Redis was unreachable and {@code failOpen} is disabled. */
    public static RateLimitDecision failedClosed(Duration retryAfter) {
        return new RateLimitDecision(false, 0L, retryAfter, true);
    }

    /** Limiting is switched off entirely. */
    public static RateLimitDecision disabled() {
        return new RateLimitDecision(true, Long.MAX_VALUE, Duration.ZERO, false);
    }
}
