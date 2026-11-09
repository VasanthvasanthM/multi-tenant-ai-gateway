package io.vasanth.gateway.budget;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-tenant token budget configuration.
 *
 * <h2>Why enforcement is off by default</h2>
 *
 * <p>Switching this on turns requests that previously succeeded into 402s. The limit a
 * tenant gets is {@code defaultLimitTokens} until something publishes a per-tenant value
 * to Redis, and nothing does yet - the control plane has no HTTP API and its provisioning
 * saga has no concrete steps. Defaulting to on would therefore apply one hard-coded number
 * to every tenant on upgrade, which should be a deliberate act rather than a side effect of
 * deploying.
 *
 * <p>Same reasoning as {@code shadowMode} on the semantic cache: a control that can refuse
 * traffic is switched on once its inputs are real, not when its code lands.
 *
 * @param enabled whether budgets are tracked at all. When false nothing is counted and no
 *     Redis call is made on the request path.
 * @param shadowMode when true, usage is counted and over-budget requests are logged and
 *     metered but still served. The same two-stage roll-out the semantic cache uses: a
 *     control that can refuse paid traffic should first prove, against real usage, that it
 *     refuses the right requests. Switching straight to enforcement means discovering a
 *     mis-set limit as a wall of 402s.
 * @param period the window a budget applies to. Defaults to {@link BudgetPeriod#MONTHLY};
 *     see that class for why the period cannot simply be assumed
 * @param defaultLimitTokens limit for a tenant with no published limit of its own. Mirrors
 *     {@code tenants.quota_tokens DEFAULT 1000000} in the control-plane schema, so a tenant
 *     provisioned with the schema default behaves the same whether or not its limit has
 *     reached Redis yet. **Zero means unlimited** - see {@link TokenBudgetLedger}.
 * @param failOpen whether to serve when the budget store is unreachable
 */
@Validated
@ConfigurationProperties(prefix = "gateway.budget")
public record BudgetProperties(
        boolean enabled,
        boolean shadowMode,
        BudgetPeriod period,
        @Min(0) long defaultLimitTokens,
        boolean failOpen) {

    /**
     * Defaults {@code period} rather than rejecting null.
     *
     * <p>Deliberately not {@code @NotNull}: that annotation plus this defaulting would
     * contradict each other, and the annotation would be decoration since the default fires
     * first. A missing period is a configuration omission with one obviously right answer,
     * not an error worth refusing to boot over - unlike {@code similarityThreshold}, where
     * any default would be a guess about correctness.
     */
    public BudgetProperties {
        if (period == null) {
            period = BudgetPeriod.MONTHLY;
        }
    }
}
