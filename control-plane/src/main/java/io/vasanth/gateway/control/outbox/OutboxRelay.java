package io.vasanth.gateway.control.outbox;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Publishes outbox rows to Kafka.
 *
 * <h2>FOR UPDATE SKIP LOCKED is the whole trick</h2>
 *
 * <p>Several control-plane replicas run this relay concurrently. A plain
 * {@code SELECT ... WHERE published_at IS NULL} would hand every replica the same rows,
 * and each would publish them - turning one event into N duplicates.
 *
 * <p>{@code FOR UPDATE} locks the selected rows. {@code SKIP LOCKED} makes other replicas
 * step over rows already locked rather than block behind them. Each replica therefore
 * takes a disjoint batch, and the relay scales horizontally with no coordination, no
 * leader election and no distributed lock.
 *
 * <p>Without {@code SKIP LOCKED} the replicas would serialise behind one another and
 * throughput would be that of a single relay no matter how many ran.
 *
 * <h2>At-least-once, deliberately</h2>
 *
 * <p>A crash between publishing and marking a row published will republish it. That is
 * accepted rather than fixed: eliminating it would need a distributed transaction across
 * Postgres and Kafka, and the standard answer - idempotent consumers - is cheaper and more
 * robust. Every consumer in this system is built for it.
 *
 * <h2>Why a poller rather than Debezium</h2>
 *
 * <p>Debezium reading the Postgres WAL is the production-grade relay: no polling latency,
 * no load on the table. It needs Kafka Connect, which does not fit in this machine's RAM.
 * The interface is the same either way - rows in a table - so swapping is a deployment
 * change. See {@code docs/adr/0007-outbox-and-saga.md}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /**
     * Rows claimed per poll.
     *
     * <p>Bounded so one replica cannot lock the entire backlog and starve the others, and
     * so a single transaction stays short.
     */
    private static final int BATCH_SIZE = 100;

    /** Beyond this many failures a row is left alone and reported, not retried forever. */
    private static final int MAX_ATTEMPTS = 10;

    private static final String CLAIM_BATCH = """
            SELECT id, topic, partition_key, payload::text AS payload, attempts
              FROM control_outbox
             WHERE published_at IS NULL
               AND attempts < :maxAttempts
             ORDER BY created_at
             LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE control_outbox
               SET published_at = now()
             WHERE id = :id
            """;

    private static final String MARK_FAILED = """
            UPDATE control_outbox
               SET attempts = attempts + 1,
                   last_error = :error
             WHERE id = :id
            """;

    private static final String COUNT_PENDING = """
            SELECT count(*) FROM control_outbox WHERE published_at IS NULL
            """;

    private final DatabaseClient db;
    private final TransactionalOperator transactions;
    private final KafkaSender<String, String> sender;
    private final boolean enabled;

    private final Counter relayed;
    private final Counter failures;
    private final AtomicLong pendingGauge = new AtomicLong();

    public OutboxRelay(
            DatabaseClient db,
            TransactionalOperator transactions,
            KafkaSender<String, String> sender,
            MeterRegistry meterRegistry,
            @Value("${gateway.outbox.relay-enabled:true}") boolean enabled) {
        this.db = db;
        this.transactions = transactions;
        this.sender = sender;
        this.enabled = enabled;
        this.relayed = Counter.builder("outbox.events.relayed").register(meterRegistry);
        this.failures = Counter.builder("outbox.events.failed").register(meterRegistry);

        // The single most important signal this service emits. A rising backlog means
        // the data plane is not learning about key revocations and quota changes - which
        // is a security and billing problem long before it is a performance one.
        Gauge.builder("outbox.pending", pendingGauge, AtomicLong::get)
                .description("Outbox rows awaiting publication")
                .register(meterRegistry);
    }

    /**
     * Polls every second.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next poll starts a second after
     * the previous one finishes. With fixedRate a slow batch would have polls overlapping
     * and competing for the same rows - which SKIP LOCKED survives, but only by doing
     * useless work.
     *
     * <p>One second is the propagation latency for a key revocation. It is bounded and
     * documented rather than instantaneous, which is why the control plane also deletes
     * the Redis entry directly for an immediate kill.
     */
    @Scheduled(fixedDelay = 1, timeUnit = java.util.concurrent.TimeUnit.SECONDS,
            initialDelay = 5)
    public void relay() {
        if (!enabled) {
            return;
        }

        try {
            Long published = transactions.transactional(claimAndPublish())
                    .reduce(0L, Long::sum)
                    // The scheduler runs this on a plain pool thread, not an event loop,
                    // so blocking here is correct.
                    .block(Duration.ofSeconds(30));

            if (published != null && published > 0L) {
                relayed.increment(published);
                log.debug("relayed {} outbox events", published);
            }
            refreshPendingGauge();

        } catch (Exception e) {
            // Never allowed to kill the scheduler thread: if this method throws, Spring
            // stops rescheduling it and the relay silently dies while the service
            // continues to report itself healthy.
            failures.increment();
            log.warn("outbox relay poll failed: {}", e.toString());
        }
    }

    private Flux<Long> claimAndPublish() {
        return db.sql(CLAIM_BATCH)
                .bind("batchSize", BATCH_SIZE)
                .bind("maxAttempts", MAX_ATTEMPTS)
                // orDefault, not direct assignment: Row.get(..., Long.class) returns a
                // boxed value that is null for a NULL column, and assigning it into the
                // record's `long` component unboxes it and throws NPE. The columns are
                // NOT NULL today, so this guards against a future migration - and an id
                // of 0 matches no row, so a malformed entry can never be marked
                // published by mistake.
                .map((row, metadata) -> new PendingEvent(
                        orDefault(row.get("id", Long.class), 0L),
                        orDefault(row.get("topic", String.class), ""),
                        orDefault(row.get("partition_key", String.class), ""),
                        orDefault(row.get("payload", String.class), "")))
                .all()
                // concatMap, not flatMap: rows are claimed in created_at order and must
                // be published in that order, or a QUOTA_CHANGED event could overtake the
                // TENANT_CREATED that precedes it.
                .concatMap(this::publishOne);
    }

    private Mono<Long> publishOne(PendingEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.topic(), event.partitionKey(), event.payload());

        return sender.send(Mono.just(SenderRecord.create(record, event.partitionKey())))
                .next()
                .flatMap(result -> markPublished(event.id()).thenReturn(1L))
                .onErrorResume(error -> {
                    // Record the failure and move on. The row stays unpublished and is
                    // retried next poll; after MAX_ATTEMPTS it is left for a human, so one
                    // permanently bad row cannot consume the relay forever.
                    log.warn("failed to relay outbox row {}: {}", event.id(), error.toString());
                    return markFailed(event.id(), error.toString()).thenReturn(0L);
                });
    }

    private Mono<Void> markPublished(long id) {
        return db.sql(MARK_PUBLISHED).bind("id", id).fetch().rowsUpdated().then();
    }

    private Mono<Void> markFailed(long id, String error) {
        String trimmed = error.length() > 1000 ? error.substring(0, 1000) : error;
        return db.sql(MARK_FAILED)
                .bind("id", id)
                .bind("error", trimmed)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private void refreshPendingGauge() {
        db.sql(COUNT_PENDING)
                .map((row, metadata) -> row.get(0, Long.class))
                .one()
                .doOnNext(pendingGauge::set)
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private record PendingEvent(long id, String topic, String partitionKey, String payload) {
    }
}
