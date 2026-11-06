package io.vasanth.gateway.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;

/**
 * Resilience policy for upstream calls.
 *
 * <p>Configured in Java rather than YAML so that the failure predicates - the parts that
 * actually determine behaviour - can be expressed as code and explained next to the
 * reasoning.
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // Count-based, not time-based. LLM traffic is bursty: a time window can
                // hold three calls during a lull and open the circuit on a single blip.
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50)
                // Never judge a provider on fewer than 10 calls. Without this, the first
                // failed call after startup is a 100% failure rate and trips the breaker.
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50.0f)
                // Slow calls count toward opening. An upstream answering every request in
                // 25 s is functionally down even though nothing technically fails, and a
                // pure failure-rate breaker would never notice.
                .slowCallRateThreshold(80.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(20))
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)

                // THE IMPORTANT PART. A 400 from the vendor means the caller sent
                // something unacceptable - an unknown model, bad parameters. Counting that
                // as a provider failure lets one client with a malformed request open the
                // circuit for every other tenant. Only genuine upstream faults count.
                .recordException(ResilienceConfig::countsAsProviderFailure)
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig config = BulkheadConfig.custom()
                // Concurrent in-flight calls per provider. Bounded so one slow upstream
                // cannot consume every connection and starve the others.
                .maxConcurrentCalls(64)
                // Fail fast rather than queue. A queue here would be invisible latency on
                // top of the time limiter, and callers would time out having waited
                // without ever reaching the provider.
                .maxWaitDuration(Duration.ZERO)
                .build();

        return BulkheadRegistry.of(config);
    }

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                // Per attempt, not per request. With retry wrapped outside, the worst case
                // is attempts x this value, which must stay inside the caller's own
                // deadline - hence 30 s here against the pipeline's 30 s overall budget and
                // a single retry.
                .timeoutDuration(Duration.ofSeconds(30))
                .cancelRunningFuture(true)
                .build();

        return TimeLimiterRegistry.of(config);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
                // Two attempts total: one retry. LLM calls are expensive and slow, so a
                // deep retry policy multiplies cost and latency for little gain - and with
                // a fallback chain already in place, the second provider is usually the
                // better answer than a third attempt at the first.
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(250))
                .retryExceptions(GatewayException.class)
                // Never retry what cannot succeed. A malformed request is still malformed
                // the second time, and retrying it just doubles the load during an
                // incident.
                .retryOnException(ResilienceConfig::isRetryable)
                .failAfterMaxAttempts(true)
                .build();

        return RetryRegistry.of(config);
    }

    // -----------------------------------------------------------------------
    // Metric binding.
    //
    // Creating the registries as plain beans above means Spring Boot's Resilience4j
    // auto-configuration never sees them, and so never wires them to Micrometer. Without
    // these binders there is no `resilience4j_circuitbreaker_state` series at all - and a
    // circuit opening is one of the few signals genuinely worth paging on, so losing it
    // silently is not acceptable.
    // -----------------------------------------------------------------------

    @Bean
    public MeterBinder circuitBreakerMetrics(CircuitBreakerRegistry registry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
    }

    @Bean
    public MeterBinder bulkheadMetrics(BulkheadRegistry registry) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
    }

    @Bean
    public MeterBinder retryMetrics(RetryRegistry registry) {
        return TaggedRetryMetrics.ofRetryRegistry(registry);
    }

    @Bean
    public MeterBinder timeLimiterMetrics(TimeLimiterRegistry registry) {
        return TaggedTimeLimiterMetrics.ofTimeLimiterRegistry(registry);
    }

    /**
     * Whether a failure reflects on the provider's health.
     *
     * <p>Client errors are excluded deliberately: one tenant sending a bad model name must
     * not be able to open the circuit for everyone else.
     */
    private static boolean countsAsProviderFailure(Throwable error) {
        if (error instanceof GatewayException gatewayException) {
            GatewayError gatewayError = gatewayException.error();
            return gatewayError instanceof GatewayError.UpstreamUnavailable
                    || gatewayError instanceof GatewayError.UpstreamTimeout
                    || gatewayError instanceof GatewayError.Internal;
        }
        // An unrecognised throwable is an infrastructure fault until proven otherwise.
        return true;
    }

    /** Reuses the taxonomy's own retryable flag, so the two can never disagree. */
    private static boolean isRetryable(Throwable error) {
        if (error instanceof GatewayException gatewayException) {
            GatewayError gatewayError = gatewayException.error();
            // Retryable AND a provider fault. A 429 from our own limiter is retryable but
            // retrying it here, inside the gateway, would defeat the limit.
            return gatewayError.retryable() && countsAsProviderFailure(error);
        }
        return true;
    }
}
