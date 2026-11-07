package io.vasanth.gateway.web.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.vasanth.gateway.usage.UsagePoint;

/**
 * GET /v1/usage/series.
 *
 * @param points OLDEST FIRST, so a chart can plot them without sorting and two clients
 *     cannot disagree about the direction
 */
public record UsageSeriesResponse(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("window_hours") int windowHours,
        List<Point> points) {

    public static UsageSeriesResponse from(
            String tenantId, int windowHours, List<UsagePoint> points) {

        return new UsageSeriesResponse(
                tenantId, windowHours, points.stream().map(Point::from).toList());
    }

    /** One hour. */
    public record Point(
            Instant hour,
            long requests,
            @JsonProperty("cache_hits") long cacheHits,
            @JsonProperty("hit_ratio") double hitRatio,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("upstream_tokens") long upstreamTokens,
            @JsonProperty("tokens_saved") long tokensSaved) {

        static Point from(UsagePoint point) {
            return new Point(
                    point.hour(),
                    point.requests(),
                    point.cacheHits(),
                    point.hitRatio(),
                    point.totalTokens(),
                    point.upstreamTokens(),
                    point.tokensSaved());
        }
    }
}
