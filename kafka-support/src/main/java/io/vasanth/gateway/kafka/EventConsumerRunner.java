package io.vasanth.gateway.kafka;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverRecord;
import reactor.util.retry.Retry;

/**
 * Runs a Kafka consumer with partition-ordered processing, dead-lettering and manual
 * offset acknowledgement.
 *
 * <p>Every consumer service needs exactly this behaviour, and each of the three decisions
 * below is one that is easy to get subtly wrong in a way that only shows up under load or
 * during a rebalance.
 *
 * <h2>1. Grouped by partition, sequential within each</h2>
 *
 * <p>{@code receive().flatMap(handler)} processes records concurrently across the whole
 * stream and destroys per-partition ordering. Since the partition key is the tenant id,
 * that means one tenant's events applied out of order.
 *
 * <p>{@code receive().concatMap(handler)} restores ordering by processing one record at a
 * time globally, making throughput independent of how many partitions exist.
 *
 * <p>Grouping by partition and using {@code concatMap} inside each group gives
 * parallelism across partitions and strict ordering within one - which is the guarantee
 * Kafka's partitioning exists to provide, and which both naive forms throw away.
 *
 * <h2>2. A poison pill must not stall its partition</h2>
 *
 * <p>A record that fails to parse fails identically every time. Retrying it blocks its
 * partition permanently while the service continues to report itself healthy. It is
 * dead-lettered instead, and the offset is acknowledged, so the partition keeps moving.
 *
 * <h2>3. Offsets acknowledge after the outcome, not on poll</h2>
 *
 * <p>Acknowledgement happens in {@code doFinally}, once the record has either been
 * processed or parked. A crash before that causes redelivery, which every consumer here
 * is built to absorb idempotently.
 *
 * @param <T> the event type this consumer handles
 */
public final class EventConsumerRunner<T> implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventConsumerRunner.class);

    private final String name;
    private final KafkaReceiver<String, String> receiver;
    private final Class<T> eventType;
    private final Function<T, Mono<Void>> handler;
    private final ToIntFunction<T> schemaVersionOf;
    private final DeadLetterPublisher deadLetters;
    private final ObjectMapper objectMapper;

    private final Counter consumed;
    private final Counter failed;

    private volatile Disposable subscription;

    public EventConsumerRunner(
            String name,
            KafkaReceiver<String, String> receiver,
            Class<T> eventType,
            Function<T, Mono<Void>> handler,
            ToIntFunction<T> schemaVersionOf,
            DeadLetterPublisher deadLetters,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.name = name;
        this.receiver = receiver;
        this.eventType = eventType;
        this.handler = handler;
        this.schemaVersionOf = schemaVersionOf;
        this.deadLetters = deadLetters;
        this.objectMapper = objectMapper;
        this.consumed = Counter.builder("consumer.events.consumed")
                .tag("consumer", name).register(meterRegistry);
        this.failed = Counter.builder("consumer.events.failed")
                .tag("consumer", name).register(meterRegistry);
    }

    @Override
    public void start() {
        subscription = receiver.receive()
                .groupBy(record -> record.receiverOffset().topicPartition())
                .flatMap(partition -> partition.concatMap(this::handleRecord))
                // A failure escaping handleRecord is infrastructure: the broker is gone,
                // or the database is. Resubscribing with backoff keeps the consumer alive
                // instead of terminating the stream and leaving a process that looks
                // healthy while consuming nothing - the worst possible failure mode,
                // because no alert fires.
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(30))
                        .doBeforeRetry(signal -> log.error(
                                "{} consumer failed, resubscribing: {}",
                                name, signal.failure().toString())))
                .subscribe();

        log.info("{} consumer started", name);
    }

    private Mono<Void> handleRecord(ReceiverRecord<String, String> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.value(), eventType))
                .flatMap(event -> {
                    int version = schemaVersionOf.applyAsInt(event);
                    if (version > GatewayEvents.SCHEMA_VERSION) {
                        // Produced by a newer deployment than this consumer understands.
                        // Parsing it anyway would write data that looks right and is not.
                        log.warn("{}: event schema v{} newer than supported v{}, dead-lettering",
                                name, version, GatewayEvents.SCHEMA_VERSION);
                        return deadLetters.publish(record, "unsupported schema version " + version);
                    }
                    return handler.apply(event).doOnSuccess(ignored -> consumed.increment());
                })
                .onErrorResume(error -> {
                    failed.increment();
                    log.error("{}: could not process record at {}-{} offset {}: {}",
                            name, record.topic(), record.partition(), record.offset(),
                            error.toString());
                    return deadLetters.publish(record, error.toString());
                })
                .doFinally(signal -> record.receiverOffset().acknowledge());
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null && !current.isDisposed()) {
            current.dispose();
            log.info("{} consumer stopped", name);
        }
    }

    @Override
    public boolean isRunning() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }

    /**
     * Starts last and stops first.
     *
     * <p>Database and Kafka connections must be up before the first record is handled, and
     * consumption must stop before they are torn down - otherwise shutdown emits a burst
     * of failures that look like real errors in the logs and send someone chasing a
     * problem that does not exist.
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
