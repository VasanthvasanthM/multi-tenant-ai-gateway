package io.vasanth.gateway.pipeline;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vasanth.gateway.budget.BudgetGate;
import io.vasanth.gateway.cache.Tier1Cache;
import io.vasanth.gateway.cache.Tier2SemanticCache;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.cache.PromptNormalizer;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.StreamUsage;
import io.vasanth.gateway.config.GatewayProperties;
import io.vasanth.gateway.embedding.EmbeddingService;
import io.vasanth.gateway.provider.ProviderRegistry;
import io.vasanth.gateway.ratelimit.TokenBucketGate;
import io.vasanth.gateway.telemetry.GatewayEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Orchestrates one completion request.
 *
 * <p>Stage order is not arbitrary - each stage sits where it rejects the most work for the
 * least cost:
 *
 * <ol>
 *   <li><b>Validate.</b> Pure CPU, no I/O. Rejecting an oversized prompt costs nothing.
 *   <li><b>Rate limit.</b> One Redis round trip. Must precede the cache: otherwise a tenant
 *       hammering a cached prompt consumes unlimited capacity for free - cheap for us, but
 *       still occupying connections and event-loop time paying traffic needs.
 *   <li><b>Budget.</b> Off by default. Also precedes the cache, and for a sharper reason: a
 *       cache hit spends the tenant's quota, so checking afterwards would serve an exhausted
 *       tenant every cached answer for free. Costs two Redis reads when enabled. The
 *       buffered path draws down the provider's own token count; the streaming path uses
 *       the provider's usage frame when one arrives and an estimate when it does not -
 *       see {@link #stream}.
 *   <li><b>Admission control.</b> In-flight gate; sheds before anything expensive.
 *   <li><b>Tier 1.</b> Redis exact hash, under 2 ms.
 *   <li><b>Tier 2.</b> Embed, then pgvector ANN, under 15 ms.
 *   <li><b>Upstream.</b> The only genuinely expensive step.
 *   <li><b>Store.</b> Both tiers, fire-and-forget, never delaying the response.
 * </ol>
 */
@Component
public class CompletionPipeline {

    private static final Logger log = LoggerFactory.getLogger(CompletionPipeline.class);

    /**
     * Prompt length beyond which Tier 2 is skipped entirely.
     *
     * <p><b>This guard prevents a real false-hit class, not a hypothetical one.</b>
     * all-MiniLM-L6-v2 accepts 256 tokens and <em>silently truncates</em> anything longer.
     * Two 4,000-word documents that are identical for their first 256 tokens and completely
     * different afterwards therefore produce the <em>same</em> embedding - a similarity of
     * 1.0 for two unrelated prompts. No threshold, however high, can catch that, because
     * the information distinguishing them was discarded before the comparison.
     *
     * <p>1,000 characters is a deliberately conservative stand-in for 256 tokens at roughly
     * 4 characters per token. Long prompts simply fall through to Tier 1 and upstream,
     * which is the correct behaviour: a missed hit costs money, a false hit returns the
     * wrong answer.
     */
    private static final int TIER2_MAX_PROMPT_CHARS = 1_000;

    private final Tier1Cache tier1;
    private final Tier2SemanticCache tier2;
    private final EmbeddingService embeddings;
    private final ProviderRegistry providers;
    private final TokenBucketGate rateLimitGate;
    private final BudgetGate budgetGate;
    private final GatewayProperties.Limits limits;
    private final GatewayProperties.Cache cacheConfig;

    /**
     * Absent when {@code gateway.kafka.enabled} is false.
     *
     * <p>Optional rather than a no-op implementation so that running without a broker is
     * a visible, deliberate state rather than something that silently looks like it is
     * publishing and is not.
     */
    private final Optional<GatewayEventPublisher> events;
    private final Clock clock;

    private final AtomicInteger inFlight = new AtomicInteger();

    private final Counter shed;
    private final Counter tier2Skipped;
    private final Timer endToEnd;

    public CompletionPipeline(
            Tier1Cache tier1,
            Tier2SemanticCache tier2,
            EmbeddingService embeddings,
            ProviderRegistry providers,
            TokenBucketGate rateLimitGate,
            BudgetGate budgetGate,
            Optional<GatewayEventPublisher> events,
            Clock clock,
            GatewayProperties properties,
            MeterRegistry meterRegistry) {
        this.tier1 = tier1;
        this.tier2 = tier2;
        this.embeddings = embeddings;
        this.providers = providers;
        this.rateLimitGate = rateLimitGate;
        this.budgetGate = budgetGate;
        this.events = events;
        this.clock = clock;
        this.limits = properties.limits();
        this.cacheConfig = properties.cache();

        this.shed = Counter.builder("gateway.requests.rejected")
                .tag("reason", "overloaded").register(meterRegistry);
        this.tier2Skipped = Counter.builder("gateway.cache.tier2_skipped")
                .register(meterRegistry);
        this.endToEnd = Timer.builder("gateway.request.duration")
                .publishPercentileHistogram().register(meterRegistry);

        Gauge.builder("gateway.requests.in_flight", inFlight, AtomicInteger::get)
                .register(meterRegistry);
    }

    /** Runs the full pipeline. */
    public Mono<CompletionResponse> execute(CompletionRequest request) {
        long startNanos = System.nanoTime();

        String requestId = UUID.randomUUID().toString();

        return validate(request)
                // Mono.defer, not a bare call. `then(x)` evaluates its argument when the
                // chain is ASSEMBLED, so rateLimitGate.check(...) would be invoked even
                // when validation has already failed. That is harmless only for as long
                // as every collaborator returns a cold publisher - a property nothing
                // enforces. Deferring makes the laziness explicit instead of accidental.
                .then(Mono.defer(() -> rateLimitGate.check(request.tenantId())))
                .then(Mono.defer(() -> budgetGate.check(request.tenantId())))
                .then(Mono.defer(() -> withAdmissionControl(serve(request))))
                .doOnSuccess(response -> {
                    endToEnd.record(Duration.ofNanos(System.nanoTime() - startNanos));
                    if (response != null) {
                        // Fire-and-forget. Publishing happens after the response is
                        // assembled and never gates it - see GatewayEventPublisher for
                        // why this path deliberately forgoes the outbox.
                        events.ifPresent(publisher ->
                                publisher.publishUsage(requestId, request, response));

                        // Also fire-and-forget, and for the same reason: the tenant has a
                        // valid answer, so a Redis write must not delay or fail it. What
                        // is risked is enforcement accuracy, not billing accuracy - the
                        // exact figure is written to usage_ledger by the metering service
                        // from the event published just above, and that ledger is
                        // idempotent and authoritative.
                        //
                        // subscribe() with no error handler would route a failure to
                        // Reactor's global onErrorDropped hook and log a stack trace per
                        // request during a Redis outage; TokenBudgetLedger.record already
                        // resolves its own errors to an empty Mono and one WARN.
                        budgetGate.record(request.tenantId(), response.usage().total())
                                .subscribe();
                    }
                });
    }

    /**
     * Streaming variant.
     *
     * <p>Deliberately consults neither tier and populates neither. Reassembling a full
     * response from deltas in order to cache it would mean buffering every streamed
     * response in memory, and at high concurrency that memory cost outweighs the hit rate
     * it buys. The cache key ignores the {@code stream} flag precisely so that a buffered
     * request can still hit an entry a streamed one would have produced.
     *
     * <h2>The budget drawdown prefers the provider's own count</h2>
     *
     * <p>Streaming requests ask for {@code stream_options.include_usage}, so an
     * OpenAI-compatible vendor sends a terminal usage frame with the real token count. That
     * figure is used when it arrives, via a cell carried in the Reactor Context - see
     * {@link io.vasanth.gateway.common.llm.StreamUsage} for why the Context rather than a
     * parameter threaded through the registry and the resilience decorator.
     *
     * <p>When no frame arrives - Ollama, the mock provider, a vendor that ignores the
     * option, or a stream that ended early - the drawdown falls back to an estimate from
     * character count. Deltas are counted as they pass, their lengths summed and their
     * content never retained. The estimate is coarse, and the alternative was drawing down
     * nothing at all, which left a tenant that streams exclusively bounded by the rate
     * limiter and not by its budget. {@code usage_ledger} remains the authoritative
     * record either way.
     *
     * <p>{@code doFinally}, not {@code doOnComplete}: a client that disconnects mid-stream
     * has still consumed everything delivered up to that point, and cancellation is the
     * normal way a stream ends. Recording only on completion would let a caller avoid the
     * drawdown entirely by hanging up, which is a trivially exploitable hole.
     */
    public Flux<String> stream(CompletionRequest request) {
        AtomicLong characters = new AtomicLong();
        AtomicLong reportedTokens = new AtomicLong();

        return validate(request)
                .then(Mono.defer(() -> rateLimitGate.check(request.tenantId())))
                .then(Mono.defer(() -> budgetGate.check(request.tenantId())))
                .thenMany(Flux.defer(() -> providers.stream(request)))
                .doOnNext(delta -> characters.addAndGet(delta.length()))
                .doFinally(signal -> {
                    long exact = reportedTokens.get();
                    if (exact > 0L) {
                        // The provider returned a usage frame, so no estimate is needed.
                        budgetGate.record(request.tenantId(), exact).subscribe();
                    } else {
                        // No usage frame - Ollama, the mock, a vendor that ignores
                        // stream_options, or a stream that ended before the frame
                        // arrived. Estimating is what keeps a stream-only tenant bounded
                        // by its budget at all.
                        budgetGate.recordStreamed(request.tenantId(), characters.get())
                                .subscribe();
                    }
                })
                // The cell the provider writes the real count into. Context flows from
                // subscriber to source, so this reaches whichever provider ends up
                // serving without the registry or the resilience decorator knowing.
                .contextWrite(StreamUsage.withCell(reportedTokens));
    }

    private Mono<Void> validate(CompletionRequest request) {
        int totalCodePoints = 0;
        for (Message message : request.messages()) {
            totalCodePoints += PromptNormalizer.lengthInCodePoints(message.content());
        }

        if (totalCodePoints > limits.maxPromptCodePoints()) {
            return Mono.error(new GatewayException(new GatewayError.PayloadTooLarge(
                    totalCodePoints, limits.maxPromptCodePoints())));
        }
        return Mono.empty();
    }

    /**
     * Bounded admission control.
     *
     * <p>{@code doFinally} rather than {@code doOnTerminate}: the permit must be released
     * on cancellation too, which happens whenever a client disconnects mid-request. Missing
     * that leaks a permit per dropped connection and the gateway slowly strangles itself.
     */
    private Mono<CompletionResponse> withAdmissionControl(Mono<CompletionResponse> work) {
        return Mono.defer(() -> {
            if (inFlight.incrementAndGet() > limits.maxInFlight()) {
                inFlight.decrementAndGet();
                shed.increment();
                return Mono.error(new GatewayException(
                        new GatewayError.Overloaded(limits.maxInFlight())));
            }
            return work.doFinally(signal -> inFlight.decrementAndGet());
        });
    }

    private Mono<CompletionResponse> serve(CompletionRequest request) {
        CacheKey key = CacheKey.derive(request);
        String promptText = renderForEmbedding(request);

        return tier1.get(key)
                .switchIfEmpty(Mono.defer(() -> tryTier2(request, key, promptText)))
                .switchIfEmpty(Mono.defer(() -> callUpstream(request, key, promptText)));
    }

    /**
     * Tier-2 lookup, with every precondition checked before an embedding is computed.
     *
     * <p>Order matters here for cost as well as correctness: embedding costs ~6 ms of CPU,
     * so each guard that can reject without it is checked first.
     */
    private Mono<CompletionResponse> tryTier2(
            CompletionRequest request, CacheKey key, String promptText) {

        if (!cacheConfig.tier2Enabled()) {
            return Mono.empty();
        }
        if (!request.isSemanticCacheEligible()) {
            // Non-deterministic sampling, or tool definitions present. Either way the
            // caller asked for something a similarity match cannot honour.
            tier2Skipped.increment();
            return Mono.empty();
        }
        if (promptText.length() > TIER2_MAX_PROMPT_CHARS) {
            // See TIER2_MAX_PROMPT_CHARS: the model truncates, and truncation turns two
            // different long prompts into one identical vector.
            tier2Skipped.increment();
            log.debug("tier-2 skipped: prompt of {} chars exceeds the truncation-safe limit",
                    promptText.length());
            return Mono.empty();
        }

        return embeddings.embed(promptText)
                .flatMap(vector -> tier2.lookup(
                                request.tenantId(),
                                request.model(),
                                vector,
                                embeddings.modelId(),
                                embeddings.isSemanticallyMeaningful())
                        // A Tier-2 hit is promoted into Tier 1 so the next occurrence of
                        // this exact prompt costs 2 ms instead of another vector search.
                        .flatMap(hit -> tier1.put(key, hit).thenReturn(hit)))
                .onErrorResume(error -> {
                    // Embedding failure must not fail the request; fall through to upstream.
                    log.warn("tier-2 path failed, falling through: {}", error.toString());
                    return Mono.empty();
                });
    }

    private Mono<CompletionResponse> callUpstream(
            CompletionRequest request, CacheKey key, String promptText) {

        return providers
                .complete(request)
                .timeout(limits.upstreamTimeout())
                .onErrorMap(
                        java.util.concurrent.TimeoutException.class,
                        timeout -> new GatewayException(
                                new GatewayError.UpstreamTimeout(limits.upstreamTimeout())))
                .flatMap(response -> storeQuietly(key, request, promptText, response)
                        .thenReturn(response));
    }

    /**
     * Writes to both tiers without delaying the response.
     *
     * <p>Tier 2's write needs an embedding, which costs CPU. It is computed here rather
     * than reused from the lookup because the lookup may have been skipped entirely - and
     * in P6 this whole block is replaced by a Kafka event, moving the cost off this process
     * altogether and into the indexer service.
     */
    private Mono<Void> storeQuietly(
            CacheKey key,
            CompletionRequest request,
            String promptText,
            CompletionResponse response) {

        if (response.servedBy() != CacheTier.UPSTREAM) {
            // Already came from a cache. Re-storing would only refresh its TTL and let a
            // popular entry live forever without ever being revalidated upstream.
            return Mono.empty();
        }

        Mono<Void> writeTier1 = tier1.put(key, response);

        if (!cacheConfig.tier2Enabled()
                || !request.isSemanticCacheEligible()
                || promptText.length() > TIER2_MAX_PROMPT_CHARS) {
            return writeTier1;
        }

        Mono<Void> writeTier2 = embeddings.embed(promptText)
                .flatMap(vector -> {
                    if (events.isPresent()) {
                        // Hand the write to the indexer. HNSW insertion is expensive and
                        // write-amplifying; keeping it off this path is the whole reason
                        // that service exists. The vector goes with the event so the
                        // indexer never recomputes it.
                        events.get().publishCacheWrite(
                                key, request, promptText, vector, embeddings.modelId(),
                                response, Instant.now(clock).plus(cacheConfig.tier2Ttl()));
                        return Mono.<Void>empty();
                    }
                    // No broker configured: write directly, so Tier 2 still works in the
                    // single-service local setup.
                    return tier2.store(
                            key, request.model(), promptText, vector,
                            embeddings.modelId(), response);
                })
                .onErrorResume(error -> {
                    log.debug("tier-2 store skipped: {}", error.toString());
                    return Mono.empty();
                })
                .then();

        return Mono.when(writeTier1, writeTier2);
    }

    /**
     * Renders a conversation into the single string that gets embedded.
     *
     * <p>Roles are included because "user: reset my password" and
     * "assistant: reset my password" are different things, and embedding only the raw text
     * would collapse them. The whole conversation is included rather than just the final
     * turn, because a follow-up like "and the second one?" is meaningless without what
     * came before.
     */
    private static String renderForEmbedding(CompletionRequest request) {
        StringJoiner joiner = new StringJoiner("\n");
        for (Message message : request.messages()) {
            joiner.add(message.role().wireValue() + ": "
                    + PromptNormalizer.normalize(message.content()));
        }
        return joiner.toString();
    }

    /** Current in-flight count. Exposed for tests and the readiness probe. */
    public int inFlight() {
        return inFlight.get();
    }
}
