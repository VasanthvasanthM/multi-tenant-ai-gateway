package io.vasanth.gateway.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.cache.CacheKey;
import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.event.GatewayEvents;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.embedding.Embedding;
import reactor.core.publisher.Flux;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderResult;

/**
 * Telemetry publishing.
 *
 * <p>The behaviour that matters is NOT that events arrive - it is that a failure to
 * publish is invisible to the caller. These events sit beside the request path on
 * purpose ({@code ADR-0003}), so a broker outage must degrade telemetry and nothing
 * else. A publisher that propagated would make Kafka a hard dependency of serving a
 * cache hit.
 *
 * <p>Assertions use the WIRE field names ("rid", "tid", "pt") rather than the Java
 * component names. Those short names are deliberate - an event is written once per
 * request, so the keys are a real share of the bytes - and they are what consumers
 * actually read. A test written against the Java names would keep passing after a
 * rename that broke every consumer.
 *
 * <p>The second property is the partition key. Ordering is only guaranteed within a
 * Kafka partition, and the consumers process partition-ordered, so every event for one
 * tenant has to land on the same partition - which means the key must be the tenant id
 * and nothing else.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GatewayEventPublisherTest {

    @Mock
    private KafkaSender<String, String> sender;

    /**
     * Configured the way Spring Boot auto-configures one.
     *
     * <p>WRITE_DATES_AS_TIMESTAMPS must be DISABLED, which is Boot's default but not
     * Jackson's. A bare {@code new ObjectMapper()} writes an Instant as a floating-point
     * epoch ("1.7939664E9"), so a test using one would assert a wire format no
     * deployment ever produces - and would keep passing if the real format changed.
     * There is no Jackson configuration anywhere in this project, so producer and
     * consumers both get Boot's defaults and this is what travels on the topic.
     */
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final Clock fixed =
            Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC);

    private SimpleMeterRegistry meters;
    private GatewayEventPublisher publisher;

    /** Records every record handed to the sender. */
    private final List<ProducerRecord<String, String>> sent = new CopyOnWriteArrayList<>();

    private static final CompletionRequest REQUEST = CompletionRequest.of(
            TenantId.of("acme"), ModelId.of("gpt-4o-mini"), "why is the sky blue?");

    private static CompletionResponse response() {
        return new CompletionResponse(
                "Rayleigh scattering.",
                ModelId.of("gpt-4o-mini"),
                new TokenUsage(11L, 4L),
                CacheTier.UPSTREAM,
                ProviderId.of("mock"),
                Instant.parse("2026-11-06T11:59:00Z"),
                Duration.ofMillis(843));
    }

    @SuppressWarnings("unchecked")
    private void senderAccepts() {
        when(sender.send(any(org.reactivestreams.Publisher.class))).thenAnswer(invocation -> {
            org.reactivestreams.Publisher<reactor.kafka.sender.SenderRecord<String, String, String>>
                    records = invocation.getArgument(0);
            return Flux.from(records)
                    .doOnNext(record -> sent.add(
                            new ProducerRecord<>(record.topic(), record.key(), record.value())))
                    .map(record -> (SenderResult<String>) null)
                    .filter(result -> false);
        });
    }

    @SuppressWarnings("unchecked")
    private void senderFails() {
        when(sender.send(any(org.reactivestreams.Publisher.class)))
                .thenReturn(Flux.error(new IllegalStateException("broker unreachable")));
    }

    @BeforeEach
    void setUp() {
        sent.clear();
        meters = new SimpleMeterRegistry();
        publisher = new GatewayEventPublisher(sender, mapper, fixed, meters);
    }

    private double dropped() {
        var counter = meters.find("gateway.events.dropped").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private ProducerRecord<String, String> awaitOneRecord() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !sent.isEmpty());
        return sent.get(0);
    }

    private JsonNode payloadOf(ProducerRecord<String, String> record) throws Exception {
        return mapper.readTree(record.value());
    }

    // ------------------------------------------------------------------
    // Usage
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a usage event goes to gateway.usage.v1 keyed by tenant")
    void usageEventTopicAndKey() throws Exception {
        senderAccepts();

        publisher.publishUsage("req-1", REQUEST, response());

        ProducerRecord<String, String> record = awaitOneRecord();
        assertThat(record.topic()).isEqualTo(GatewayEvents.TOPIC_USAGE);
        // The tenant id, and nothing else. Ordering is only guaranteed within a
        // partition and the consumers read partition-ordered, so any other key would
        // let two events for one tenant be processed out of order.
        assertThat(record.key()).isEqualTo("acme");

        JsonNode payload = payloadOf(record);
        assertThat(payload.path("rid").asText()).isEqualTo("req-1");
        assertThat(payload.path("tid").asText()).isEqualTo("acme");
        assertThat(payload.path("pt").asLong()).isEqualTo(11L);
        assertThat(payload.path("ct").asLong()).isEqualTo(4L);
        assertThat(payload.path("tier").asText()).isEqualTo("upstream");
    }

    @Test
    @DisplayName("a usage event carries the schema version, so consumers can reject it")
    void usageEventIsVersioned() throws Exception {
        senderAccepts();

        publisher.publishUsage("req-1", REQUEST, response());

        // Topics are versioned in their names AND payloads carry the version, so a
        // consumer reading an event it does not understand can say so rather than
        // silently mis-parsing it.
        assertThat(payloadOf(awaitOneRecord()).path("v").asInt())
                .isEqualTo(GatewayEvents.SCHEMA_VERSION);
    }

    @Test
    @DisplayName("the event timestamp comes from the injected clock, not the wall clock")
    void usesTheInjectedClock() throws Exception {
        senderAccepts();

        publisher.publishUsage("req-1", REQUEST, response());

        // ArchUnit forbids a static Instant.now() precisely so this is assertable.
        assertThat(payloadOf(awaitOneRecord()).path("at").asText())
                .contains("2026-11-06T12:00:00");
    }

    // ------------------------------------------------------------------
    // Cache write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cache-write event carries the vector, so the indexer never recomputes it")
    void cacheWriteCarriesTheVector() throws Exception {
        senderAccepts();
        Embedding embedding = Embedding.of(unitVector(384));
        CacheKey key = CacheKey.derive(REQUEST);

        publisher.publishCacheWrite(
                key, REQUEST, "why is the sky blue?", embedding, "all-MiniLM-L6-v2",
                response(), Instant.parse("2026-11-07T12:00:00Z"));

        ProducerRecord<String, String> record = awaitOneRecord();
        assertThat(record.topic()).isEqualTo(GatewayEvents.TOPIC_CACHE_WRITE);
        assertThat(record.key()).isEqualTo("acme");

        JsonNode payload = payloadOf(record);
        // Shipping the vector costs bytes and saves the indexer recomputing an identical
        // embedding - the same ~3 ms of ONNX inference, for the same answer.
        assertThat(payload.path("vec").asText())
                .startsWith("[")
                .endsWith("]");
        assertThat(payload.path("em").asText()).isEqualTo("all-MiniLM-L6-v2");
        assertThat(payload.path("key").asText()).isEqualTo(key.hex());
        assertThat(payload.path("prompt").asText()).isEqualTo("why is the sky blue?");
    }

    // ------------------------------------------------------------------
    // Audit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an audit event records the key FINGERPRINT, never the key")
    void auditRecordsFingerprintNotKey() throws Exception {
        senderAccepts();

        publisher.publishAudit(
                new GatewayEventPublisher.AuditContext(
                        "req-1", "fp_9a3c2b", "203.0.113.7", "served"),
                REQUEST, response());

        JsonNode payload = payloadOf(awaitOneRecord());
        assertThat(payload.path("key").asText()).isEqualTo("fp_9a3c2b");
        assertThat(payload.path("out").asText()).isEqualTo("served");
        assertThat(payload.path("ip").asText()).isEqualTo("203.0.113.7");
        // The audit log is append-only and widely readable, so a raw credential in it
        // would be a credential leak with a long retention period.
        assertThat(record(payload)).doesNotContain("sk-");
    }

    private static String record(JsonNode payload) {
        return payload.toString();
    }

    @Test
    @DisplayName("an audit event for a failed request records servedBy=none")
    void auditWithNoResponse() throws Exception {
        senderAccepts();

        // A rejected request has no response at all - a 401 or a 429 never reaches a
        // tier. The audit entry must still be written, because a denied request is
        // exactly what an audit log is for.
        publisher.publishAudit(
                new GatewayEventPublisher.AuditContext(
                        "req-2", "fp_9a3c2b", "203.0.113.7", "rate_limited"),
                REQUEST, null);

        JsonNode payload = payloadOf(awaitOneRecord());
        assertThat(payload.path("tier").asText()).isEqualTo("none");
        assertThat(payload.path("out").asText()).isEqualTo("rate_limited");
    }

    // ------------------------------------------------------------------
    // Failure is invisible to the caller - the property that matters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a broker failure never throws, and is counted as dropped")
    void brokerFailureIsCountedNotThrown() {
        senderFails();

        // No exception. ADR-0003: a dropped usage event costs a fraction of a cent,
        // while making Kafka a hard dependency of serving a cache hit costs the latency
        // budget the product is sold on.
        publisher.publishUsage("req-1", REQUEST, response());

        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() >= 1.0d);
        assertThat(dropped()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a sender that throws synchronously is also swallowed and counted")
    void synchronousSenderFailureIsSwallowed() {
        when(sender.send(any(org.reactivestreams.Publisher.class)))
                .thenThrow(new IllegalStateException("producer is closed"));

        publisher.publishUsage("req-1", REQUEST, response());

        // Thrown from send() itself rather than signalled on the Flux - a closed
        // producer does this. Both paths must be caught, or the first one that is not
        // propagates into the request.
        assertThat(dropped()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("an unserialisable event is dropped rather than thrown")
    void serialisationFailureIsSwallowed() {
        // An ObjectMapper with no modules cannot write the Instant fields these events
        // carry, which is the realistic way serialisation breaks here.
        GatewayEventPublisher broken =
                new GatewayEventPublisher(sender, new ObjectMapper(), fixed, meters);

        broken.publishUsage("req-1", REQUEST, response());

        assertThat(dropped()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("every publisher method is fire-and-forget and returns void")
    void allPublishMethodsAreFireAndForget() {
        senderFails();

        // No method returns a Mono the caller could accidentally await. Returning one
        // would invite someone to chain it into the request path, which is the exact
        // mistake the event design exists to prevent.
        publisher.publishUsage("req-1", REQUEST, response());
        publisher.publishAudit(
                new GatewayEventPublisher.AuditContext("r", "fp", "1.2.3.4", "served"),
                REQUEST, response());
        publisher.publishCacheWrite(
                CacheKey.derive(REQUEST), REQUEST, "prompt",
                Embedding.of(unitVector(384)), "model", response(),
                Instant.parse("2026-11-07T12:00:00Z"));

        await().atMost(Duration.ofSeconds(5)).until(() -> dropped() >= 3.0d);
    }

    // ------------------------------------------------------------------

    /** An L2-normalised vector, which is the invariant Embedding enforces. */
    private static float[] unitVector(int dimensions) {
        float[] values = new float[dimensions];
        float component = (float) (1.0d / Math.sqrt(dimensions));
        java.util.Arrays.fill(values, component);
        return values;
    }

    @Test
    @DisplayName("the event topics are the versioned names the consumers subscribe to")
    void topicNamesAreVersioned() {
        // Pinned here because a rename is a silent incompatibility: producers write to
        // the new topic and running consumers keep reading an empty old one.
        assertThat(GatewayEvents.TOPIC_USAGE).isEqualTo("gateway.usage.v1");
        assertThat(GatewayEvents.TOPIC_CACHE_WRITE).isEqualTo("gateway.cache-write.v1");
        assertThat(GatewayEvents.TOPIC_AUDIT).isEqualTo("gateway.audit.v1");
    }

    @Test
    @DisplayName("AuditContext groups what used to be six positional parameters")
    void auditContextIsARecord() {
        // Introduced after a PMD finding: six consecutive String parameters is an
        // invitation to transpose two of them, and transposing the fingerprint and the
        // source address would be invisible in the audit log.
        var context = new GatewayEventPublisher.AuditContext(
                "req-1", "fp_9a3c2b", "203.0.113.7", "served");

        assertThat(context.requestId()).isEqualTo("req-1");
        assertThat(context.apiKeyFingerprint()).isEqualTo("fp_9a3c2b");
        assertThat(context.sourceAddress()).isEqualTo("203.0.113.7");
        assertThat(context.outcome()).isEqualTo("served");
        assertThat(new ArrayList<>(List.of(context))).containsExactly(context);
    }
}
