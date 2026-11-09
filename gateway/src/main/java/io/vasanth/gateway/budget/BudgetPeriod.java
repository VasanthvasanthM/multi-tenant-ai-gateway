package io.vasanth.gateway.budget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * The window a token budget applies to.
 *
 * <h2>Why this is configuration rather than a constant</h2>
 *
 * <p>{@code tenants.quota_tokens} in the control-plane schema carries no period column: it
 * says a tenant may spend 1,000,000 tokens, and not over what. A quota without a period is
 * not a quota, so the period has to come from somewhere, and inventing a hidden constant
 * would bury an assumption that changes what every tenant is entitled to.
 *
 * <p>The default is {@link #MONTHLY}, because 1,000,000 tokens reads as a monthly plan
 * allowance and because {@link io.vasanth.gateway.common.error.GatewayError.BudgetExhausted}
 * is documented as not retryable "until the budget is topped up or the period rolls over" -
 * which describes a billing period, not an hour. **That is an inference from the schema and
 * the error contract, not a stated requirement**, which is precisely why it is overridable
 * with {@code gateway.budget.period}.
 *
 * <h2>Everything is UTC</h2>
 *
 * <p>Period boundaries are computed in UTC, not in a tenant's local zone. Per-tenant zones
 * would mean the same counter rolls over at different instants for different tenants, and
 * a tenant moving zone would gain or lose a window. UTC is also what the services run in
 * and what {@code usage_ledger.occurred_at} stores, so the gateway's view of "this period"
 * matches the ledger's without a conversion that could drift.
 */
public enum BudgetPeriod {

    /** Calendar day, UTC. Suits a trial or a burst-protection budget. */
    DAILY {
        @Override
        public String key(Instant at) {
            return at.atOffset(ZoneOffset.UTC).toLocalDate().toString();
        }

        @Override
        Instant nextBoundary(Instant at) {
            return at.atOffset(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC);
        }
    },

    /** Calendar month, UTC. The default, and what a plan allowance normally means. */
    MONTHLY {
        @Override
        public String key(Instant at) {
            // YYYY-MM. Deliberately not the ISO instant: the key must be identical for
            // every request in the period, which is the whole mechanism.
            return String.format("%04d-%02d",
                    at.atOffset(ZoneOffset.UTC).getYear(),
                    at.atOffset(ZoneOffset.UTC).getMonthValue());
        }

        @Override
        Instant nextBoundary(Instant at) {
            return at.atOffset(ZoneOffset.UTC)
                    .toLocalDate()
                    .withDayOfMonth(1)
                    .plusMonths(1)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC);
        }
    };

    /**
     * How long a counter outlives the period it belongs to.
     *
     * <p>Not zero. A usage record can arrive slightly after the boundary - a request that
     * started before midnight and finished after it - and the counter it belongs to must
     * still exist to receive it. A day of grace costs one extra key per tenant per period
     * and removes a class of lost-write that would only ever show up as a tenant being
     * charged for tokens the enforcement counter never saw.
     */
    private static final Duration GRACE = Duration.ofDays(1);

    /** Stable identifier for the period containing {@code at}, used in the Redis key. */
    public abstract String key(Instant at);

    /** First instant of the following period. */
    abstract Instant nextBoundary(Instant at);

    /**
     * Seconds until this period's counter may be discarded.
     *
     * <p>At least one second, because Redis rejects a non-positive {@code EXPIRE} by
     * deleting the key - which would throw away the counter that was just incremented and
     * silently reset the tenant's spend to zero. Clamping is cheaper than reasoning about
     * whether the boundary can ever be in the past.
     */
    public long ttlSeconds(Clock clock) {
        Instant now = Instant.now(clock);
        long seconds = ChronoUnit.SECONDS.between(now, nextBoundary(now).plus(GRACE));
        return Math.max(1L, seconds);
    }
}
