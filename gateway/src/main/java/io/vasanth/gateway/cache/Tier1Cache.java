package io.vasanth.gateway.cache;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.config.GatewayProperties;
import reactor.core.publisher.Mono;

/**
 * Tier 1: exact SHA-256 prompt-hash lookup in Redis. Target p99 under 2 ms.
 *
 * <h2>The cache is never a dependency</h2>
 *
 * <p>Every method here swallows infrastructure failure. A Redis outage must degrade this
 * system to "slower and more expensive", never to "down". Propagating a connection error
 * from a cache read would convert a performance optimisation into a single point of
 * failure, which is precisely backwards.
 *
 * <p>The same reasoning covers malformed entries: a value that cannot be deserialised is
 * reported as a miss. That is what lets {@link CachedCompletion} change format without a
 * coordinated flush - old entries simply stop matching and expire on their own.
 */
@Component
public class Tier1Cache {

    private static final Logger log = LoggerFactory.getLogger(Tier1Cache.class);

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final GatewayProperties.Cache config;

    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private final Counter writes;
    private final Timer lookupTimer;

    public Tier1Cache(
            ReactiveStringRedisTemplate redis,
            ObjectMapper objectMapper,
            GatewayProperties properties,
            MeterRegistry meterRegistry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.config = properties.cache();

        String tier = CacheTier.TIER1_EXACT.label();
        this.hits = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "hit").register(meterRegistry);
        this.misses = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "miss").register(meterRegistry);
        this.errors = Counter.builder("gateway.cache.lookup")
                .tag("tier", tier).tag("result", "error").register(meterRegistry);
        this.writes = Counter.builder("gateway.cache.write")
                .tag("tier", tier).register(meterRegistry);
        this.lookupTimer = Timer.builder("gateway.cache.lookup.duration")
                .tag("tier", tier)
                // Percentile histogram rather than client-side quantiles: Prometheus can
                // then aggregate p99 correctly across replicas. Averaging pre-computed
                // per-pod quantiles, which is the usual mistake, is mathematically invalid.
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * Looks up an exact match.
     *
     * @return the cached response, or an empty {@link Mono} for a miss, a malformed entry,
     *     or any Redis failure
     */
    public Mono<CompletionResponse> get(CacheKey key) {
        if (!config.tier1Enabled()) {
            return Mono.empty();
        }

        long startNanos = System.nanoTime();

        return redis.opsForValue()
                .get(key.redisKey())
                .flatMap(raw -> deserialize(raw, key, startNanos))
                .doOnSuccess(found -> {
                    if (found == null) {
                        misses.increment();
                    }
                })
                .onErrorResume(error -> {
                    errors.increment();
                    log.warn("tier-1 lookup failed for {}: {}", key.shortForm(), error.toString());
                    return Mono.empty();
                });
    }

    /**
     * Stores a completion.
     *
     * <p>Returns {@code Mono<Void>} that completes even on failure: a write error must not
     * fail a request whose answer has already been produced. The caller has a valid response
     * in hand, and losing the opportunity to cache it is strictly less bad than discarding
     * work the user already paid for.
     */
    public Mono<Void> put(CacheKey key, CompletionResponse response) {
        if (!config.tier1Enabled()) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> objectMapper.writeValueAsString(
                        CachedCompletion.from(response)))
                .flatMap(json -> redis.opsForValue()
                        .set(key.redisKey(), json, config.tier1Ttl()))
                .doOnSuccess(stored -> writes.increment())
                .onErrorResume(error -> {
                    log.warn("tier-1 write failed for {}: {}", key.shortForm(), error.toString());
                    return Mono.empty();
                })
                .then();
    }

    /** Removes a single entry. Used by the control plane's per-tenant purge. */
    public Mono<Boolean> evict(CacheKey key) {
        return redis.opsForValue()
                .delete(key.redisKey())
                .onErrorResume(error -> {
                    log.warn("tier-1 evict failed for {}: {}", key.shortForm(), error.toString());
                    return Mono.just(false);
                });
    }

    private Mono<CompletionResponse> deserialize(String raw, CacheKey key, long startNanos) {
        return Mono.fromCallable(() -> objectMapper.readValue(raw, CachedCompletion.class))
                .flatMap(entry -> {
                    if (!entry.isReadable()) {
                        // An entry written by an older schema. Treated as a miss so the
                        // format can evolve without a coordinated cache flush.
                        misses.increment();
                        return Mono.<CompletionResponse>empty();
                    }
                    Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
                    lookupTimer.record(elapsed);
                    hits.increment();
                    return Mono.just(entry.toResponse(CacheTier.TIER1_EXACT, elapsed));
                })
                .onErrorResume(error -> {
                    // Corrupt JSON. Count it as an error so it is visible, but still serve
                    // the request by falling through to upstream.
                    errors.increment();
                    log.warn(
                            "tier-1 entry for {} could not be parsed, treating as miss: {}",
                            key.shortForm(),
                            error.toString());
                    return Mono.empty();
                });
    }
}
