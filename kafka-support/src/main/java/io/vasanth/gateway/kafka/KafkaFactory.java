package io.vasanth.gateway.kafka;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;

/**
 * Builds correctly configured Kafka receivers and senders.
 *
 * <p>Every setting below is one that silently misbehaves at its default. Leaving them
 * implicit in three separate services would guarantee the three drift, and the drift
 * would show up as "Kafka is flaky" rather than as a configuration difference.
 */
public final class KafkaFactory {

    private KafkaFactory() {
        // factory
    }

    /**
     * A receiver for one topic, with manual acknowledgement.
     *
     * @param consumerGroup must be unique per service. Two services sharing a group split
     *     the partitions between them, so each sees only half the events - a data-loss bug
     *     that looks like a throughput problem
     */
    public static KafkaReceiver<String, String> receiver(
            String bootstrapServers, String consumerGroup, String topic) {

        Map<String, Object> props = Map.ofEntries(
                Map.entry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                Map.entry(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup),
                Map.entry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),
                Map.entry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class),

                // earliest, not latest. A service redeployed after downtime must not skip
                // the events that accumulated while it was gone - for metering that is
                // unbilled revenue, for audit it is a compliance gap.
                Map.entry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),

                // Offsets are acknowledged after the write succeeds. Auto-commit
                // acknowledges on poll, so a crash mid-processing loses records silently.
                Map.entry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false),

                // The default of 500 lets a rebalance hand one consumer 500 records to
                // finish inside max.poll.interval.ms; exceeding it triggers another
                // rebalance, and the loop presents as an unstable broker.
                Map.entry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100),
                Map.entry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000),
                Map.entry(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000),
                Map.entry(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000),

                // Cooperative rebalancing revokes only the partitions actually moving.
                // The eager default stops the whole group on every membership change, so
                // a rolling deploy becomes a series of full stalls.
                Map.entry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                        "org.apache.kafka.clients.consumer.CooperativeStickyAssignor"));

        return KafkaReceiver.create(
                ReceiverOptions.<String, String>create(props).subscription(List.of(topic)));
    }

    /** A sender configured for durability rather than throughput. */
    public static KafkaSender<String, String> sender(String bootstrapServers) {
        Map<String, Object> props = Map.ofEntries(
                Map.entry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                Map.entry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class),
                Map.entry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class),

                // acks=all plus idempotence. These producers carry billing events, audit
                // records and dead letters; losing one to a leader failover means losing
                // money, a compliance record, or the last copy of a bad message.
                Map.entry(ProducerConfig.ACKS_CONFIG, "all"),
                Map.entry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true),
                Map.entry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5),
                Map.entry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE),
                Map.entry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000),

                Map.entry(ProducerConfig.LINGER_MS_CONFIG, 20),
                Map.entry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"));

        return KafkaSender.create(SenderOptions.create(props));
    }
}
