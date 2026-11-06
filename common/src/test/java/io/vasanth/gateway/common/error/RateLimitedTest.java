package io.vasanth.gateway.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the retry-after rounding.
 *
 * <p>Regression test: the header rounded up while the message truncated, so a 400 ms wait
 * was advertised as "1" in Retry-After and "0" in the body. A client trusting the body
 * retried immediately, which is the worst possible response to being rate limited.
 */
class RateLimitedTest {

    @ParameterizedTest(name = "{0}ms -> {1}s")
    @CsvSource({
        "0, 1",      // never advertise 0: that reads as "retry now"
        "1, 1",
        "400, 1",
        "999, 1",
        "1000, 1",
        "1001, 2",
        "1500, 2",
        "2000, 2",
        "2001, 3",
    })
    void roundsUpAndNeverBelowOne(long millis, long expectedSeconds) {
        GatewayError.RateLimited error =
                new GatewayError.RateLimited(Duration.ofMillis(millis));

        assertThat(error.retryAfterSeconds()).isEqualTo(expectedSeconds);
    }

    @Test
    @DisplayName("message and header agree for a sub-second wait")
    void messageMatchesHeaderValue() {
        GatewayError.RateLimited error =
                new GatewayError.RateLimited(Duration.ofMillis(400));

        assertThat(error.message()).contains(Long.toString(error.retryAfterSeconds()) + "s");
        assertThat(error.message()).doesNotContain("0s");
    }

    @Test
    void isRetryableAnd429() {
        GatewayError.RateLimited error = new GatewayError.RateLimited(Duration.ofSeconds(1));

        assertThat(error.retryable()).isTrue();
        assertThat(error.httpStatus()).isEqualTo(429);
        assertThat(error.code()).isEqualTo("rate_limited");
    }

    @Test
    void budgetExhaustedIsNotRetryable() {
        // The distinction that stops clients retry-storming against a spent budget.
        GatewayError.BudgetExhausted error = new GatewayError.BudgetExhausted(1000L, 1000L);

        assertThat(error.retryable()).isFalse();
        assertThat(error.httpStatus()).isEqualTo(402);
    }
}
