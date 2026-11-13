package io.vasanth.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Kafka client settings, asserted rather than commented.
 *
 * <h2>Why a test and not just the comments</h2>
 *
 * <p>{@code KafkaFactory} documents a consequence for every setting it pins - unbilled
 * revenue, a compliance gap, silent record loss, a rolling deploy that stalls. Those
 * comments are accurate and completely inert: nothing fails if a setting is removed, and
 * the failure mode of every one of them is silence rather than an exception.
 *
 * <p>So these are the settings whose DEFAULT is wrong, pinned as literals. Each test
 * states what breaks if it changes, because that is the part a future reader needs in
 * order to decide whether they are allowed to change it.
 *
 * <p>No broker is involved. {@code KafkaReceiver.create} and {@code KafkaSender.create}
 * in reactor-kafka build their options eagerly and connect lazily, on first subscribe,
 * so construction is a pure function of the configuration.
 */
class KafkaFactoryTest {

    private static final String BROKERS = "kafka-1:9092,kafka-2:9092";

    @Nested
    @DisplayName("consumer configuration")
    class Consumer {

        private final Map<String, Object> props =
                KafkaFactory.receiverProps(BROKERS, "metering");

        @Test
        @DisplayName("auto-commit is OFF, which is what makes at-least-once work")
        void autoCommitIsOff() {
            // The single most consequential entry in the map. Auto-commit acknowledges on
            // poll, before the handler has written anything, so a crash mid-processing
            // loses those records with no error anywhere - for metering that is revenue
            // that was never billed. EventConsumerRunner acknowledges in doFinally
            // instead, and that design is only correct while this stays false.
            assertThat(props).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        }

        @Test
        @DisplayName("offsets reset to earliest, so downtime does not skip events")
        void resetsToEarliest() {
            // "latest" would mean a service redeployed after an outage silently resumes
            // from now and never sees what accumulated while it was gone.
            assertThat(props).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        }

        @Test
        @DisplayName("rebalancing is cooperative, not the eager default")
        void cooperativeRebalancing() {
            // The eager default revokes every partition in the group on any membership
            // change, so a rolling deploy becomes a series of full stalls rather than a
            // handover of only the partitions that actually move.
            assertThat(props)
                    .containsEntry(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG,
                            "org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
        }

        @Test
        @DisplayName("poll sizing leaves room to finish a batch inside the poll interval")
        void pollSizing() {
            // The relationship is the point, not the individual numbers: a consumer must
            // be able to process max.poll.records within max.poll.interval.ms or the
            // broker considers it dead and rebalances, which looks like an unstable
            // cluster rather than a misconfigured client. 100 records in 300 s is 3 s a
            // record, far more than any handler here needs.
            assertThat(props).containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
            assertThat(props).containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);

            // Heartbeats must be comfortably inside the session timeout - the usual
            // guidance is at most a third - or a slow network drops the member.
            int session = (int) props.get(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG);
            int heartbeat = (int) props.get(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);
            assertThat(heartbeat * 3).isLessThanOrEqualTo(session);
        }

        @Test
        @DisplayName("the caller's brokers and group are passed through")
        void passThrough() {
            assertThat(props)
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BROKERS)
                    // The group id must be whatever the caller asked for. Two services
                    // sharing a group split the partitions, so each sees only half the
                    // events - data loss that presents as a throughput problem.
                    .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "metering")
                    .containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class)
                    .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            StringDeserializer.class);
        }

        @Test
        @DisplayName("a receiver is constructed without contacting a broker")
        void buildsReceiver() {
            // The brokers above do not resolve. Construction must still succeed, because
            // reactor-kafka connects on subscribe - if this ever threw, every service
            // would fail to start whenever Kafka was briefly unreachable.
            assertThat(KafkaFactory.receiver(BROKERS, "metering", "gateway.usage.v1"))
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("producer configuration")
    class Producer {

        private final Map<String, Object> props = KafkaFactory.senderProps(BROKERS);

        @Test
        @DisplayName("durability settings: acks=all with idempotence")
        void durability() {
            // These producers carry billing events, audit records and dead letters.
            // acks=1 would acknowledge on the leader alone, so a leader failover before
            // replication loses the record: money, a compliance record, or the last copy
            // of a message that already failed once.
            assertThat(props).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
            assertThat(props).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        }

        @Test
        @DisplayName("in-flight requests stay within the limit idempotence requires")
        void inFlightWithinIdempotenceLimit() {
            // Kafka only guarantees ordering under idempotence while this is 5 or fewer.
            // Raising it is the kind of change made for throughput that silently costs
            // the ordering guarantee the rest of the system assumes, so the BOUND is
            // asserted rather than the value.
            assertThat((int) props.get(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION))
                    .isBetween(1, 5);
        }

        @Test
        @DisplayName("retries are bounded by the delivery timeout, not by a retry count")
        void retriesBoundedByTime() {
            // Integer.MAX_VALUE retries sounds reckless and is the documented pairing:
            // with idempotence on, the bound that matters is delivery.timeout.ms, so a
            // send gives up after a known wall-clock interval instead of after an
            // arbitrary number of attempts that says nothing about elapsed time.
            assertThat(props).containsEntry(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
            assertThat((int) props.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG))
                    .isPositive();
        }

        @Test
        @DisplayName("brokers, serializers and batching are set")
        void remainder() {
            assertThat(props)
                    .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BROKERS)
                    .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                            StringSerializer.class)
                    .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                            StringSerializer.class)
                    .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");

            // linger trades a little latency for batching. It must be small enough that a
            // single event is not held back noticeably on an otherwise idle topic.
            assertThat((int) props.get(ProducerConfig.LINGER_MS_CONFIG)).isBetween(0, 100);
        }

        @Test
        @DisplayName("a sender is constructed without contacting a broker")
        void buildsSender() {
            assertThat(KafkaFactory.sender(BROKERS)).isNotNull();
        }
    }
}
