package io.vasanth.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * What an exhausted budget means to a request.
 *
 * <p>No Redis. The ledger is mocked, which is the point of the ledger/gate split: this
 * class contains only the decision about refusal, and a test of refusal policy should not
 * need a container.
 */
@ExtendWith(MockitoExtension.class)
class BudgetGateTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    @Mock
    private TokenBudgetLedger ledger;

    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    private BudgetGate gate(boolean shadowMode) {
        return new BudgetGate(
                ledger,
                new BudgetProperties(true, shadowMode, BudgetPeriod.MONTHLY, 1_000_000L, true),
                meters);
    }

    private double counter(String name, String... tags) {
        return Optional.ofNullable(meters.find(name).tags(tags).counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    private double rejections() {
        return counter("gateway.requests.rejected", "reason", "budget_exhausted");
    }

    @Nested
    @DisplayName("enforcing")
    class Enforcing {

        @Test
        @DisplayName("a request within budget passes and counts nothing")
        void withinBudgetPasses() {
            when(ledger.check(ACME)).thenReturn(
                    Mono.just(BudgetDecision.allowed(10L, 1_000L)));

            StepVerifier.create(gate(false).check(ACME)).verifyComplete();

            assertThat(rejections()).isZero();
            assertThat(counter("gateway.budget.degraded")).isZero();
        }

        @Test
        @DisplayName("an exhausted budget is a non-retryable 402 carrying both figures")
        void exhaustedIsRefused() {
            when(ledger.check(ACME)).thenReturn(
                    Mono.just(BudgetDecision.exhausted(1_200L, 1_000L)));

            StepVerifier.create(gate(false).check(ACME))
                    .verifyErrorSatisfies(thrown -> {
                        GatewayError error = ((GatewayException) thrown).error();
                        assertThat(error).isInstanceOf(GatewayError.BudgetExhausted.class);
                        assertThat(error.httpStatus()).isEqualTo(402);
                        // NOT retryable, and that is the distinction from RateLimited.
                        // Waiting does not refill a budget, so advertising a retry would
                        // be a lie that generates load against a wall.
                        assertThat(error.retryable()).isFalse();
                        assertThat(error.code()).isEqualTo("budget_exhausted");
                        // The argument order is (limit, used) and getting it backwards
                        // produces a plausible message that is exactly wrong.
                        GatewayError.BudgetExhausted budget =
                                (GatewayError.BudgetExhausted) error;
                        assertThat(budget.limitTokens()).isEqualTo(1_000L);
                        assertThat(budget.usedTokens()).isEqualTo(1_200L);
                        assertThat(error.message()).contains("used 1200 of 1000");
                    });

            assertThat(rejections()).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("spending exactly the limit leaves nothing: the boundary is >=, not >")
        void exactlyAtTheLimitIsExhausted() {
            // The ledger decides this, so the gate test asserts it is honoured rather than
            // recomputed. See TokenBudgetLedgerTest for the boundary itself.
            when(ledger.check(ACME)).thenReturn(
                    Mono.just(BudgetDecision.exhausted(1_000L, 1_000L)));

            StepVerifier.create(gate(false).check(ACME))
                    .verifyErrorSatisfies(thrown -> assertThat(
                            ((GatewayException) thrown).error())
                            .isInstanceOf(GatewayError.BudgetExhausted.class));
        }

        @Test
        @DisplayName("not enforced passes without touching any counter")
        void notEnforcedPasses() {
            when(ledger.check(ACME)).thenReturn(Mono.just(BudgetDecision.notEnforced()));

            StepVerifier.create(gate(false).check(ACME)).verifyComplete();

            assertThat(rejections()).isZero();
            assertThat(counter("gateway.budget.degraded")).isZero();
        }
    }

    @Nested
    @DisplayName("shadow mode")
    class Shadow {

        @Test
        @DisplayName("records what it would have refused, and serves it anyway")
        void shadowDoesNotRefuse() {
            when(ledger.check(ACME)).thenReturn(
                    Mono.just(BudgetDecision.exhausted(2_000L, 1_000L)));

            StepVerifier.create(gate(true).check(ACME)).verifyComplete();

            // The number that decides whether enforcement is safe to switch on. A dry-run
            // flag with no output is indistinguishable from one that is off.
            assertThat(counter("gateway.budget.shadow_would_have_refused")).isEqualTo(1.0d);
            assertThat(rejections()).isZero();
        }

        @Test
        @DisplayName("shadow mode never refuses, even when configured to fail closed")
        void shadowBeatsFailClosed() {
            when(ledger.check(ACME)).thenReturn(Mono.just(BudgetDecision.failedClosed()));

            // failOpen=false and shadowMode=true are contradictory instructions - "refuse
            // rather than exceed" against "never refuse". One rule that always holds beats
            // a precedence order nobody recalls at 3am, so shadow mode wins and says so.
            StepVerifier.create(gate(true).check(ACME)).verifyComplete();

            assertThat(counter("gateway.budget.degraded")).isEqualTo(1.0d);
            assertThat(rejections()).isZero();
        }
    }

    @Nested
    @DisplayName("when the store is unreachable")
    class Degraded {

        @Test
        @DisplayName("failing open serves, and records the incident separately")
        void failOpenServes() {
            when(ledger.check(ACME)).thenReturn(Mono.just(BudgetDecision.failedOpen()));

            StepVerifier.create(gate(false).check(ACME)).verifyComplete();

            // Separate from rejections because "within budget" and "budget not enforced
            // right now" must never look alike on a dashboard - the second is an incident.
            assertThat(counter("gateway.budget.degraded")).isEqualTo(1.0d);
            assertThat(rejections()).isZero();
        }

        @Test
        @DisplayName("failing closed is a retryable 500, NOT a 402 claiming -1 of -1")
        void failClosedIsNotReportedAsExhausted() {
            when(ledger.check(ACME)).thenReturn(Mono.just(BudgetDecision.failedClosed()));

            StepVerifier.create(gate(false).check(ACME))
                    .verifyErrorSatisfies(thrown -> {
                        GatewayError error = ((GatewayException) thrown).error();
                        // The used and limit figures were never read, so BudgetExhausted
                        // would render "used -1 of -1" and tell the tenant to top up a
                        // budget that may be untouched. This is the bug this test exists
                        // to prevent coming back.
                        assertThat(error).isNotInstanceOf(GatewayError.BudgetExhausted.class);
                        assertThat(error.httpStatus()).isEqualTo(500);
                        // Retryable: a Redis blip is transient, unlike a spent budget.
                        assertThat(error.retryable()).isTrue();
                        assertThat(error.message()).doesNotContain("-1");
                    });

            assertThat(counter("gateway.budget.degraded")).isEqualTo(1.0d);
            // Not counted as a budget rejection: nothing was known about the budget.
            assertThat(rejections()).isZero();
        }
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("delegates to the ledger and completes")
        void recordDelegates() {
            when(ledger.record(ACME, 42L)).thenReturn(Mono.just(142L));

            StepVerifier.create(gate(false).record(ACME, 42L)).verifyComplete();

            verify(ledger).record(ACME, 42L);
        }

        @Test
        @DisplayName("records in shadow mode too - that is what makes shadow mode useful")
        void recordsInShadowMode() {
            when(ledger.record(ACME, 42L)).thenReturn(Mono.just(42L));

            StepVerifier.create(gate(true).record(ACME, 42L)).verifyComplete();

            // Counting without refusing is the whole mechanism. A shadow mode that did not
            // count would measure nothing and could never justify switching enforcement on.
            verify(ledger).record(ACME, 42L);
        }

        @Test
        @DisplayName("an empty ledger result still completes rather than hanging")
        void emptyRecordCompletes() {
            // record() returns empty when there is nothing to do - disabled, or zero
            // tokens. `.then()` on an empty Mono completes, and this pins that, because
            // the pipeline subscribes to it fire-and-forget and a hang would be invisible.
            when(ledger.record(any(), anyLong())).thenReturn(Mono.empty());

            StepVerifier.create(gate(false).record(ACME, 0L)).verifyComplete();
        }
    }

    @Test
    @DisplayName("the gate asks the ledger exactly once per check")
    void doesNotDoubleCheck() {
        when(ledger.check(ACME)).thenReturn(Mono.just(BudgetDecision.allowed(1L, 1_000L)));

        gate(false).check(ACME).block(PATIENCE);

        verify(ledger).check(ACME);
        verify(ledger, never()).record(any(), anyLong());
    }
}
