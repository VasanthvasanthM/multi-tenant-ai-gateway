package io.vasanth.gateway.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vasanth.gateway.common.event.GatewayEvents;

/**
 * The link nothing else tested: a real broker between a producer and a consumer.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link UsageLedgerIT} proves what happens <em>once an event reaches</em>
 * {@code UsageLedger}, against a real Postgres, and says so explicitly - it points the
 * consumer at {@code localhost:1}. The consumer's own tests mock the Kafka API. Until this
 * file, {@code grep -rln KafkaContainer} returned nothing across the whole repository, so:
 *
 * <ul>
 *   <li>the JSON a producer writes had never been deserialised by a consumer
 *   <li>no offset was ever committed to a real broker
 *   <li>no consumer group was ever formed
 *   <li>the four consumer settings in {@code KafkaFactory} were asserted on the config
 *       <em>map</em> rather than observed in behaviour
 *   <li>the dead-letter path had never carried a real message
 * </ul>
 *
 * <p>A shared compiled event record in {@code common} stops the producer and consumer
 * disagreeing about field names, which is a real protection - but a compile-time one. It
 * says nothing about serialisation round-trips or offset handling. Tracked as A36.
 *
 * <h2>Why the consumer is not started by hand</h2>
 *
 * <p>{@code EventConsumerRunner} is a {@code SmartLifecycle}, so Spring starts it with the
 * context. Pointing {@code gateway.kafka.bootstrap-servers} at the container is therefore
 * the entire wiring - which is what makes this a test of the production path rather than
 * of a hand-assembled approximation of it.
 */
@Testcontainers
@SpringBootTest
class UsageConsumerKafkaIT {

    private static final Duration PATIENCE = Duration.ofSeconds(30);

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("metering")
                    .withUsername("test")
                    .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("gateway.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Topics are created HERE rather than in @BeforeAll, because this hook is the only
        // one that runs after the containers are up and before the Spring context - and
        // the context starts the consumer immediately. Relying on auto-creation instead
        // would make the test depend on metadata.max.age.ms, which defaults to five
        // minutes: the consumer would subscribe to a topic that did not exist yet and sit
        // idle long past any sensible timeout.
        createTopics();
    }

    private static void createTopics() {
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(config)) {
            admin.createTopics(List.of(
                    new NewTopic(GatewayEvents.TOPIC_USAGE, 1, (short) 1),
                    new NewTopic(GatewayEvents.TOPIC_DEAD_LETTER, 1, (short) 1))).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted creating topics", e);
        } catch (Exception e) {
            throw new IllegalStateException("could not create topics", e);
        }
    }

    @Autowired private DatabaseClient db;
    @Autowired private ObjectMapper objectMapper;

    /** A producer configured as the gateway's is: acks=all and idempotent. */
    private static KafkaProducer<String, String> producer() {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaProducer<>(config);
    }

    private static GatewayEvents.UsageEvent upstreamEvent(String requestId, String tenant) {
        return new GatewayEvents.UsageEvent(
                GatewayEvents.SCHEMA_VERSION,
                requestId,
                tenant,
                "gpt-4o-mini",
                "upstream",
                "openai",
                120L,
                180L,
                40L,
                Instant.now());
    }

    /**
     * Publishes with the tenant id as the partition key, exactly as the gateway does.
     *
     * <p>The key is not decoration: it is what puts one tenant's events on a single
     * partition and therefore in order. Publishing without it would pass these assertions
     * while silently removing the ordering metering depends on.
     */
    private void publish(String topic, String key, String payload) throws Exception {
        try (KafkaProducer<String, String> producer = producer()) {
            producer.send(new ProducerRecord<>(topic, key, payload)).get();
        }
    }

    private void publishUsage(GatewayEvents.UsageEvent event) throws Exception {
        publish(GatewayEvents.TOPIC_USAGE, event.tenantId(),
                objectMapper.writeValueAsString(event));
    }

    private Long ledgerRows(String requestId) {
        return db.sql("SELECT count(*) FROM usage_ledger WHERE request_id = :id")
                .bind("id", requestId)
                .map(row -> row.get(0, Long.class))
                .one()
                .block(PATIENCE);
    }

    private Map<String, Object> ledgerRow(String requestId) {
        return db.sql("""
                        SELECT tenant_id, model_id, served_by, provider,
                               prompt_tokens, completion_tokens, total_tokens,
                               billable_upstream, latency_millis
                          FROM usage_ledger WHERE request_id = :id
                        """)
                .bind("id", requestId)
                .fetch()
                .one()
                .block(PATIENCE);
    }

    private static long asLong(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    @DisplayName("an event published to a real broker lands in the ledger, field for field")
    void endToEnd() throws Exception {
        String requestId = UUID.randomUUID().toString();

        publishUsage(upstreamEvent(requestId, "acme"));

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(ledgerRows(requestId)).isEqualTo(1L));

        // Every field, because a JSON round trip is exactly where a renamed or mistyped
        // one would be lost - silently, leaving a plausible row that bills the wrong
        // amount. The event's wire names are abbreviated (v, rid, tid, pt, ct, ms), so
        // this also pins the @JsonProperty mapping in both directions.
        Map<String, Object> row = ledgerRow(requestId);
        assertThat(row).containsEntry("tenant_id", "acme");
        assertThat(row).containsEntry("model_id", "gpt-4o-mini");
        assertThat(row).containsEntry("served_by", "upstream");
        assertThat(row).containsEntry("provider", "openai");
        assertThat(asLong(row.get("prompt_tokens"))).isEqualTo(120L);
        assertThat(asLong(row.get("completion_tokens"))).isEqualTo(180L);
        assertThat(asLong(row.get("latency_millis"))).isEqualTo(40L);

        // Denormalised by the writer, not carried on the event. If this ever disagrees
        // with the two token columns, the "spend this period" query - which reads only
        // this column - bills a number nothing else in the system agrees with.
        assertThat(asLong(row.get("total_tokens"))).isEqualTo(300L);

        // True because this one went upstream. The cache's entire value is invisible on
        // the bill without this column.
        assertThat(row).containsEntry("billable_upstream", true);
    }

    @Test
    @DisplayName("a cache hit is recorded as consumed quota but NOT billable upstream")
    void cacheHitIsNotBillable() throws Exception {
        String requestId = UUID.randomUUID().toString();

        publishUsage(new GatewayEvents.UsageEvent(
                GatewayEvents.SCHEMA_VERSION, requestId, "acme", "gpt-4o-mini",
                "tier1_exact", "cache", 120L, 180L, 2L, Instant.now()));

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(ledgerRows(requestId)).isEqualTo(1L));

        Map<String, Object> row = ledgerRow(requestId);
        assertThat(row).containsEntry("served_by", "tier1_exact");
        // The tenant used quota and no vendor was paid. Getting this backwards would
        // either under-bill the customer or over-state the cache's saving.
        assertThat(row).containsEntry("billable_upstream", false);
        assertThat(asLong(row.get("total_tokens"))).isEqualTo(300L);
    }

    @Test
    @DisplayName("the same event delivered twice bills once, across a real offset commit")
    void redeliveryIsIdempotent() throws Exception {
        String requestId = UUID.randomUUID().toString();
        GatewayEvents.UsageEvent event = upstreamEvent(requestId, "globex");

        publishUsage(event);
        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(ledgerRows(requestId)).isEqualTo(1L));

        // Kafka guarantees at-least-once, so this is not hypothetical: a consumer restart
        // between the write and the offset commit produces exactly this. The existing
        // unit test proves ON CONFLICT DO NOTHING works; this proves it still holds when
        // the duplicate arrives through a broker and a real commit.
        publishUsage(event);

        await().during(Duration.ofSeconds(3)).atMost(PATIENCE).untilAsserted(() ->
                assertThat(ledgerRows(requestId)).isEqualTo(1L));
    }

    @Test
    @DisplayName("an unparseable payload does not block the partition behind it")
    void poisonMessageDoesNotBlockThePartition() throws Exception {
        publish(GatewayEvents.TOPIC_USAGE, "acme", "{\"this\":\"is not a usage event\"");

        // The real assertion is not that the bad message is handled - it is that the
        // partition keeps moving. A consumer that retried forever would stop here, and
        // every tenant sharing this partition would stop being billed, with no error
        // anywhere except a growing consumer lag.
        String requestId = UUID.randomUUID().toString();
        publishUsage(upstreamEvent(requestId, "acme"));

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(ledgerRows(requestId)).isEqualTo(1L));
    }

    @Test
    @DisplayName("an event from an unknown schema version is skipped, not half-parsed")
    void unknownSchemaVersionIsRejected() throws Exception {
        String requestId = UUID.randomUUID().toString();
        GatewayEvents.UsageEvent event = upstreamEvent(requestId, "initech");

        // Producers and consumers deploy independently, so an old consumer WILL see a new
        // event. The version exists so it can recognise what it cannot handle rather than
        // "parsing it half-correctly and writing plausible-looking nonsense to the
        // ledger". The wire name is "v", not "schemaVersion".
        String payload = objectMapper.writeValueAsString(event)
                .replace("\"v\":" + GatewayEvents.SCHEMA_VERSION, "\"v\":9999");
        publish(GatewayEvents.TOPIC_USAGE, event.tenantId(), payload);

        // Then a well-formed one, to prove the consumer moved on rather than stalling.
        String goodId = UUID.randomUUID().toString();
        publishUsage(upstreamEvent(goodId, "initech"));

        await().atMost(PATIENCE).untilAsserted(() ->
                assertThat(ledgerRows(goodId)).isEqualTo(1L));

        // And the unreadable one produced no row at all.
        assertThat(ledgerRows(requestId)).isZero();
    }
}
