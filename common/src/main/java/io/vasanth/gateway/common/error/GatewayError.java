package io.vasanth.gateway.common.error;

import java.time.Duration;
import java.util.Objects;

/**
 * Every way a request can fail, as a closed set.
 *
 * <h2>Why a sealed interface rather than exceptions</h2>
 *
 * <p>Three reasons, in order of how much pain each one saves:
 *
 * <ol>
 *   <li><b>Exhaustiveness.</b> Because the hierarchy is sealed, a {@code switch} over it
 *       without a {@code default} fails to compile when a new case is added. Adding an error
 *       type therefore forces every mapping site - HTTP status, metric label, retry
 *       decision - to be updated. With exceptions, a new type silently falls into a catch-all
 *       and is reported as a 500.
 *   <li><b>Reactive fit.</b> In a Reactor pipeline, failures carried as values in
 *       {@code Mono<Either>}-shaped flows compose with operators; exceptions thrown inside
 *       lambdas get wrapped, lose their stack context, and are awkward to branch on.
 *   <li><b>Cost.</b> Exceptions capture a stack trace on construction. On a path that
 *       returns 429 under load - precisely when the system is busiest - that is real CPU
 *       spent describing a condition that is entirely expected.
 * </ol>
 *
 * <h2>retryable()</h2>
 *
 * <p>This flag is what the client SDK and the circuit breaker act on, so it must mean exactly
 * one thing: <em>would resending this identical request plausibly succeed later?</em> A
 * malformed request is never retryable no matter how many times it is sent; an upstream
 * timeout usually is.
 */
public sealed interface GatewayError {

    /** Stable machine-readable code. Part of the public API contract; never rename one. */
    String code();

    /** Human-readable detail. Must never contain another tenant's data or a secret. */
    String message();

    /** HTTP status this maps to. */
    int httpStatus();

    /** Whether resending the identical request could plausibly succeed. */
    boolean retryable();

    // -----------------------------------------------------------------
    // Client errors
    // -----------------------------------------------------------------

    /** No API key, or a key that does not resolve to a tenant. */
    record Unauthenticated(String message) implements GatewayError {
        public Unauthenticated {
            Objects.requireNonNull(message);
        }

        @Override
        public String code() {
            return "unauthenticated";
        }

        @Override
        public int httpStatus() {
            return 401;
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }

    /** Authenticated, but not permitted to use this model or operation. */
    record Forbidden(String message) implements GatewayError {
        public Forbidden {
            Objects.requireNonNull(message);
        }

        @Override
        public String code() {
            return "forbidden";
        }

        @Override
        public int httpStatus() {
            return 403;
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }

    /** Request failed validation. */
    record InvalidRequest(String message) implements GatewayError {
        public InvalidRequest {
            Objects.requireNonNull(message);
        }

        @Override
        public String code() {
            return "invalid_request";
        }

        @Override
        public int httpStatus() {
            return 400;
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }

    /** Prompt exceeds the configured size limit. */
    record PayloadTooLarge(int actualCodePoints, int limitCodePoints) implements GatewayError {

        @Override
        public String code() {
            return "payload_too_large";
        }

        @Override
        public String message() {
            return "prompt is " + actualCodePoints + " characters, limit is " + limitCodePoints;
        }

        @Override
        public int httpStatus() {
            return 413;
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Quota and rate limiting
    // -----------------------------------------------------------------

    /**
     * This tenant's data is being moved between shards, so its writes are paused.
     *
     * <p>A shard migration copies a tenant's rows while the source keeps serving. Anything
     * written between the copy starting and the cutover lands on the <em>old</em> shard and
     * is never copied - a usage row that silently stops existing, because after the cutover
     * nothing reads there. Pausing the tenant for the seconds the copy takes closes that
     * window.
     *
     * <p><b>Why 503 and not 429.</b> The runbook originally proposed 429, reasoning that
     * clients already retry it. They do, but a 429 claims the <em>caller</em> sent too
     * much, which is false here and would put a planned migration into the rate-limit
     * counters - a spike on the abuse dashboard caused by an operator action. 503 says what
     * is true: the service is briefly unavailable for this tenant. {@code Retry-After} is
     * valid on both, and the distinct {@code code} is what a client or a dashboard should
     * branch on.
     *
     * <p>It is deliberately the whole request, not only the write. Serving a request always
     * produces a usage event, so there is no way to answer a tenant and write nothing. The
     * honest description is a brief per-tenant outage, scoped to one customer and measured
     * in seconds, rather than a write-only pause.
     */
    record TenantMigrating(String tenant, Duration retryAfter) implements GatewayError {
        public TenantMigrating {
            Objects.requireNonNull(tenant);
            Objects.requireNonNull(retryAfter);
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }

        @Override
        public String code() {
            return "tenant_migrating";
        }

        /** Whole seconds, rounded up, never below 1 - see {@link RateLimited}. */
        public long retryAfterSeconds() {
            return Math.max(1L, (retryAfter.toMillis() + 999L) / 1000L);
        }

        @Override
        public String message() {
            return "tenant " + tenant + " is being migrated between shards; retry after "
                    + retryAfterSeconds() + "s";
        }

        @Override
        public int httpStatus() {
            return 503;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * Token bucket is empty. {@code retryAfter} is surfaced as the {@code Retry-After}
     * header so a well-behaved client backs off by the right amount instead of guessing and
     * making the overload worse.
     */
    record RateLimited(Duration retryAfter) implements GatewayError {
        public RateLimited {
            Objects.requireNonNull(retryAfter);
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }

        @Override
        public String code() {
            return "rate_limited";
        }

        /**
         * Backoff in whole seconds, always rounded up and never below 1.
         *
         * <p>Single source of truth for both this message and the {@code Retry-After}
         * header. They diverged once - the header rounded up while the message used
         * {@code toSeconds()}, which truncates - so a sub-second wait advertised "1" in the
         * header and "0" in the body, and a client reading the body would retry instantly
         * and make the overload worse.
         */
        public long retryAfterSeconds() {
            return Math.max(1L, (retryAfter.toMillis() + 999L) / 1000L);
        }

        @Override
        public String message() {
            return "rate limit exceeded; retry after " + retryAfterSeconds() + "s";
        }

        @Override
        public int httpStatus() {
            return 429;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * The tenant's token budget for the period is spent.
     *
     * <p>Distinct from {@link RateLimited} and <em>not</em> retryable: waiting does not help
     * until the budget is topped up or the period rolls over. Collapsing the two would have
     * clients retry-storm against a wall.
     */
    record BudgetExhausted(long limitTokens, long usedTokens) implements GatewayError {

        @Override
        public String code() {
            return "budget_exhausted";
        }

        @Override
        public String message() {
            return "token budget exhausted: used " + usedTokens + " of " + limitTokens;
        }

        @Override
        public int httpStatus() {
            return 402;
        }

        @Override
        public boolean retryable() {
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Upstream and infrastructure
    // -----------------------------------------------------------------

    /** Every configured provider, including fallbacks, failed or is unavailable. */
    record UpstreamUnavailable(String message) implements GatewayError {
        public UpstreamUnavailable {
            Objects.requireNonNull(message);
        }

        @Override
        public String code() {
            return "upstream_unavailable";
        }

        @Override
        public int httpStatus() {
            return 503;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** Upstream exceeded its time limit. */
    record UpstreamTimeout(Duration elapsed) implements GatewayError {
        public UpstreamTimeout {
            Objects.requireNonNull(elapsed);
        }

        @Override
        public String code() {
            return "upstream_timeout";
        }

        @Override
        public String message() {
            return "upstream did not respond within " + elapsed.toMillis() + "ms";
        }

        @Override
        public int httpStatus() {
            return 504;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * The circuit breaker is open, so the call was rejected without being attempted.
     *
     * <p>Separate from {@link UpstreamUnavailable} because it is a decision this gateway
     * made, not a failure it observed. Keeping them apart is what lets a dashboard show
     * "we are shedding load deliberately" rather than "the provider is down".
     */
    record CircuitOpen(String provider) implements GatewayError {
        public CircuitOpen {
            Objects.requireNonNull(provider);
        }

        @Override
        public String code() {
            return "circuit_open";
        }

        @Override
        public String message() {
            return "circuit breaker open for provider " + provider;
        }

        @Override
        public int httpStatus() {
            return 503;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * In-flight request limit reached; the request was shed rather than queued.
     *
     * <p>This is backpressure working as designed. Shedding at the edge keeps latency bounded
     * for requests already accepted, which is strictly better than queueing everything and
     * timing out uniformly.
     */
    record Overloaded(int inFlightLimit) implements GatewayError {

        @Override
        public String code() {
            return "overloaded";
        }

        @Override
        public String message() {
            return "gateway at capacity: " + inFlightLimit + " concurrent requests in flight";
        }

        @Override
        public int httpStatus() {
            return 503;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /**
     * An unexpected internal failure.
     *
     * <p>{@code message} is what the client sees and must stay generic; the {@code cause} is
     * for logs only. Leaking an internal exception message to a caller discloses library
     * versions, SQL shape and file paths.
     */
    record Internal(String message) implements GatewayError {
        public Internal {
            Objects.requireNonNull(message);
        }

        public static Internal generic() {
            return new Internal("internal error");
        }

        @Override
        public String code() {
            return "internal_error";
        }

        @Override
        public int httpStatus() {
            return 500;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }
}
