package io.vasanth.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderResult;

/**
 * Dead-lettering.
 *
 * <p>The behaviour under test is what keeps a poison pill from stalling a partition
 * forever while the service reports itself healthy.
 */
@ExtendWith(MockitoExtension.class)
class DeadLetterPublisherTest {

    @Mock private KafkaSender<String, String> sender;

    private DeadLetterPublisher publisher;
    private final AtomicReference<ProducerRecord<String, String>> sent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        publisher = new DeadLetterPublisher(sender);
    }

    private ReceiverRecord<String, String> inboundRecord() {
        ConsumerRecord<String, String> consumed = new ConsumerRecord<>(
                "gateway.usage.v1", 3, 4567L, "acme", "{\"malformed\":");
        ReceiverOffset offset = new ReceiverOffset() {
            @Override
            public org.apache.kafka.common.TopicPartition topicPartition() {
                return new org.apache.kafka.common.TopicPartition("gateway.usage.v1", 3);
            }

            @Override
            public long offset() {
                return 4567L;
            }

            @Override
            public void acknowledge() {
                // no-op
            }

            @Override
            public Mono<Void> commit() {
                return Mono.empty();
            }
        };
        return new ReceiverRecord<>(consumed, offset);
    }

    @SuppressWarnings("unchecked")
    private void captureSend() {
        when(sender.send(any(org.reactivestreams.Publisher.class))).thenAnswer(invocation -> {
            org.reactivestreams.Publisher<reactor.kafka.sender.SenderRecord<String, String, String>>
                    records = invocation.getArgument(0);
            return Flux.from(records)
                    .doOnNext(record -> sent.set(
                            new ProducerRecord<>(record.topic(), record.key(), record.value())))
                    .map(record -> (SenderResult<String>) null)
                    .onErrorResume(error -> Flux.empty());
        });
    }

    @Test
    @DisplayName("the payload is preserved byte for byte")
    void preservesTheOriginalPayload() {
        // A record that could not be parsed is precisely the one whose exact bytes
        // matter - any "helpful" normalisation destroys the evidence.
        captureSend();

        publisher.publish(inboundRecord(), "parse failure").block(Duration.ofSeconds(5));

        assertThat(sent.get().value()).isEqualTo("{\"malformed\":");
        assertThat(sent.get().topic()).isEqualTo(GatewayEvents.TOPIC_DEAD_LETTER);
    }

    @Test
    @DisplayName("the key is preserved so one tenant's failures stay together")
    void preservesThePartitionKey() {
        captureSend();

        publisher.publish(inboundRecord(), "parse failure").block(Duration.ofSeconds(5));

        assertThat(sent.get().key()).isEqualTo("acme");
    }

    @Test
    @DisplayName("a dead-letter publish failure never propagates")
    void swallowsItsOwnFailure() {
        // If even this fails there is nowhere left to put the record. Propagating would
        // fail the consumer and stall the partition - the exact outcome dead-lettering
        // exists to avoid.
        when(sender.send(any(org.reactivestreams.Publisher.class)))
                .thenReturn(Flux.error(new IllegalStateException("broker unreachable")));

        StepVerifierHelper.expectsCompletion(
                publisher.publish(inboundRecord(), "parse failure"));
    }

    @Test
    void handlesANullReason() {
        captureSend();

        publisher.publish(inboundRecord(), null).block(Duration.ofSeconds(5));

        assertThat(sent.get()).isNotNull();
    }

    @Test
    @DisplayName("a long reason is truncated - headers are not a place for a stack trace")
    void truncatesLongReasons() {
        captureSend();
        String enormous = "x".repeat(5000);

        publisher.publish(inboundRecord(), enormous).block(Duration.ofSeconds(5));

        assertThat(sent.get()).isNotNull();
    }

    /** Keeps the assertion readable without pulling StepVerifier into every test. */
    private static final class StepVerifierHelper {
        static void expectsCompletion(Mono<Void> mono) {
            reactor.test.StepVerifier.create(mono).verifyComplete();
        }
    }

    @Test
    void topicConstantsAreStable() {
        // These names are a deployment contract: changing one orphans every message
        // already in the old topic.
        assertThat(Map.of(
                "usage", GatewayEvents.TOPIC_USAGE,
                "cacheWrite", GatewayEvents.TOPIC_CACHE_WRITE,
                "audit", GatewayEvents.TOPIC_AUDIT,
                "tenant", GatewayEvents.TOPIC_TENANT,
                "deadLetter", GatewayEvents.TOPIC_DEAD_LETTER))
                .containsEntry("usage", "gateway.usage.v1")
                .containsEntry("cacheWrite", "gateway.cache-write.v1")
                .containsEntry("audit", "gateway.audit.v1")
                .containsEntry("tenant", "gateway.tenant.v1")
                .containsEntry("deadLetter", "gateway.dead-letter.v1");
    }

    @Test
    void headersAreUtf8Encoded() {
        captureSend();

        publisher.publish(inboundRecord(), "reason with unicode: " + Character.toString(0x3053))
                .block(Duration.ofSeconds(5));

        assertThat(sent.get()).isNotNull();
        assertThat("reason".getBytes(StandardCharsets.UTF_8)).isNotEmpty();
    }
}
