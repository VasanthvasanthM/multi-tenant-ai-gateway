package io.vasanth.gateway.kafka;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Parks records this consumer cannot process.
 *
 * <h2>Why a dead-letter topic rather than a retry</h2>
 *
 * <p>A record that fails to parse will fail to parse identically every time. Retrying it
 * blocks its partition forever - the classic poison-pill stall, where one malformed
 * message silently halts every tenant that hashes to that partition while the service
 * reports itself healthy.
 *
 * <p>Moving it aside keeps the partition flowing and leaves the bad record somewhere a
 * human can inspect it. The original payload is preserved byte for byte, because a record
 * that could not be parsed is precisely the one whose exact bytes matter.
 */
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    /** Headers carry the diagnosis, so the payload stays exactly as produced. */
    private static final String HEADER_REASON = "x-dlq-reason";
    private static final String HEADER_TOPIC = "x-dlq-original-topic";
    private static final String HEADER_PARTITION = "x-dlq-original-partition";
    private static final String HEADER_OFFSET = "x-dlq-original-offset";

    private final KafkaSender<String, String> sender;

    public DeadLetterPublisher(KafkaSender<String, String> sender) {
        this.sender = sender;
    }

    public Mono<Void> publish(ReceiverRecord<String, String> record, String reason) {
        ProducerRecord<String, String> dead = new ProducerRecord<>(
                GatewayEvents.TOPIC_DEAD_LETTER,
                // Same key, so a tenant's failures stay together and the volume of one
                // tenant's bad data is immediately visible.
                record.key(),
                record.value());

        dead.headers()
                .add(new RecordHeader(HEADER_REASON, bytes(truncate(reason))))
                .add(new RecordHeader(HEADER_TOPIC, bytes(record.topic())))
                .add(new RecordHeader(HEADER_PARTITION, bytes(Integer.toString(record.partition()))))
                .add(new RecordHeader(HEADER_OFFSET, bytes(Long.toString(record.offset()))));

        return sender.send(Mono.just(SenderRecord.create(dead, record.key())))
                .next()
                .doOnNext(result -> log.warn(
                        "dead-lettered record from {}-{} offset {}: {}",
                        record.topic(), record.partition(), record.offset(), truncate(reason)))
                .onErrorResume(error -> {
                    // If even the dead-letter publish fails, there is nowhere left to put
                    // the record. Logging the full payload is the last resort - it is the
                    // only remaining copy outside the original topic.
                    log.error("dead-letter publish FAILED, record may be lost. "
                                    + "topic={} partition={} offset={} payload={}",
                            record.topic(), record.partition(), record.offset(),
                            truncate(record.value()), error);
                    return Mono.empty();
                })
                .then();
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    /** Kafka headers are not a place for a full stack trace. */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }
}
