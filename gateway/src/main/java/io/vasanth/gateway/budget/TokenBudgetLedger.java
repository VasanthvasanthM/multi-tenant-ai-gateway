package io.vasanth.gateway.budget;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Per-tenant token spend for the current period, held in Redis.
 *
 * <p>Answers "is there budget left?" and "this request spent N tokens". It takes no view on
 * what an exhausted budget means to a request - that is {@link BudgetGate}. The split
 * mirrors {@link io.vasanth.gateway.ratelimit.TokenBucketRateLimiter} and
 * {@link io.vasanth.gateway.ratelimit.TokenBucketGate}, and exists for the same reason:
 * this half can be tested against real Redis without the error taxonomy, and the gate can
 * be tested without Redis at all.
 *
 * <h2>Why checking and recording are separate calls</h2>
 *
 * <p>A completion's cost is not known until the completion exists. There is no way to debit
 * the right number of tokens before calling the provider, because the number is in the
 * response. So enforcement is necessarily:
 *
 * <ol>
 *   <li><b>before</b> - refuse if the tenant is <em>already</em> over its limit;
 *   <li><b>after</b> - add what the request actually cost.
 * </ol>
 *
 * <p><b>The consequence is an overshoot, and it is inherent rather than a defect.</b> A
 * tenant sitting just under its limit can still have every in-flight request served, so the
 * final figure can exceed the limit by roughly (concurrent requests x tokens per request).
 * The alternative - reserving an estimate up front and reconciling afterwards - bounds the
 * overshoot but leaks a reservation whenever a request dies between the two writes, and a
 * leaked reservation silently shrinks a tenant's budget until the period rolls over. A
 * bounded overshoot that self-corrects is strictly better than an unbounded under-grant
 * that does not.
 *
 * <p>This is also why the authoritative number is {@code usage_ledger} in Postgres, written
 * by the metering service from Kafka. That ledger is exact and idempotent, and it is what a
 * tenant is billed from. The counter here is an <em>enforcement</em> figure: fast, shared
 * across replicas, and allowed to be approximate at the edges.
 *
 * <h2>Cache hits spend budget</h2>
 *
 * <p>Deliberately, and {@code usage_ledger.billable} records the same distinction: a cache
 * hit consumed the tenant's quota even though no vendor was paid. A budget that only
 * counted upstream calls would let a tenant with a high hit ratio serve unlimited traffic,
 * which inverts the product's incentive - the cache exists to make the operator's costs
 * lower, not to make a tenant's entitlement unbounded.
 */
@Component
public class TokenBudgetLedger {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetLedger.class);

    /** Zero or less, as a resolved limit, means no ceiling. */
    private static final long UNLIMITED = 0L;

    private final ReactiveStringRedisTemplate redis;
    private final RedisScript<Long> budgetRecordScript;
    private final TenantQuotaRegistry quotas;
    private final BudgetProperties config;
    private final Clock clock;

    public TokenBudgetLedger(
            ReactiveStringRedisTemplate redis,
            RedisScript<Long> budgetRecordScript,
            TenantQuotaRegistry quotas,
            BudgetProperties config,
            Clock clock) {
        this.redis = redis;
        this.budgetRecordScript = budgetRecordScript;
        this.quotas = quotas;
        this.config = config;
        this.clock = clock;
    }

    /**
     * Whether {@code tenant} has budget left in the current period.
     *
     * <p>Never signals an error. A budget store that propagated Redis failures would turn a
     * degraded dependency into a total outage - the same inversion {@code
     * TokenBucketRateLimiter} avoids - so infrastructure failure resolves to the configured
     * fail-open or fail-closed policy instead.
     */
    public Mono<BudgetDecision> check(TenantId tenant) {
        if (!config.enabled()) {
            return Mono.just(BudgetDecision.notEnforced());
        }

        return quotas.limitFor(tenant)
                .flatMap(limit -> limit <= UNLIMITED
                        ? Mono.just(BudgetDecision.notEnforced())
                        : usedBy(tenant).map(used -> decide(used, limit)))
                .onErrorResume(error -> Mono.just(onStoreUnavailable(tenant, error)));
    }

    /**
     * Adds {@code tokens} to the tenant's spend for the current period.
     *
     * <p>Returns the new total, or empty when there is nothing to do. Never signals an
     * error: a failure to record must not fail a request whose answer the tenant already
     * has, for the same reason a failed Tier-2 write does not.
     */
    public Mono<Long> record(TenantId tenant, long tokens) {
        if (!config.enabled() || tokens <= 0L) {
            // tokens == 0 is normal, not exceptional: an empty or fully-cached response can
            // legitimately cost nothing, and the Lua script rejects a negative, so filtering
            // here keeps that precondition from ever being reachable.
            return Mono.empty();
        }

        return redis.execute(
                        budgetRecordScript,
                        List.of(counterKey(tenant, Instant.now(clock))),
                        List.of(
                                Long.toString(tokens),
                                Long.toString(config.period().ttlSeconds(clock))))
                .next()
                .onErrorResume(error -> {
                    // WARN, not ERROR: the request succeeded and the tenant has its answer.
                    // What is lost is enforcement accuracy, and the Postgres ledger still
                    // records the spend exactly, so nothing is mis-billed.
                    log.warn("failed to record {} tokens against the budget for tenant {}: {}",
                            tokens, tenant, error.toString());
                    return Mono.empty();
                });
    }

    /** Tokens recorded against the current period; zero when nothing is recorded yet. */
    private Mono<Long> usedBy(TenantId tenant) {
        return redis.opsForValue()
                .get(counterKey(tenant, Instant.now(clock)))
                .map(Long::parseLong)
                .defaultIfEmpty(0L);
    }

    private static BudgetDecision decide(long used, long limit) {
        // >= and not >: a tenant that has spent exactly its limit has nothing left. Using >
        // would grant one extra request at every limit, which is the kind of off-by-one that
        // only shows up as a billing dispute.
        return used >= limit
                ? BudgetDecision.exhausted(used, limit)
                : BudgetDecision.allowed(used, limit);
    }

    /**
     * Redis key for a tenant's spend in the period containing {@code at}.
     *
     * <p>Hash-tagged with the tenant so it shares a Redis Cluster slot with that tenant's
     * cache entries, token bucket and published limit. Period-stamped so a new period starts
     * from zero without anything having to reset it - the old key simply expires, which is
     * the only reset mechanism that cannot fail halfway or be forgotten.
     */
    String counterKey(TenantId tenant, Instant at) {
        return tenant.redisHashTag() + ":budget:" + config.period().key(at);
    }

    private BudgetDecision onStoreUnavailable(TenantId tenant, Throwable error) {
        log.warn(
                "budget store unavailable for tenant {}, failing {}: {}",
                tenant,
                config.failOpen() ? "open" : "closed",
                error.toString());

        // Fail-open by default, matching the rate limiter. The reasoning is slightly
        // different and worth stating: for a budget, failing open gives away tokens, which
        // sounds like the expensive choice. It is not, because the Postgres ledger still
        // records every token and the tenant is still billed correctly - what is lost is
        // the ceiling, temporarily. Failing closed would instead refuse paid traffic from
        // every tenant because one Redis node blinked. A deployment that genuinely must
        // never exceed a budget sets gateway.budget.fail-open=false and accepts that.
        return config.failOpen()
                ? BudgetDecision.failedOpen()
                : BudgetDecision.failedClosed();
    }
}
