package io.vasanth.gateway.provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.LlmProvider;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.config.GatewayProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Selects an upstream provider and walks the fallback chain when one fails.
 *
 * <h2>Why fallback is ordered rather than load-balanced</h2>
 *
 * <p>Spreading traffic across providers sounds appealing but is wrong here: different
 * vendors produce different answers to the same prompt, so round-robin would make the cache
 * incoherent - an identical request could yield two different cached answers depending on
 * which vendor happened to serve the miss. A strict primary with ordered fallbacks keeps
 * output stable in normal operation and degrades only when the primary is genuinely down.
 *
 * <h2>Why the chain is bounded</h2>
 *
 * <p>Each provider is attempted at most once per request. An unbounded or retrying chain
 * turns one vendor's outage into an amplified storm against all the others, which is how a
 * partial failure becomes a total one. Retry policy belongs to Resilience4j, in one place.
 */
@Component
// final: the constructor throws on misconfiguration, which is correct fail-fast
// behaviour, but a throwing constructor on a non-final class is open to a finalizer
// attack - a subclass overriding finalize() can capture the partially built instance.
// Sealing the class removes the hazard outright, which is better than suppressing the
// warning. Nothing extends this.
public final class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<ProviderId, LlmProvider> providers;
    private final LlmProvider primary;
    private final List<LlmProvider> fallbacks;

    public ProviderRegistry(List<LlmProvider> discovered, GatewayProperties properties) {
        Map<ProviderId, LlmProvider> index = new LinkedHashMap<>();
        for (LlmProvider provider : discovered) {
            LlmProvider clash = index.put(provider.id(), provider);
            if (clash != null) {
                throw new IllegalStateException(
                        "two providers claim id '" + provider.id() + "': "
                                + clash.getClass().getName() + " and "
                                + provider.getClass().getName());
            }
        }
        this.providers = Map.copyOf(index);

        GatewayProperties.Providers config = properties.providers();
        this.primary = require(config.primary());
        this.fallbacks = config.fallback().stream().map(this::require).toList();

        log.info(
                "provider chain: primary={} fallbacks={} (available={})",
                primary.id(),
                fallbacks.stream().map(LlmProvider::id).toList(),
                providers.keySet());
    }

    /**
     * Completes a request, walking the fallback chain in order.
     *
     * <p>Only the final failure reaches the client, flattened to
     * {@link GatewayError.UpstreamUnavailable}. Intermediate failures are logged rather
     * than returned: a caller cannot act on "vendor B also failed", and naming vendors in
     * an error response leaks infrastructure detail.
     */
    public Mono<CompletionResponse> complete(CompletionRequest request) {
        List<LlmProvider> chain = chainFor(request);
        if (chain.isEmpty()) {
            return Mono.error(new GatewayException(new GatewayError.UpstreamUnavailable(
                    "no provider supports model " + request.model().value())));
        }

        Mono<CompletionResponse> attempt = chain.get(0).complete(request);
        for (int i = 1; i < chain.size(); i++) {
            LlmProvider failed = chain.get(i - 1);
            LlmProvider next = chain.get(i);
            attempt = attempt.onErrorResume(error -> {
                log.warn("provider {} failed, falling back to {}: {}",
                        failed.id(), next.id(), error.toString());
                return next.complete(request);
            });
        }

        return attempt.onErrorMap(
                error -> !(error instanceof GatewayException),
                error -> new GatewayException(
                        new GatewayError.UpstreamUnavailable("all providers failed")));
    }

    /**
     * Streams a request.
     *
     * <p>Fallback applies only to a failure that occurs <em>before the first delta is
     * emitted</em>. Once bytes have reached the client, switching provider would splice two
     * different answers together into incoherent output, so a mid-stream failure terminates
     * the stream instead. The {@link AtomicBoolean} is what distinguishes the two cases -
     * a bare {@code onErrorResume} would happily switch halfway through a sentence.
     */
    public Flux<String> stream(CompletionRequest request) {
        List<LlmProvider> chain = chainFor(request);
        if (chain.isEmpty()) {
            return Flux.error(new GatewayException(new GatewayError.UpstreamUnavailable(
                    "no provider supports model " + request.model().value())));
        }

        Flux<String> attempt = guarded(chain.get(0), request);
        for (int i = 1; i < chain.size(); i++) {
            LlmProvider failed = chain.get(i - 1);
            LlmProvider next = chain.get(i);
            attempt = attempt.onErrorResume(error -> {
                if (error instanceof MidStreamFailure) {
                    return Flux.error(error.getCause());
                }
                log.warn("provider {} failed before first token, falling back to {}: {}",
                        failed.id(), next.id(), error.toString());
                return guarded(next, request);
            });
        }

        return attempt.onErrorMap(
                MidStreamFailure.class, MidStreamFailure::getCause);
    }

    public LlmProvider byId(ProviderId id) {
        return providers.get(id);
    }

    /** Marks a failure as having occurred after output began, so fallback is skipped. */
    private static Flux<String> guarded(LlmProvider provider, CompletionRequest request) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        return provider.stream(request)
                .doOnNext(delta -> emitted.set(true))
                .onErrorMap(error -> emitted.get() ? new MidStreamFailure(error) : error);
    }

    /**
     * Primary first, then fallbacks, keeping only providers that support the model.
     *
     * <p>Plain synchronous list building. {@code supports()} is a simple predicate, so
     * wrapping this in a reactive pipeline would add machinery - and the temptation to
     * block on it - for no benefit.
     */
    private List<LlmProvider> chainFor(CompletionRequest request) {
        List<LlmProvider> chain = new ArrayList<>(1 + fallbacks.size());
        if (primary.supports(request.model())) {
            chain.add(primary);
        }
        for (LlmProvider fallback : fallbacks) {
            if (fallback.supports(request.model())) {
                chain.add(fallback);
            }
        }
        return chain;
    }

    private LlmProvider require(String id) {
        LlmProvider provider = providers.get(ProviderId.of(id));
        if (provider == null) {
            throw new IllegalStateException(
                    "configured provider '" + id + "' is not on the classpath; available: "
                            + providers.keySet());
        }
        return provider;
    }

    /** Internal marker; never escapes this class. */
    private static final class MidStreamFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MidStreamFailure(Throwable cause) {
            super(null, cause, false, false);
        }
    }
}
