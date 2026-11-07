package io.vasanth.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic behind the dashboard's headline numbers.
 *
 * <p>Worth its own test because every figure here has an edge case that renders badly
 * rather than failing: a division by zero becomes NaN and a NaN becomes a blank tile,
 * which is indistinguishable from a broken query.
 */
class UsageSummaryTest {

    private static final Instant FROM = Instant.parse("2026-11-06T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-07T00:00:00Z");

    private static UsageSummary summary(
            long requests, long hits, long prompt, long completion, long upstream) {
        return new UsageSummary(FROM, TO, requests, hits, prompt, completion, upstream);
    }

    @Test
    @DisplayName("tokens saved is metered tokens minus what reached a provider")
    void tokensSaved() {
        // 1,000 metered, 300 of which actually went upstream.
        UsageSummary s = summary(100, 70, 600, 400, 300);

        assertThat(s.totalTokens()).isEqualTo(1_000L);
        // The number that justifies the system existing. Computed from two
        // independently recorded columns rather than inferred from the hit ratio,
        // because a hit on a long prompt saves far more than a hit on a short one and
        // the ratio cannot see that.
        assertThat(s.tokensSaved()).isEqualTo(700L);
    }

    @Test
    @DisplayName("a saving can never be reported as negative")
    void savingIsClamped() {
        // upstream_tokens exceeding the metered total should be impossible, but a
        // replayed event or a mid-window schema change could produce it - and a
        // negative saving on a dashboard is worse than a zero, because it reads as the
        // cache costing money.
        assertThat(summary(10, 0, 100, 100, 5_000).tokensSaved()).isZero();
    }

    @Test
    @DisplayName("an empty window reports a zero hit ratio, not NaN")
    void emptyWindowDoesNotDivideByZero() {
        UsageSummary empty = UsageSummary.empty(FROM, TO);

        // 0/0 is NaN, NaN serialises as null or "NaN", and the tile renders blank -
        // which looks exactly like a failed query. A new tenant opening the dashboard
        // before their first request is the common case for this.
        assertThat(empty.hitRatio()).isZero();
        assertThat(empty.hitRatio()).isNotNaN();
        assertThat(empty.requests()).isZero();
        assertThat(empty.tokensSaved()).isZero();
        assertThat(empty.from()).isEqualTo(FROM);
        assertThat(empty.to()).isEqualTo(TO);
    }

    @Test
    @DisplayName("the hit ratio is a fraction, not a percentage")
    void hitRatioIsAFraction() {
        // 0-1, so the client formats it. Sending 70.0 and 0.70 from different
        // endpoints is how a dashboard ends up showing 7000%.
        assertThat(summary(100, 70, 0, 0, 0).hitRatio()).isEqualTo(0.70d);
        assertThat(summary(3, 1, 0, 0, 0).hitRatio()).isCloseTo(0.3333d, org.assertj.core.data.Offset.offset(1e-4));
        assertThat(summary(100, 100, 0, 0, 0).hitRatio()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("the money figure is tokens saved at the configured blended rate")
    void estimatedSaving() {
        // 2,000,000 tokens saved at $0.375 per million.
        UsageSummary s = summary(1_000, 700, 1_500_000, 1_500_000, 1_000_000);

        assertThat(s.tokensSaved()).isEqualTo(2_000_000L);
        assertThat(s.estimatedSavingUsd(0.375d)).isEqualTo(0.75d);
    }

    @Test
    @DisplayName("a zero rate yields a zero saving rather than an error")
    void zeroRate() {
        assertThat(summary(10, 10, 1_000, 1_000, 0).estimatedSavingUsd(0.0d)).isZero();
    }

    // ------------------------------------------------------------------
    // Pricing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unset price falls back to the documented default")
    void unsetPriceUsesDefault() {
        UsagePricing pricing = UsagePricing.defaults();

        // $0.15/1M input + $0.60/1M output at an even split = $0.375 blended, matching
        // docs/architecture/scaling-model.md.
        assertThat(pricing.rate()).isEqualTo(0.375d);
        assertThat(pricing.showsMoney()).isTrue();
    }

    @Test
    @DisplayName("an explicit zero price means 'show no money figure'")
    void explicitZeroIsDistinctFromUnset() {
        UsagePricing free = new UsagePricing(0.0d);

        // The whole reason the field is a boxed Double. A self-hosted model behind
        // OllamaProvider has no per-token cost, so a money figure there is meaningless
        // rather than merely unknown - and a primitive would make this
        // indistinguishable from "not configured".
        assertThat(free.showsMoney()).isFalse();
        assertThat(free.rate()).isZero();
        assertThat(UsagePricing.defaults().showsMoney()).isTrue();
    }

    @Test
    @DisplayName("a negative price is refused at startup")
    void negativePriceIsRefused() {
        assertThatThrownBy(() -> new UsagePricing(-1.0d))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
    }

    // ------------------------------------------------------------------
    // Points
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a point computes the same way as a summary")
    void pointArithmeticMatches() {
        UsagePoint point = new UsagePoint(FROM, 100, 70, 600, 400, 300);

        // Same derivation, so a chart and a tile cannot disagree about the same hour.
        assertThat(point.totalTokens()).isEqualTo(1_000L);
        assertThat(point.tokensSaved()).isEqualTo(700L);
        assertThat(point.hitRatio()).isEqualTo(0.70d);
    }

    @Test
    @DisplayName("an empty hour does not divide by zero either")
    void emptyPoint() {
        UsagePoint idle = new UsagePoint(FROM, 0, 0, 0, 0, 0);

        // An hour with no traffic is normal in a 24-hour window and must plot as zero,
        // not as a gap.
        assertThat(idle.hitRatio()).isZero();
        assertThat(idle.hitRatio()).isNotNaN();
        assertThat(idle.tokensSaved()).isZero();
    }
}
