package io.vasanth.gateway.usage;

import java.time.Instant;

/**
 * One hour of a tenant's usage - a single row of {@code tenant_usage_rollup}.
 *
 * <p>Hourly is the finest granularity available, because that is the period the
 * metering service aggregates into. A dashboard asking for minutes would have to query
 * {@code usage_ledger} directly, which is one row per request - at the volumes in
 * scaling-model.md that is a table scan nobody should put behind a web request.
 *
 * @param hour the start of the hour, UTC
 */
public record UsagePoint(
        Instant hour,
        long requests,
        long cacheHits,
        long promptTokens,
        long completionTokens,
        long upstreamTokens) {

    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    public long tokensSaved() {
        return Math.max(0L, totalTokens() - upstreamTokens);
    }

    public double hitRatio() {
        return requests == 0L ? 0.0d : divide(cacheHits, requests);
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
