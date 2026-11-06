package io.vasanth.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The gate that turns a {@link RateLimitDecision} into either nothing or a 429.
 *
 * <p>Small class, two jobs, and both are easy to get subtly wrong: the DEGRADED counter
 * must be separate from the rejection counter, and a degraded-but-allowed request must
 * pass through while still being recorded.
 */
@ExtendWith(MockitoExtension.class)
class TokenBucketGateTest {

    @Mock
    private TokenBucketRateLimiter limiter;

    private SimpleMeterRegistry meters;
    private TokenBucketGate gate;

    private static final TenantId TENANT = TenantId.of("acme");

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        gate = new TokenBucketGate(limiter, meters);
    }

    private double rejected() {
        var counter = meters.find("gateway.requests.rejected")
                .tag("reason", "rate_limited").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private double degraded() {
        var counter = meters.find("gateway.ratelimit.degraded").counter();
        return counter == null ? 0.0d : counter.count();
    }

    @Test
    @DisplayName("an allowed decision passes through and records nothing")
    void allowedPassesThrough() {
        when(limiter.tryAcquire(any())).thenReturn(Mono.just(RateLimitDecision.allowed(99L)));

        StepVerifier.create(gate.check(TENANT)).verifyComplete();

        assertThat(rejected()).isZero();
        assertThat(degraded()).isZero();
    }

    @Test
    @DisplayName("a denied decision becomes a RateLimited error carrying the wait")
    void deniedBecomesRateLimited() {
        when(limiter.tryAcquire(any()))
                .thenReturn(Mono.just(RateLimitDecision.denied(0L, Duration.ofSeconds(3))));

        StepVerifier.create(gate.check(TENANT))
                .verifyErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.RateLimited.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(429);
                    // Retryable, unlike a budget exhaustion - waiting genuinely helps.
                    assertThat(gatewayError.retryable()).isTrue();
                    assertThat(((GatewayError.RateLimited) gatewayError).retryAfter())
                            .isEqualTo(Duration.ofSeconds(3));
                });

        assertThat(rejected()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a degraded-but-allowed request passes AND is counted as degraded")
    void degradedAllowedIsCountedButNotRejected() {
        when(limiter.tryAcquire(any())).thenReturn(Mono.just(RateLimitDecision.failedOpen()));

        StepVerifier.create(gate.check(TENANT)).verifyComplete();

        // Both halves matter. The request must proceed - that is what fail-open means -
        // and the fact that NO limit was enforced must be visible, because otherwise a
        // Redis outage silently removes abuse protection with nothing on any dashboard.
        assertThat(degraded()).isEqualTo(1.0d);
        assertThat(rejected()).isZero();
    }

    @Test
    @DisplayName("a degraded-and-denied request counts in both counters")
    void degradedDeniedCountsBoth() {
        when(limiter.tryAcquire(any()))
                .thenReturn(Mono.just(RateLimitDecision.failedClosed(Duration.ofSeconds(1))));

        StepVerifier.create(gate.check(TENANT))
                .verifyErrorSatisfies(error -> assertThat(((GatewayException) error).error())
                        .isInstanceOf(GatewayError.RateLimited.class));

        // Deliberately both: it was rejected, AND the reason was a limiter failure
        // rather than the tenant exceeding a quota. Those need telling apart - one is
        // the tenant's problem and the other is ours.
        assertThat(degraded()).isEqualTo(1.0d);
        assertThat(rejected()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a disabled limiter passes through silently")
    void disabledPassesThrough() {
        when(limiter.tryAcquire(any())).thenReturn(Mono.just(RateLimitDecision.disabled()));

        StepVerifier.create(gate.check(TENANT)).verifyComplete();

        // Disabled is a configuration choice, not a malfunction, so it must NOT raise
        // the degraded counter - or the signal that means "limits are not being
        // enforced because Redis is down" would fire constantly in a deployment that
        // turned limiting off on purpose.
        assertThat(degraded()).isZero();
        assertThat(rejected()).isZero();
    }

    @Test
    @DisplayName("the retry-after in the error is the one the limiter reported")
    void retryAfterIsPreserved() {
        when(limiter.tryAcquire(any()))
                .thenReturn(Mono.just(RateLimitDecision.denied(0L, Duration.ofMillis(1_400))));

        StepVerifier.create(gate.check(TENANT))
                .verifyErrorSatisfies(error -> {
                    GatewayError.RateLimited rateLimited =
                            (GatewayError.RateLimited) ((GatewayException) error).error();
                    // retryAfterSeconds() is the single source of truth for the header
                    // and the message body, which once disagreed because each rounded
                    // independently. 1.4 s must round UP, or a client that waits the
                    // stated time is still too early.
                    assertThat(rateLimited.retryAfter()).isEqualTo(Duration.ofMillis(1_400));
                    assertThat(rateLimited.retryAfterSeconds()).isEqualTo(2L);
                });
    }
}
