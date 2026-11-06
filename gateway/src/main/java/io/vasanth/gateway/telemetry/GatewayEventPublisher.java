package io.vasanth.gateway.telemetry;

import java.time.Clock;
import java.time.Instant;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.event.GatewayEvents;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.embedding.Embedding;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Publishes telemetry from the data plane. Fire-and-forget, always.
 *
 * <h2>This is the one place the outbox pattern is deliberately NOT used</h2>
 *
 * <p>The control plane and the metering service both use a transactional outbox, because
 * for them a lost event means a lost API-key revocation or an unbilled request. Here the
 * trade runs the other way.
 *
 * <p>An outbox requires a database write in the same transaction as the business change.
 * On this path there is no database transaction to join - the response came from Redis or
 * from a vendor - so adding one would mean a synchronous insert on a path budgeted at
 * 2 ms, roughly doubling it. Paying that on every request to guarantee delivery of a
 * telemetry event is the wrong trade: a dropped usage event costs a rounding error in
 * billing, while 3 ms on every request costs the latency target the product is built
 * around.
 *
 * <p>So events are published asynchronously and drops are counted rather than prevented.
 * {@code gateway.events.dropped} is the metric that makes the cost of this decision
 * visible instead of silent - if it is ever non-trivial, the trade needs revisiting.
 *
 * <h2>Nothing here can fail a request</h2>
 *
 * <p>Every publish is subscribed on its own, errors are swallowed, and the caller gets
 * back a {@code Mono} that completes immediately. Kafka being down must degrade telemetry,
 * never availability.
 */
@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "true")
public class GatewayEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GatewayEventPublisher.class);

    private final KafkaSender<String, String> sender;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final Counter published;
    private final Counter dropped;

    public GatewayEventPublisher(
            KafkaSender<String, String> sender,
            ObjectMapper objectMapper,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.sender = sender;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.published = Counter.builder("gateway.events.published").register(meterRegistry);
        // The honest accounting of the decision documented above.
        this.dropped = Counter.builder("gateway.events.dropped").register(meterRegistry);
    }

    /** Emits a usage event for billing and quota drawdown. */
    public void publishUsage(
            String requestId, CompletionRequest request, CompletionResponse response) {

        GatewayEvents.UsageEvent event = new GatewayEvents.UsageEvent(
                GatewayEvents.SCHEMA_VERSION,
                requestId,
                request.tenantId().value(),
                request.model().value(),
                response.servedBy().label(),
                response.provider().value(),
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.latency().toMillis(),
                Instant.now(clock));

        emit(GatewayEvents.TOPIC_USAGE, request.tenantId().value(), event);
    }

    /** Emits a cache-write event so the indexer can store the vector. */
    public void publishCacheWrite(
            CacheKey cacheKey,
            CompletionRequest request,
            String promptText,
            Embedding embedding,
            String embeddingModelId,
            CompletionResponse response,
            Instant expiresAt) {

        GatewayEvents.CacheWriteEvent event = new GatewayEvents.CacheWriteEvent(
                GatewayEvents.SCHEMA_VERSION,
                request.tenantId().value(),
                cacheKey.hex(),
                request.model().value(),
                embeddingModelId,
                promptText,
                // The vector travels with the event so the indexer never recomputes it -
                // that would spend the same 6 ms of CPU twice for an identical result.
                embedding.toPgVector(),
                response.content(),
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.provider().value(),
                response.createdAt(),
                expiresAt);

        emit(GatewayEvents.TOPIC_CACHE_WRITE, request.tenantId().value(), event);
    }

    /**
     * Identifies the caller and outcome of one request, for the audit log.
     *
     * <p>A record rather than four more String parameters. The previous signature took
     * six arguments of which four were Strings, and nothing but argument order
     * distinguished {@code apiKeyFingerprint} from {@code sourceAddress} from
     * {@code outcome} - a transposition the compiler could never catch.
     *
     * @param requestId unique per request, the idempotency key for the whole pipeline
     * @param apiKeyFingerprint first 16 hex characters of the key's SHA-256; never the key
     * @param sourceAddress caller IP, as seen after proxy headers are resolved
     * @param outcome HTTP status or error code
     */
    public record AuditContext(
            String requestId,
            String apiKeyFingerprint,
            String sourceAddress,
            String outcome) {
    }

    /** Emits an audit entry. */
    public void publishAudit(
            AuditContext context, CompletionRequest request, CompletionResponse response) {

        GatewayEvents.AuditEvent event = new GatewayEvents.AuditEvent(
                GatewayEvents.SCHEMA_VERSION,
                context.requestId(),
                request.tenantId().value(),
                context.apiKeyFingerprint(),
                request.model().value(),
                response == null ? "none" : response.servedBy().label(),
                context.outcome(),
                context.sourceAddress(),
                Instant.now(clock));

        emit(GatewayEvents.TOPIC_AUDIT, request.tenantId().value(), event);
    }

    /**
     * Serialises and sends, detached from the caller.
     *
     * <p>The partition key is always the tenant id. That gives per-tenant ordering, which
     * the metering consumer needs so usage is applied in the order it occurred, and it
     * matches the sharding key used everywhere else in the system.
     *
     * <p>Subscribed on {@code boundedElastic} rather than inline: serialisation is CPU
     * work and the send allocates, and neither belongs on the event loop that is about to
     * write the response.
     */
    private void emit(String topic, String partitionKey, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(topic, partitionKey, payload);

            sender.send(Mono.just(SenderRecord.create(record, partitionKey)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(result -> published.increment())
                    .doOnError(error -> {
                        dropped.increment();
                        log.debug("dropped {} event for tenant {}: {}",
                                topic, partitionKey, error.toString());
                    })
                    .onErrorResume(error -> Mono.empty())
                    .subscribe();

        } catch (Exception e) {
            // Serialisation failure, or the sender refusing work. Counted and dropped -
            // a telemetry problem must never surface to the caller.
            dropped.increment();
            log.debug("could not emit {} event: {}", topic, e.toString());
        }
    }
}
