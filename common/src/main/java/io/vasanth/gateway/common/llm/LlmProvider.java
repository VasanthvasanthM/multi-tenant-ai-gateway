package io.vasanth.gateway.common.llm;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service provider interface for an upstream LLM.
 *
 * <p>Implementations are the only part of the system that performs network I/O to a model
 * vendor. Keeping that behind one narrow interface is what makes three things possible:
 * benchmarking against a deterministic mock, switching vendors by configuration, and
 * falling back from one vendor to another when a circuit opens.
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 *   <li>{@code MockLlmProvider} - deterministic, with configurable latency and failure
 *       injection. <b>Every load test and CI run uses this</b>, because measuring against a
 *       real vendor measures the vendor's queue, not this gateway.
 *   <li>{@code OpenAiCompatibleProvider} - one adapter with a configurable base URL. Groq,
 *       OpenRouter, Together, Google AI Studio and llama.cpp all speak this wire format, so
 *       changing vendor is configuration rather than code.
 *   <li>{@code OllamaProvider} - present and documented, off by default. On a CPU-only
 *       machine it yields single-digit tokens per second, which would distort any benchmark
 *       it took part in.
 * </ul>
 *
 * <h2>Contract</h2>
 *
 * <p>Implementations must be non-blocking. A blocking call here occupies a Netty event loop
 * thread and stalls every other request sharing it - the single most damaging mistake
 * available in a WebFlux application. Anything genuinely blocking or CPU-bound must be
 * moved onto a bounded scheduler by the implementation, not left for the caller to discover.
 *
 * <p>Implementations must <b>not</b> apply their own retries, timeouts or circuit breaking.
 * Resilience4j owns all of that one layer up, and a second, invisible retry policy inside a
 * provider turns one upstream failure into an unbounded multiplier.
 */
public interface LlmProvider {

    /** Stable identifier, used for metric labels and fallback configuration. */
    ProviderId id();

    /**
     * Performs a non-streaming completion.
     *
     * @param request the request; already validated and authorised
     * @return the response, or an error signal carrying a
     *     {@link io.vasanth.gateway.common.error.GatewayException}
     */
    Mono<CompletionResponse> complete(CompletionRequest request);

    /**
     * Performs a streaming completion, emitting incremental text deltas.
     *
     * <p>Each element is a delta, not a cumulative string: concatenating every element in
     * order yields the full response. Emitting cumulative snapshots instead would make
     * bandwidth quadratic in response length.
     *
     * <p>The returned {@link Flux} must honour cancellation. When a client disconnects
     * mid-stream, the subscription is cancelled and the implementation is expected to abort
     * its upstream call - otherwise the gateway keeps paying for tokens nobody will read.
     */
    Flux<String> stream(CompletionRequest request);

    /**
     * Reports whether the provider currently looks usable.
     *
     * <p>Advisory only. It feeds readiness reporting and fallback ordering; it is not a
     * precondition for {@link #complete}, because checking before every call would double
     * the request count and still race.
     */
    Mono<Boolean> isHealthy();

    /**
     * Whether this provider can serve the given model.
     *
     * <p>Used to build the fallback chain: there is no point failing over to a provider that
     * has never heard of the model being requested.
     */
    boolean supports(io.vasanth.gateway.common.model.ModelId model);
}
