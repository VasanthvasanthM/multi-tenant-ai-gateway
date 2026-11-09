package io.vasanth.gateway.budget;

/**
 * Outcome of a budget check.
 *
 * <p>Mirrors {@link io.vasanth.gateway.ratelimit.RateLimitDecision} on purpose: the
 * limiter/gate split is the established shape in this codebase, and the {@code degraded}
 * flag exists for the same reason there - an operator must be able to tell "within budget"
 * from "budget not being enforced right now", because the second is an incident and would
 * otherwise be invisible.
 *
 * @param allowed whether the request may proceed
 * @param usedTokens tokens recorded against this period, or -1 when not known (enforcement
 *     disabled, or the store was unreachable)
 * @param limitTokens the limit that applied, or -1 when unlimited or not known
 * @param degraded true when the budget store could not be reached and the configured
 *     fail-open policy let the request through unchecked
 */
public record BudgetDecision(
        boolean allowed, long usedTokens, long limitTokens, boolean degraded) {

    public static BudgetDecision allowed(long usedTokens, long limitTokens) {
        return new BudgetDecision(true, usedTokens, limitTokens, false);
    }

    public static BudgetDecision exhausted(long usedTokens, long limitTokens) {
        return new BudgetDecision(false, usedTokens, limitTokens, false);
    }

    /** Enforcement is switched off, or the resolved limit is unlimited. */
    public static BudgetDecision notEnforced() {
        return new BudgetDecision(true, -1L, -1L, false);
    }

    /** The store was unreachable and {@code failOpen} is configured. */
    public static BudgetDecision failedOpen() {
        return new BudgetDecision(true, -1L, -1L, true);
    }

    /** The store was unreachable and {@code failOpen} is disabled. */
    public static BudgetDecision failedClosed() {
        return new BudgetDecision(false, -1L, -1L, true);
    }
}
