package io.vasanth.gateway.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All gateway tuning, bound and validated at startup.
 *
 * <p>Validation is deliberate: a gateway that boots with a nonsensical limit and then
 * misbehaves under load is far harder to diagnose than one that refuses to start and names
 * the offending property. Every constraint below exists because the wrong value produces a
 * subtle failure rather than an obvious one.
 *
 * @param cache Tier-1 and Tier-2 cache behaviour
 * @param rateLimit per-tenant token bucket
 * @param limits request size and concurrency guards
 * @param providers upstream selection and fallback order
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @Valid @NotNull Cache cache,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull Limits limits,
        @Valid @NotNull Providers providers) {

    /**
     * @param tier1Enabled exact-hash Redis lookup
     * @param tier1Ttl how long an exact entry survives
     * @param tier2Enabled pgvector semantic lookup
     * @param tier2Ttl how long a semantic entry survives
     * @param similarityThreshold minimum cosine similarity to count as a semantic hit
     * @param shadowMode when true, Tier 2 is queried and the result logged but never served
     */
    public record Cache(
            boolean tier1Enabled,
            @NotNull Duration tier1Ttl,
            boolean tier2Enabled,
            @NotNull Duration tier2Ttl,
            @Min(0) @Max(1) double similarityThreshold,
            boolean shadowMode) {

        public Cache {
            if (tier1Ttl.isNegative() || tier1Ttl.isZero()) {
                throw new IllegalArgumentException("tier1Ttl must be positive: " + tier1Ttl);
            }
            if (tier2Ttl.isNegative() || tier2Ttl.isZero()) {
                throw new IllegalArgumentException("tier2Ttl must be positive: " + tier2Ttl);
            }
            // A low threshold is the single most dangerous misconfiguration available: at
            // 0.80, "how do I cancel my order" and "how do I cancel my subscription" are
            // close enough to match, and the cache starts confidently returning the wrong
            // answer. Refusing to boot is kinder than discovering this in production.
            if (similarityThreshold < 0.90d) {
                throw new IllegalArgumentException(
                        "similarityThreshold below 0.90 is unsafe for a semantic cache; got "
                                + similarityThreshold
                                + ". Enable shadowMode and measure the false-hit rate before "
                                + "lowering it.");
            }
        }
    }

    /**
     * Token bucket, not leaky bucket.
     *
     * <p>A leaky bucket enforces a strictly uniform rate, which punishes the bursty traffic
     * real API clients actually produce - a batch job firing 50 requests then idling for a
     * minute is well within its average but fails a leaky bucket. A token bucket permits the
     * burst while still bounding the long-run rate.
     *
     * @param enabled whether limiting is applied at all
     * @param capacity burst size: the most requests allowed back to back
     * @param refillPerSecond sustained rate
     * @param failOpen whether to allow traffic when Redis is unreachable
     */
    public record RateLimit(
            boolean enabled,
            @Min(1) int capacity,
            @Min(1) int refillPerSecond,
            boolean failOpen) {

        public RateLimit {
            if (capacity < refillPerSecond) {
                throw new IllegalArgumentException(
                        "capacity (" + capacity + ") below refillPerSecond (" + refillPerSecond
                                + ") caps the sustained rate below its configured value");
            }
        }
    }

    /**
     * @param maxPromptCodePoints prompt size cap, counted in code points rather than UTF-16
     *     units so that a limit means the same thing for Japanese or emoji-bearing text as
     *     for ASCII
     * @param maxInFlight concurrent upstream calls before load is shed
     * @param upstreamTimeout per-attempt upstream deadline
     */
    public record Limits(
            @Min(1) int maxPromptCodePoints,
            @Min(1) int maxInFlight,
            @NotNull Duration upstreamTimeout) {
    }

    /**
     * @param primary provider id attempted first
     * @param fallback ordered providers tried when the primary's circuit is open; may be
     *     empty, in which case an open circuit means a 503
     */
    public record Providers(@NotEmpty String primary, @NotNull List<String> fallback) {

        public Providers {
            fallback = List.copyOf(fallback);
            if (fallback.contains(primary)) {
                throw new IllegalArgumentException(
                        "provider '" + primary + "' is both primary and fallback; retrying the "
                                + "same provider whose circuit just opened cannot help");
            }
        }
    }
}
