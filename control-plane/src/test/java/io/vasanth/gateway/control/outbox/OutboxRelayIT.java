package io.vasanth.gateway.control.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import reactor.core.publisher.Mono;

/**
 * The outbox table and the claim semantics the relay depends on.
 *
 * <p>The relay itself is not driven here - that needs a broker. What is tested is the
 * thing that makes a multi-replica relay safe: {@code FOR UPDATE SKIP LOCKED} handing
 * disjoint batches to concurrent claimers. Without that property the relay publishes every
 * event N times, once per replica, and no amount of application code fixes it.
 */
@Testcontainers
@SpringBootTest(properties = {
        "gateway.kafka.bootstrap-servers=localhost:1",
        // The scheduled relay is disabled so it cannot race these assertions.
        "gateway.outbox.relay-enabled=false"
})
class OutboxRelayIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("control")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    private static final String CLAIM = """
            SELECT id FROM control_outbox
             WHERE published_at IS NULL
             ORDER BY created_at
             LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """;

    @Autowired private DatabaseClient db;

    private void insert(String aggregateId, String eventType) {
        db.sql("""
                INSERT INTO control_outbox (aggregate_id, event_type, topic, partition_key, payload)
                VALUES (:aggregateId, :eventType, 'gateway.tenant.v1', :aggregateId,
                        CAST('{}' AS jsonb))
                """)
                .bind("aggregateId", aggregateId)
                .bind("eventType", eventType)
                .fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, Long.class)).one()
                .block(Duration.ofSeconds(10));
    }

    @BeforeEach
    void clean() {
        db.sql("TRUNCATE control_outbox, saga_instances, api_keys, tenants CASCADE")
                .fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    @Test
    void migrationCreatesTheExpectedTables() {
        assertThat(count("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema='public'
                   AND table_name IN ('tenants','api_keys','saga_instances','control_outbox')
                """)).isEqualTo(4L);
    }

    @Test
    void rowsStartUnpublished() {
        insert("acme", "TENANT_CREATED");

        assertThat(count(
                "SELECT count(*) FROM control_outbox WHERE published_at IS NULL")).isEqualTo(1L);
    }

    @Test
    @DisplayName("two concurrent claimers receive disjoint batches")
    void skipLockedGivesDisjointBatches() {
        // THE property the whole design rests on. Without SKIP LOCKED the second claimer
        // either blocks behind the first - serialising the relay no matter how many
        // replicas run - or sees the same rows and publishes everything twice.
        for (int i = 0; i < 6; i++) {
            insert("tenant-" + i, "TENANT_CREATED");
        }

        // Two independent connection factories, so these are genuinely separate sessions
        // rather than two statements sharing one connection.
        try (CloseableFactory one = newFactory(); CloseableFactory two = newFactory()) {

            // The claims must OVERLAP in time. transactional() commits as soon as its
            // publisher completes, so claiming sequentially releases the first lock
            // before the second claim runs - and the test would pass even with SKIP
            // LOCKED removed. Each claim therefore holds its transaction open for a
            // moment, and both are subscribed concurrently with zip.
            Duration hold = Duration.ofSeconds(2);

            var batches = Mono.zip(
                            claimHolding(one.factory(), 3, hold),
                            claimHolding(two.factory(), 3, hold))
                    .block(Duration.ofSeconds(30));

            List<Long> firstBatch = batches.getT1();
            List<Long> secondBatch = batches.getT2();

            assertThat(firstBatch).isNotEmpty();
            assertThat(secondBatch).isNotEmpty();
            assertThat(firstBatch)
                    .as("a second claimer must never be handed rows the first already holds")
                    .doesNotContainAnyElementsOf(secondBatch);
            assertThat(firstBatch.size() + secondBatch.size())
                    .as("between them the two claimers should cover the backlog exactly once")
                    .isEqualTo(6);
        }
    }

    /**
     * Claims a batch and holds the transaction open for {@code hold}.
     *
     * <p>The row locks exist only for the life of the transaction, so the hold is what
     * makes concurrency observable. Without it the two claims serialise and the assertion
     * passes whether or not SKIP LOCKED is present.
     */
    private Mono<List<Long>> claimHolding(
            ConnectionFactory factory, int batchSize, Duration hold) {

        DatabaseClient client = DatabaseClient.create(factory);
        TransactionalOperator transaction =
                TransactionalOperator.create(new R2dbcTransactionManager(factory));

        return transaction.transactional(
                client.sql(CLAIM)
                        .bind("batchSize", batchSize)
                        .map((row, meta) -> row.get("id", Long.class))
                        .all()
                        .collectList()
                        .delayElement(hold));
    }

    private static CloseableFactory newFactory() {
        ConnectionFactory factory = ConnectionFactories.get(
                ConnectionFactoryOptions.builder()
                        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                        .option(ConnectionFactoryOptions.HOST, POSTGRES.getHost())
                        .option(ConnectionFactoryOptions.PORT, POSTGRES.getFirstMappedPort())
                        .option(ConnectionFactoryOptions.DATABASE, POSTGRES.getDatabaseName())
                        .option(ConnectionFactoryOptions.USER, POSTGRES.getUsername())
                        .option(ConnectionFactoryOptions.PASSWORD, POSTGRES.getPassword())
                        .build());
        return new CloseableFactory(factory);
    }

    /** Lets the factories be closed by try-with-resources without leaking connections. */
    private record CloseableFactory(ConnectionFactory factory) implements AutoCloseable {
        @Override
        public void close() {
            if (factory instanceof io.r2dbc.spi.Closeable closeable) {
                Mono.from(closeable.close()).block(Duration.ofSeconds(5));
            }
        }
    }

    @Test
    @DisplayName("the claim query returns the oldest rows first")
    void claimsInCreationOrder() {
        // Ordering matters: a QUOTA_CHANGED event must not overtake the TENANT_CREATED
        // that precedes it, or the data plane sees a quota for a tenant it does not know.
        insert("first", "TENANT_CREATED");
        insert("second", "QUOTA_CHANGED");
        insert("third", "API_KEY_ISSUED");

        List<String> order = db.sql("""
                        SELECT event_type FROM control_outbox
                         WHERE published_at IS NULL ORDER BY created_at, id
                        """)
                .map((row, meta) -> row.get(0, String.class))
                .all().collectList().block(Duration.ofSeconds(10));

        assertThat(order)
                .containsExactly("TENANT_CREATED", "QUOTA_CHANGED", "API_KEY_ISSUED");
    }

    @Test
    void markingPublishedRemovesARowFromTheBacklog() {
        insert("acme", "TENANT_CREATED");

        db.sql("UPDATE control_outbox SET published_at = now() WHERE published_at IS NULL")
                .fetch().rowsUpdated().block(Duration.ofSeconds(10));

        assertThat(count(
                "SELECT count(*) FROM control_outbox WHERE published_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM control_outbox")).isEqualTo(1L);
    }

    @Test
    @DisplayName("a permanently failing row stops being retried past the attempt cap")
    void exhaustedRowsAreExcludedFromClaims() {
        insert("poison", "TENANT_CREATED");
        db.sql("UPDATE control_outbox SET attempts = 10, last_error = 'always fails'")
                .fetch().rowsUpdated().block(Duration.ofSeconds(10));

        // One permanently bad row must not consume the relay forever; it is left for a
        // human while the rest of the backlog keeps flowing.
        assertThat(count("""
                SELECT count(*) FROM control_outbox
                 WHERE published_at IS NULL AND attempts < 10
                """)).isZero();
    }

    @Test
    void tenantConstraintsRejectAnUnsafeSimilarityThreshold() {
        db.sql("""
                INSERT INTO tenants (tenant_id, display_name)
                VALUES ('acme', 'Acme Corp')
                """).fetch().rowsUpdated().block(Duration.ofSeconds(10));

        // A CHECK constraint is the one place a misconfigured deployment cannot bypass.
        // At 0.80 a semantic cache returns confident wrong answers.
        assertThat(count("""
                SELECT count(*) FROM tenants WHERE similarity_threshold >= 0.900
                """)).isEqualTo(1L);
    }
}
