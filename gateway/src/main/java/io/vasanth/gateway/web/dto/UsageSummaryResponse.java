package io.vasanth.gateway.web.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.vasanth.gateway.usage.UsagePricing;
import io.vasanth.gateway.usage.UsageSummary;

/**
 * GET /v1/usage/summary.
 *
 * <p>snake_case on the wire, matching the completions API, so a client does not have to
 * switch naming conventions between two endpoints of the same service.
 *
 * <p>The derived figures - hit ratio, tokens saved, money saved - are computed SERVER
 * SIDE and sent, rather than left for the client. Two reasons: the arithmetic has edge
 * cases (an empty window divides by zero; a replayed event could make the saving
 * negative) and a second implementation in TypeScript is a second place to get them
 * wrong; and the money figure depends on a configured price the client has no business
 * knowing.
 *
 * <p>A top-level record rather than a nested one inside a holder class. The holder had a
 * private constructor and no static members of its own, which PMD flags as
 * {@code MissingStaticMethodInNonInstantiatableClass} - correctly: a class that cannot
 * be instantiated and offers nothing statically is a namespace pretending to be a type.
 *
 * @param estimatedSavingUsd absent when no price is configured. A self-hosted model has
 *     no per-token cost, so a money figure there is meaningless rather than merely
 *     unknown - and omitting the field lets the UI hide the tile instead of rendering a
 *     confident $0.00
 */
public record UsageSummaryResponse(
        @JsonProperty("tenant_id") String tenantId,
        Instant from,
        Instant to,
        @JsonProperty("window_hours") int windowHours,
        long requests,
        @JsonProperty("cache_hits") long cacheHits,
        @JsonProperty("hit_ratio") double hitRatio,
        @JsonProperty("prompt_tokens") long promptTokens,
        @JsonProperty("completion_tokens") long completionTokens,
        @JsonProperty("total_tokens") long totalTokens,
        @JsonProperty("upstream_tokens") long upstreamTokens,
        @JsonProperty("tokens_saved") long tokensSaved,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("estimated_saving_usd") Double estimatedSavingUsd) {

    public static UsageSummaryResponse from(
            String tenantId, int windowHours, UsageSummary summary, UsagePricing pricing) {

        return new UsageSummaryResponse(
                tenantId,
                summary.from(),
                summary.to(),
                windowHours,
                summary.requests(),
                summary.cacheHits(),
                summary.hitRatio(),
                summary.promptTokens(),
                summary.completionTokens(),
                summary.totalTokens(),
                summary.upstreamTokens(),
                summary.tokensSaved(),
                pricing.showsMoney()
                        ? roundMoney(summary.estimatedSavingUsd(pricing.rate()))
                        : null);
    }

    /**
     * Rounds a money figure to six decimal places.
     *
     * <p>Binary floating point cannot represent most decimal fractions, so the raw value
     * of 700,000 tokens at $0.375/M serialises as {@code 0.26249999999999996}. That is
     * correct to the limit of a double and unacceptable in an API response: a client
     * either renders it verbatim or has to decide its own precision, and two clients
     * would then disagree about the same figure.
     *
     * <p>Six places, not two: at these rates a day of a small tenant's traffic is worth
     * fractions of a cent, and rounding to cents would show $0.00 for a real saving.
     */
    private static double roundMoney(double usd) {
        return BigDecimal.valueOf(usd).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
