package io.vasanth.gateway.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOffset;
import reactor.kafka.receiver.ReceiverRecord;

/**
 * The consumer loop, including the paths that only happen when something is wrong.
 *
 * <h2>Why this needs no broker</h2>
 *
 * <p>{@code EventConsumerRunner} takes its {@code KafkaReceiver} as a constructor
 * argument, so a stub returning a {@code Flux} of crafted records drives the entire loop.
 * That is worth stating because the module was previously at 25% line coverage with its
 * two largest classes untested, and "it needs Kafka" is the assumption that kept them
 * that way.
 *
 * <h2>What is actually asserted</h2>
 *
 * <p>The class comment documents three promises, none of which had a test:
 *
 * <ul>
 *   <li>a record that cannot be handled goes to the dead-letter topic rather than being
 *       retried forever - an unparseable record fails identically every time, so retrying
 *       it blocks its partition permanently;
 *   <li>an event from a NEWER schema version is dead-lettered rather than parsed, because
 *       parsing it anyway writes data that looks right and is not;
 *   <li>the offset is acknowledged in {@code doFinally} - on the success path AND on
 *       every failure path. Miss that and one bad record stalls a partition forever.
 * </ul>
 */
class EventConsumerRunnerTest {

    private record TestEvent(
            @JsonProperty("tenant") String tenant,
            @JsonProperty("schemaVersion") int schemaVersion) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
    private final List<TestEvent> handled = new CopyOnWriteArrayList<>();

    /** Set by the fake offset, so acknowledgement can be asserted rather than assumed. */
    private final AtomicBoolean acknowledged = new AtomicBoolean();

    private ReceiverRecord<String, String> record(String json, long offset, int partition) {
        ConsumerRecord<String, String> consumed =
                new ConsumerRecord<>("gateway.usage.v1", partition, offset, "acme", json);
        return new ReceiverRecord<>(consumed, new ReceiverOffset() {
            @Override
            public TopicPartition topicPartition() {
                return new TopicPartition("gateway.usage.v1", partition);
            }

            @Override
            public long offset() {
                return offset;
            }

            @Override
            public void acknowledge() {
                acknowledged.set(true);
            }

            @Override
            public Mono<Void> commit() {
                return Mono.empty();
            }
        });
    }

    private EventConsumerRunner<TestEvent> runnerOver(
            Flux<ReceiverRecord<String, String>> records,
            Function<TestEvent, Mono<Void>> handler) {

        @SuppressWarnings("unchecked")
        KafkaReceiver<String, String> receiver = mock(KafkaReceiver.class);
        when(receiver.receive()).thenReturn(records);

        return new EventConsumerRunner<>(
                "test", receiver, TestEvent.class, handler,
                TestEvent::schemaVersion, deadLetters, objectMapper, meters);
    }

    private double counter(String name) {
        return meters.find(name).tag("consumer", "test").counter().count();
    }

    private static String json(String tenant, int version) {
        return "{\"tenant\":\"" + tenant + "\",\"schemaVersion\":" + version + "}";
    }

    @Test
    @DisplayName("a valid record is handled, counted and acknowledged")
    void happyPath() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        EventConsumerRunner<TestEvent> runner = runnerOver(
                Flux.just(record(json("acme", GatewayEvents.SCHEMA_VERSION), 1L, 0)),
                event -> {
                    handled.add(event);
                    done.countDown();
                    return Mono.empty();
                });

        runner.start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(handled).extracting(TestEvent::tenant).containsExactly("acme");
        assertThat(counter("consumer.events.consumed")).isEqualTo(1.0d);
        assertThat(counter("consumer.events.failed")).isZero();
        assertThat(acknowledged).isTrue();
        verify(deadLetters, never()).publish(any(), any());
    }

    @Test
    @DisplayName("an unparseable record is dead-lettered, not retried, and acknowledged")
    void malformedJson() throws Exception {
        CountDownLatch dead = new CountDownLatch(1);
        when(deadLetters.publish(any(), any()))
                .thenAnswer(invocation -> {
                    dead.countDown();
                    return Mono.empty();
                });

        EventConsumerRunner<TestEvent> runner =
                runnerOver(Flux.just(record("{\"malformed\":", 2L, 0)), event -> {
                    handled.add(event);
                    return Mono.empty();
                });

        runner.start();

        assertThat(dead.await(5, TimeUnit.SECONDS)).isTrue();
        // The handler must never see it, and it must not be replayed: the same bytes fail
        // the same way every time, so a retry would block this partition permanently.
        assertThat(handled).isEmpty();
        assertThat(counter("consumer.events.failed")).isEqualTo(1.0d);
        // Acknowledged despite failing. This is the doFinally, and it is the difference
        // between losing one record and stalling a partition forever.
        assertThat(acknowledged).isTrue();
    }

    @Test
    @DisplayName("an event from a newer schema version is dead-lettered unparsed")
    void newerSchemaVersion() throws Exception {
        CountDownLatch dead = new CountDownLatch(1);
        when(deadLetters.publish(any(), any()))
                .thenAnswer(invocation -> {
                    dead.countDown();
                    return Mono.empty();
                });

        int tooNew = GatewayEvents.SCHEMA_VERSION + 1;
        EventConsumerRunner<TestEvent> runner =
                runnerOver(Flux.just(record(json("acme", tooNew), 3L, 0)), event -> {
                    handled.add(event);
                    return Mono.empty();
                });

        runner.start();

        assertThat(dead.await(5, TimeUnit.SECONDS)).isTrue();

        // Not handled. A newer producer may have added a field with meaning this build
        // does not know about; processing it anyway writes data that looks correct.
        assertThat(handled).isEmpty();
        verify(deadLetters).publish(any(), eq("unsupported schema version " + tooNew));

        // This path is a REFUSAL, not a processing failure, so it must not be counted as
        // one - a schema rollout would otherwise look like a spike of errors.
        assertThat(counter("consumer.events.failed")).isZero();
        assertThat(counter("consumer.events.consumed")).isZero();
        assertThat(acknowledged).isTrue();
    }

    @Test
    @DisplayName("a handler failure is dead-lettered, counted and acknowledged")
    void handlerFails() throws Exception {
        CountDownLatch dead = new CountDownLatch(1);
        when(deadLetters.publish(any(), any()))
                .thenAnswer(invocation -> {
                    dead.countDown();
                    return Mono.empty();
                });

        EventConsumerRunner<TestEvent> runner = runnerOver(
                Flux.just(record(json("acme", GatewayEvents.SCHEMA_VERSION), 4L, 0)),
                event -> Mono.error(new IllegalStateException("database down")));

        runner.start();

        assertThat(dead.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(counter("consumer.events.failed")).isEqualTo(1.0d);
        assertThat(counter("consumer.events.consumed")).isZero();
        assertThat(acknowledged).isTrue();
    }

    @Test
    @DisplayName("records on one partition are handled in offset order")
    void preservesPerPartitionOrder() throws Exception {
        CountDownLatch done = new CountDownLatch(3);
        EventConsumerRunner<TestEvent> runner = runnerOver(
                Flux.just(
                        record(json("first", GatewayEvents.SCHEMA_VERSION), 1L, 0),
                        record(json("second", GatewayEvents.SCHEMA_VERSION), 2L, 0),
                        record(json("third", GatewayEvents.SCHEMA_VERSION), 3L, 0)),
                event -> {
                    handled.add(event);
                    done.countDown();
                    return Mono.empty();
                });

        runner.start();

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        // groupBy(topicPartition) + concatMap is what guarantees this. Plain flatMap would
        // interleave, and for usage events that means a later total overwriting an earlier
        // one - the ordering is the correctness property, not a nicety.
        assertThat(handled).extracting(TestEvent::tenant)
                .containsExactly("first", "second", "third");
    }

    @Test
    @DisplayName("lifecycle: not running until started, not running once stopped")
    void lifecycle() {
        // Flux.never(), because a stream that completes disposes its own subscription and
        // isRunning() would report false for a consumer that simply ran out of records.
        EventConsumerRunner<TestEvent> runner =
                runnerOver(Flux.never(), event -> Mono.empty());

        assertThat(runner.isRunning()).isFalse();

        runner.start();
        assertThat(runner.isRunning()).isTrue();

        runner.stop();
        assertThat(runner.isRunning()).isFalse();

        // stop() on an already-stopped consumer must be harmless: Spring calls it on a
        // context that failed to start fully.
        runner.stop();
        assertThat(runner.isRunning()).isFalse();
    }

    @Test
    @DisplayName("starts last and stops first")
    void phase() {
        EventConsumerRunner<TestEvent> runner =
                runnerOver(Flux.never(), event -> Mono.empty());

        // A high phase means Spring starts this after the datasource and Kafka beans and
        // stops it before them. Getting it wrong emits a burst of failures during
        // shutdown that read as real errors and send someone chasing nothing.
        assertThat(runner.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
        assertThat(runner.getPhase()).isLessThan(Integer.MAX_VALUE);
    }
}
