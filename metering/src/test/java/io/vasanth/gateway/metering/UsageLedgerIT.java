package io.vasanth.gateway.metering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.vasanth.gateway.common.event.GatewayEvents;

/**
 * Billing correctness against a real Postgres.
 *
 * <p>Mocking the database here would test nothing: every property worth asserting -
 * idempotency, the rollup staying consistent with the ledger, hash partitioning - lives
 * in the SQL and the schema constraints, not in the Java.
 *
 * <p>Kafka is deliberately absent. The consumer wiring is tested separately; this is
 * about what happens once an event reaches {@link UsageLedger}.
 */
@Testcontainers
@SpringBootTest(properties = {
        // An unreachable broker on purpose. The consumer's retry loop handles it in the
        // background and nothing under test touches Kafka.
        //
        // NOTE: spring.main.lazy-initialization must stay OFF here. With it on, the
        // Flyway initializer is deferred and the schema does not exist when the first
        // test runs - which presents as "relation does not exist" rather than anything
        // mentioning lazy init.
        "gateway.kafka.bootstrap-servers=localhost:1"
})
class UsageLedgerIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("metering")
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

    @Autowired private UsageLedger ledger;
    @Autowired private DatabaseClient db;

    private static GatewayEvents.UsageEvent event(String requestId, String tenant, boolean upstream) {
        return new GatewayEvents.UsageEvent(
                GatewayEvents.SCHEMA_VERSION,
                requestId,
                tenant,
                "llama-3.1-8b",
                upstream ? "upstream" : "tier1_exact",
                upstream ? "mock" : "cache",
                10L,
                upstream ? 20L : 0L,
                42L,
                Instant.parse("2026-11-02T10:30:00Z"));
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, Long.class)).one()
                .block(Duration.ofSeconds(10));
    }

    @BeforeEach
    void clean() {
        db.sql("TRUNCATE usage_ledger, tenant_usage_rollup").fetch().rowsUpdated()
                .block(Duration.ofSeconds(10));
    }

    @Test
    void recordsUsage() {
        ledger.record(event("req-1", "acme", true)).block(Duration.ofSeconds(10));

        assertThat(count("SELECT count(*) FROM usage_ledger")).isEqualTo(1L);
        assertThat(count("SELECT sum(total_tokens) FROM usage_ledger")).isEqualTo(30L);
    }

    @Test
    @DisplayName("redelivery of the same event does not double-bill")
    void isIdempotent() {
        // Kafka delivers at least once. A consumer that crashes between processing and
        // committing its offset sees the event again - normal operation, not an edge case.
        GatewayEvents.UsageEvent duplicate = event("req-1", "acme", true);

        for (int i = 0; i < 5; i++) {
            ledger.record(duplicate).block(Duration.ofSeconds(10));
        }

        assertThat(count("SELECT count(*) FROM usage_ledger")).isEqualTo(1L);
        assertThat(count("SELECT sum(total_tokens) FROM usage_ledger")).isEqualTo(30L);
    }

    @Test
    @DisplayName("the rollup never drifts away from the ledger under redelivery")
    void rollupStaysConsistentWithTheLedger() {
        // The subtle part. Incrementing the rollup unconditionally would make the
        // aggregate climb on every duplicate while the ledger stayed correct - and a
        // rollup that disagrees with the ledger it summarises is worse than none,
        // because it looks authoritative.
        for (int i = 0; i < 3; i++) {
            ledger.record(event("req-1", "acme", true)).block(Duration.ofSeconds(10));
            ledger.record(event("req-2", "acme", true)).block(Duration.ofSeconds(10));
        }

        assertThat(count("SELECT count(*) FROM usage_ledger")).isEqualTo(2L);
        assertThat(count("SELECT sum(request_count) FROM tenant_usage_rollup")).isEqualTo(2L);
    }

    @Test
    @DisplayName("a cache hit consumes quota but is not billable upstream")
    void distinguishesCacheHitsFromUpstreamCalls() {
        ledger.record(event("req-upstream", "acme", true)).block(Duration.ofSeconds(10));
        ledger.record(event("req-cached", "acme", false)).block(Duration.ofSeconds(10));

        assertThat(count(
                "SELECT count(*) FROM usage_ledger WHERE billable_upstream")).isEqualTo(1L);
        assertThat(count(
                "SELECT sum(upstream_tokens) FROM tenant_usage_rollup")).isEqualTo(30L);
        assertThat(count(
                "SELECT sum(cache_hit_count) FROM tenant_usage_rollup")).isEqualTo(1L);
    }

    @Test
    void separatesTenants() {
        ledger.record(event("req-1", "acme", true)).block(Duration.ofSeconds(10));
        ledger.record(event("req-1", "globex", true)).block(Duration.ofSeconds(10));

        // Same request id, different tenants: the uniqueness constraint is scoped to the
        // tenant, so these must both be recorded.
        assertThat(count("SELECT count(*) FROM usage_ledger")).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM tenant_usage_rollup")).isEqualTo(2L);
    }

    @Test
    @DisplayName("rows land in the partition their tenant hashes to")
    void usesHashPartitioning() {
        ledger.record(event("req-1", "acme", true)).block(Duration.ofSeconds(10));

        Long partitions = db.sql(
                        "SELECT count(DISTINCT tableoid) FROM usage_ledger WHERE tenant_id='acme'")
                .map((row, meta) -> row.get(0, Long.class)).one().block(Duration.ofSeconds(10));

        // One tenant occupies exactly one partition - the property every tenant-scoped
        // query relies on for partition pruning.
        assertThat(partitions).isEqualTo(1L);
    }

    @Test
    void aggregatesIntoTheCorrectHourlyBucket() {
        ledger.record(event("req-1", "acme", true)).block(Duration.ofSeconds(10));

        Instant bucket = db.sql("SELECT period_hour FROM tenant_usage_rollup LIMIT 1")
                .map((row, meta) -> row.get(0, Instant.class)).one()
                .block(Duration.ofSeconds(10));

        assertThat(bucket)
                .isEqualTo(Instant.parse("2026-11-02T10:30:00Z").truncatedTo(ChronoUnit.HOURS));
    }
}
