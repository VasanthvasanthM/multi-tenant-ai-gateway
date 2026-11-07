package io.vasanth.gateway.usage;

import java.time.Instant;

/**
 * A tenant's usage over a window, aggregated from {@code tenant_usage_rollup}.
 *
 * <p>Every figure here is derived from columns the metering service writes; nothing is
 * estimated except {@link #estimatedSavingUsd(double)}, which is explicitly a
 * calculation from a configured price rather than a measurement.
 *
 * @param requests requests in the window
 * @param cacheHits requests served from either cache tier
 * @param promptTokens input tokens the tenant is metered for
 * @param completionTokens output tokens the tenant is metered for
 * @param upstreamTokens tokens that actually reached a provider. The metering service
 *     records the request total when the call was billable upstream and ZERO on a cache
 *     hit, which is what makes the saving below computable at all
 */
public record UsageSummary(
        Instant from,
        Instant to,
        long requests,
        long cacheHits,
        long promptTokens,
        long completionTokens,
        long upstreamTokens) {

    /** An empty window - a tenant with no traffic, not an error. */
    public static UsageSummary empty(Instant from, Instant to) {
        return new UsageSummary(from, to, 0L, 0L, 0L, 0L, 0L);
    }

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * Tokens the cache avoided paying for.
     *
     * <p>Metered tokens minus the tokens that actually reached a provider. This is the
     * one number on the dashboard that justifies the system existing, so it is computed
     * from two independently recorded columns rather than inferred from the hit ratio -
     * a hit on a long prompt saves far more than a hit on a short one, and the ratio
     * cannot see that.
     */
    public long tokensSaved() {
        // clamped: upstream_tokens should never exceed the metered total, but a
        // mid-window schema change or a replayed event could make it so, and a
        // NEGATIVE saving on a dashboard is worse than a zero.
        return Math.max(0L, totalTokens() - upstreamTokens);
    }

    /**
     * Fraction of requests served from cache, in {@code [0,1]}.
     *
     * <p>Returns 0 for an empty window rather than NaN. A dashboard rendering NaN shows
     * a blank tile, which is indistinguishable from a broken query.
     */
    public double hitRatio() {
        return requests == 0L ? 0.0d : divide(cacheHits, requests);
    }

    /**
     * Estimated money the cache avoided spending, at a blended per-token price.
     *
     * <p>A CALCULATION, not a measurement, and the dashboard labels it as one. The real
     * figure depends on the provider, the model and the input/output split, none of
     * which the rollup records - it keeps one blended rate rather than pretending to a
     * precision it does not have.
     */
    public double estimatedSavingUsd(double usdPerMillionTokens) {
        return tokensSaved() / 1_000_000.0d * usdPerMillionTokens;
    }

    /**
     * Floating-point division of two counts.
     *
     * <p>The parameters are {@code double}, so the longs widen at the call site
     * and there is no cast to get wrong. Written this way because PMD flags
     * {@code (double) a / (double) b} as an unnecessary cast - and removing the
     * casts it objects to would give INTEGER division, so 70 hits in 100
     * requests would report a ratio of 0.
     */
    private static double divide(double numerator, double denominator) {
        return numerator / denominator;
    }
}
