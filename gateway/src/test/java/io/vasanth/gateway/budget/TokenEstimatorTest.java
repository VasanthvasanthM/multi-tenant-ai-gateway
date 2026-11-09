package io.vasanth.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The streaming token estimate.
 *
 * <p>Small and arithmetic, and worth pinning because the rounding DIRECTION is a policy
 * decision rather than a detail: for a budget, over-counting makes a tenant hit their limit
 * slightly early while under-counting lets them exceed it. A change to plain integer
 * division would look like a tidy-up and would quietly make the control leak.
 */
class TokenEstimatorTest {

    @ParameterizedTest(name = "{0} characters -> {1} tokens")
    @CsvSource({
            "0, 0",
            "1, 1",
            "3, 1",
            "4, 1",
            "5, 2",
            "8, 2",
            "9, 3",
            "400, 100",
            "401, 101",
    })
    @DisplayName("characters convert at 4 per token, rounding UP")
    void roundsUp(long characters, long expected) {
        assertThat(TokenEstimator.estimateFromCharacters(characters)).isEqualTo(expected);
    }

    @Test
    @DisplayName("any non-empty text costs at least one token")
    void neverRoundsDownToZero() {
        // Plain division would make a 1-3 character delta free, so a stream of tiny
        // deltas - which is exactly what token-by-token streaming produces - would draw
        // down nothing at all. That is the bug this rounding exists to prevent.
        for (int characters = 1; characters <= 3; characters++) {
            assertThat(TokenEstimator.estimateFromCharacters(characters))
                    .as("%d characters", characters)
                    .isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("zero and negative are zero, not an error or a negative drawdown")
    void nonPositiveIsZero() {
        // A negative would CREDIT the tenant's budget if it ever reached the ledger.
        assertThat(TokenEstimator.estimateFromCharacters(0L)).isZero();
        assertThat(TokenEstimator.estimateFromCharacters(-1L)).isZero();
        assertThat(TokenEstimator.estimateFromCharacters(Long.MIN_VALUE)).isZero();
    }

    @Test
    @DisplayName("null text is zero rather than a NullPointerException")
    void nullTextIsZero() {
        assertThat(TokenEstimator.estimate(null)).isZero();
    }

    @Test
    @DisplayName("text is measured by length")
    void estimatesText() {
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimate("hello world")).isEqualTo(3L);
    }

    @Test
    @DisplayName("the estimate never overflows for an absurd character count")
    void doesNotOverflow() {
        // (characters + 3) would overflow at Long.MAX_VALUE and produce a negative, which
        // would credit the tenant instead of charging them.
        assertThat(TokenEstimator.estimateFromCharacters(Long.MAX_VALUE - 3L)).isPositive();
    }
}
