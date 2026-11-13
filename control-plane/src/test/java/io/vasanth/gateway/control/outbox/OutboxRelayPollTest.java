package io.vasanth.gateway.control.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;
import reactor.kafka.sender.KafkaSender;

/**
 * The poll loop of {@link OutboxRelay}, and specifically its failure handling.
 *
 * <h2>Why this is a unit test when OutboxRelayIT already exists</h2>
 *
 * <p>{@code OutboxRelayIT} drives the SQL against a real Postgres, which is the only way
 * to prove {@code FOR UPDATE SKIP LOCKED} behaves. What it does NOT cover is what
 * {@code relay()} does when the batch blows up, because provoking that from a working
 * database is awkward - and that path is the one with the worst failure mode in the class.
 *
 * <p>The method comment states it: "Never allowed to kill the scheduler thread: if this
 * method throws, Spring stops rescheduling it and the relay silently dies while the
 * service continues to report itself healthy." A relay that has stopped means key
 * revocations and quota changes never reach the data plane - a security and billing
 * problem - with a process that still passes every health check. Nothing verified that
 * the catch block was there.
 *
 * <p>No database and no broker: the transaction boundary is the seam, so stubbing
 * {@code TransactionalOperator} drives every branch of the poll directly.
 */
class OutboxRelayPollTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DatabaseClient db = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
    private final TransactionalOperator transactions = mock(TransactionalOperator.class);

    @SuppressWarnings("unchecked")
    private final KafkaSender<String, String> sender = mock(KafkaSender.class);

    private OutboxRelay relayWith(boolean enabled) {
        return new OutboxRelay(db, transactions, sender, meters, enabled);
    }

    private double counter(String name) {
        return meters.find(name).counter().count();
    }

    @Test
    @DisplayName("a disabled relay does not touch the database at all")
    void disabledDoesNothing() {
        OutboxRelay relay = relayWith(false);

        relay.relay();

        // Not merely "publishes nothing": it must not open a transaction either. The flag
        // exists so a second replica can run with the relay off, and a disabled replica
        // still claiming rows would defeat the point.
        verify(transactions, never()).transactional(any(Flux.class));
        assertThat(counter("outbox.events.relayed")).isZero();
        assertThat(counter("outbox.events.failed")).isZero();
    }

    @Test
    @DisplayName("a failing batch is swallowed, counted, and never rethrown")
    void failureNeverEscapes() {
        when(transactions.transactional(any(Flux.class)))
                .thenReturn(Flux.error(new IllegalStateException("broker unreachable")));

        OutboxRelay relay = relayWith(true);

        // The assertion that matters. If this throws, Spring stops rescheduling the
        // method and the relay is dead for the lifetime of the process - while health
        // checks stay green, because nothing else in the service depends on it.
        assertThatCode(relay::relay).doesNotThrowAnyException();

        assertThat(counter("outbox.events.failed")).isEqualTo(1.0d);
        assertThat(counter("outbox.events.relayed")).isZero();
    }

    @Test
    @DisplayName("the relayed counter records how many events were published")
    void countsPublished() {
        // Three rows, each contributing 1.
        when(transactions.transactional(any(Flux.class)))
                .thenReturn(Flux.just(1L, 1L, 1L));

        relayWith(true).relay();

        assertThat(counter("outbox.events.relayed")).isEqualTo(3.0d);
        assertThat(counter("outbox.events.failed")).isZero();
    }

    @Test
    @DisplayName("an empty batch increments nothing")
    void emptyBatchIsNotAnEvent() {
        when(transactions.transactional(any(Flux.class))).thenReturn(Flux.empty());

        relayWith(true).relay();

        // The idle case runs once a second forever. Incrementing a counter by zero is
        // harmless, but logging or counting an "event" here would bury the real ones.
        assertThat(counter("outbox.events.relayed")).isZero();
        assertThat(counter("outbox.events.failed")).isZero();
    }

    @Test
    @DisplayName("the pending gauge is registered even before the first poll")
    void gaugeExistsImmediately() {
        relayWith(true);

        // Registered in the constructor, not on first poll. A gauge that only appears
        // after the first successful batch is absent exactly when the backlog is the
        // thing you need to see - during a failing relay.
        assertThat(meters.find("outbox.pending").gauge()).isNotNull();
    }
}
