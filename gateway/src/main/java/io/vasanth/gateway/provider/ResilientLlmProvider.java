package io.vasanth.gateway.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.LlmProvider;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.model.ModelId;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wraps a provider in circuit breaking, bulkheading, timeouts and retry.
 *
 * <p>A decorator rather than logic inside each provider: resilience policy is identical
 * for every upstream, and duplicating it would guarantee the copies drift. It also keeps
 * the providers honest - they contain protocol translation and nothing else, which is why
 * they can be tested against WireMock without any of this machinery.
 *
 * <h2>Operator order is not arbitrary</h2>
 *
 * <p>In Reactor the <em>last</em> {@code transformDeferred} applied is the outermost, so
 * the chain below reads inside-out. The intended nesting is:
 *
 * <pre>{@code
 * Retry ( CircuitBreaker ( TimeLimiter ( Bulkhead ( call ) ) ) )
 * }</pre>
 *
 * <p>Each boundary is where it is for a reason:
 *
 * <ul>
 *   <li><b>Bulkhead innermost</b> so a permit is held only for the actual call. Outside the
 *       time limiter it would stay held during the timeout wait, and a slow upstream would
 *       exhaust permits rather than fail fast.
 *   <li><b>TimeLimiter inside the circuit breaker</b> so a timeout is <em>recorded</em> as a
 *       failure. Outside it, the breaker would never see the slowest failures - the ones
 *       most worth opening on.
 *   <li><b>Retry outermost</b> so a retry gets a fresh breaker check. Inside the breaker,
 *       retries would hammer an upstream the breaker has already judged unhealthy.
 * </ul>
 */
public final class ResilientLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmProvider.class);

    private final LlmProvider delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final Retry retry;

    public ResilientLlmProvider(
            LlmProvider delegate,
            CircuitBreaker circuitBreaker,
            Bulkhead bulkhead,
            TimeLimiter timeLimiter,
            Retry retry) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.bulkhead = bulkhead;
        this.timeLimiter = timeLimiter;
        this.retry = retry;

        circuitBreaker.getEventPublisher().onStateTransition(event ->
                log.warn("circuit breaker '{}' {} -> {}",
                        delegate.id(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    @Override
    public ProviderId id() {
        return delegate.id();
    }

    @Override
    public Mono<CompletionResponse> complete(CompletionRequest request) {
        return delegate.complete(request)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(TimeLimiterOperator.of(timeLimiter))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))
                .onErrorMap(error -> translate(error, delegate.id()));
    }

    /**
     * Streaming is protected by the bulkhead and the breaker, but <b>not</b> by the time
     * limiter.
     *
     * <p>A time limiter on a stream would cancel a perfectly healthy long generation
     * partway through - the whole point of streaming is that the total duration is
     * unbounded while individual tokens arrive promptly. Retry is also omitted: once bytes
     * have reached the client there is nothing to retry into.
     */
    @Override
    public Flux<String> stream(CompletionRequest request) {
        return delegate.stream(request)
                .transformDeferred(BulkheadOperator.of(bulkhead))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(error -> translate(error, delegate.id()));
    }

    @Override
    public Mono<Boolean> isHealthy() {
        // Reports the breaker's view first. A provider whose circuit is open is not
        // healthy however cheerfully its own health endpoint answers.
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            return Mono.just(false);
        }
        return delegate.isHealthy();
    }

    @Override
    public boolean supports(ModelId model) {
        return delegate.supports(model);
    }

    /** Exposed so the health endpoint and tests can observe breaker state. */
    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private static Throwable translate(Throwable error, ProviderId providerId) {
        if (error instanceof CallNotPermittedException) {
            // The breaker rejected the call without attempting it. Distinct from an
            // observed failure, so a dashboard can tell "we are shedding deliberately"
            // from "the vendor is down".
            return new GatewayException(new GatewayError.CircuitOpen(providerId.value()));
        }
        if (error instanceof BulkheadFullException) {
            return new GatewayException(new GatewayError.Overloaded(0));
        }
        if (error instanceof java.util.concurrent.TimeoutException) {
            return new GatewayException(new GatewayError.UpstreamTimeout(
                    java.time.Duration.ZERO));
        }
        return error;
    }
}
