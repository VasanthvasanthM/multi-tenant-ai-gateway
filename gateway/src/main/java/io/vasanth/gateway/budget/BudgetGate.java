package io.vasanth.gateway.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Turns a {@link BudgetDecision} into a pass or a {@code 402 budget_exhausted}.
 *
 * <p>Thin, like {@link io.vasanth.gateway.ratelimit.TokenBucketGate}, and for the same
 * reason: {@link TokenBudgetLedger} answers "is there budget?" and this answers "what does
 * no budget mean to this request?". Keeping them apart is what lets the ledger be tested
 * against real Redis and this be tested with no Redis at all.
 *
 * <h2>Why 402 and not 429</h2>
 *
 * <p>{@link GatewayError.BudgetExhausted} is not retryable and {@link GatewayError.RateLimited}
 * is. Collapsing them would have clients retry-storm against a wall: a rate limit clears by
 * waiting, a spent budget does not clear until someone tops it up or the period rolls over.
 * Returning 429 with a {@code Retry-After} a client could satisfy would be a lie that
 * generates load.
 */
@Component
public class BudgetGate {

    private static final Logger log = LoggerFactory.getLogger(BudgetGate.class);

    private final TokenBudgetLedger ledger;
    private final BudgetProperties config;

    private final Counter rejected;
    private final Counter shadowed;
    private final Counter degraded;
    private final Counter estimatedTokens;

    public BudgetGate(
            TokenBudgetLedger ledger, BudgetProperties config, MeterRegistry meterRegistry) {
        this.ledger = ledger;
        this.config = config;

        // Shares gateway.requests.rejected with the rate limiter and the load shedder, so a
        // dashboard can break refusals down by reason with one query instead of three.
        this.rejected = Counter.builder("gateway.requests.rejected")
                .tag("reason", "budget_exhausted")
                .register(meterRegistry);
        // The number that decides whether enforcement is safe to switch on. Without it,
        // shadow mode would be a setting with no output - which is the failure mode of
        // every well-intentioned dry-run flag.
        this.shadowed = Counter.builder("gateway.budget.shadow_would_have_refused")
                .register(meterRegistry);
        // An incident, not normal operation: the budget store was unreachable and the
        // fail-open policy let traffic through unmetered. Separate from rejections because
        // "within budget" and "budget not enforced right now" must not look alike.
        this.degraded = Counter.builder("gateway.budget.degraded")
                .register(meterRegistry);
        // How much of the recorded spend came from estimation rather than from a
        // provider's own count. Tracked so the approximation is measurable: a large share
        // here is the argument for implementing stream_options.include_usage, not for
        // trusting the estimate further.
        this.estimatedTokens = Counter.builder("gateway.budget.estimated_tokens")
                .register(meterRegistry);
    }

    /**
     * Allows the request, or fails it with {@link GatewayError.BudgetExhausted}.
     *
     * @return an empty {@link Mono} when the request may proceed
     */
    public Mono<Void> check(TenantId tenant) {
        return ledger.check(tenant)
                .flatMap(decision -> {
                    if (decision.degraded()) {
                        degraded.increment();
                    }
                    if (decision.allowed()) {
                        return Mono.empty();
                    }

                    // Shadow mode never refuses, including when the store is down and the
                    // policy is fail-closed. Those two settings together are contradictory -
                    // "refuse rather than exceed" against "never refuse" - and one rule that
                    // always holds is easier to reason about than a precedence order nobody
                    // can recall under pressure.
                    if (config.shadowMode()) {
                        shadowed.increment();
                        log.info(
                                "budget SHADOW would have refused tenant {}: used {} of {}",
                                tenant, decision.usedTokens(), decision.limitTokens());
                        return Mono.empty();
                    }

                    // Failed CLOSED, which is not the same as exhausted and must not be
                    // reported as it. The used and limit figures are -1 here because they
                    // were never read, so BudgetExhausted would render "used -1 of -1" to
                    // the tenant and a 402 telling them to top up a budget that may be
                    // untouched. Internal is a 503-grade truth: retryable, generic message,
                    // already in the OpenAPI error enum.
                    if (decision.degraded()) {
                        return Mono.<Void>error(new GatewayException(new GatewayError.Internal(
                                "budget enforcement is temporarily unavailable")));
                    }

                    rejected.increment();
                    log.info("budget exhausted for tenant {}: used {} of {}",
                            tenant, decision.usedTokens(), decision.limitTokens());
                    return Mono.<Void>error(new GatewayException(
                            new GatewayError.BudgetExhausted(
                                    decision.limitTokens(), decision.usedTokens())));
                })
                .then();
    }

    /**
     * Records what a served response cost.
     *
     * <p>Runs in shadow mode too - that is the point of shadow mode. Counting without
     * refusing is what produces the evidence that the limits are right.
     */
    public Mono<Void> record(TenantId tenant, long tokens) {
        return ledger.record(tenant, tokens).then();
    }

    /**
     * Records an <b>estimated</b> drawdown for a streamed response.
     *
     * <p>Separate from {@link #record} so the estimate is visible rather than blended into
     * the exact figures. A streamed response carries no usage frame, so the only
     * alternative was to draw down nothing - which left a tenant that streams exclusively
     * bounded by the rate limiter and not by its budget. See {@link TokenEstimator} for why
     * approximating is the lesser evil and why it rounds up.
     *
     * <p>{@code gateway.budget.estimated_tokens} exists so the share of a tenant's budget
     * that came from estimation is measurable. If it is large, that is the argument for
     * implementing {@code stream_options.include_usage} rather than for trusting the
     * estimate further.
     */
    public Mono<Void> recordStreamed(TenantId tenant, long characters) {
        long tokens = TokenEstimator.estimateFromCharacters(characters);
        if (tokens <= 0L) {
            return Mono.empty();
        }
        estimatedTokens.increment(tokens);
        return ledger.record(tenant, tokens).then();
    }
}
