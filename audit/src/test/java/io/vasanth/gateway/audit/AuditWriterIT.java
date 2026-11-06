package io.vasanth.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

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

/** Audit persistence against a real Postgres, including the time-range partitioning. */
@Testcontainers
@SpringBootTest(properties = "gateway.kafka.bootstrap-servers=localhost:1")
class AuditWriterIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("audit")
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

    @Autowired private AuditWriter writer;
    @Autowired private DatabaseClient db;

    private static GatewayEvents.AuditEvent event(String requestId, Instant at) {
        return new GatewayEvents.AuditEvent(
                GatewayEvents.SCHEMA_VERSION, requestId, "acme",
                "a1b2c3d4e5f60718", "llama-3.1-8b", "tier1_exact", "200",
                "203.0.113.5", at);
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, Long.class)).one()
                .block(Duration.ofSeconds(10));
    }

    @BeforeEach
    void clean() {
        db.sql("TRUNCATE audit_log").fetch().rowsUpdated().block(Duration.ofSeconds(10));
    }

    @Test
    void writesAnEntry() {
        writer.write(event("req-1", Instant.parse("2026-11-02T10:00:00Z")))
                .block(Duration.ofSeconds(10));

        assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(1L);
    }

    @Test
    @DisplayName("redelivery does not duplicate an audit entry")
    void isIdempotent() {
        // Otherwise "how many times did this happen" becomes unanswerable, which defeats
        // the purpose of an audit log.
        GatewayEvents.AuditEvent duplicate = event("req-1", Instant.parse("2026-11-02T10:00:00Z"));

        for (int i = 0; i < 4; i++) {
            writer.write(duplicate).block(Duration.ofSeconds(10));
        }

        assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(1L);
    }

    @Test
    @DisplayName("only a key fingerprint is stored, never anything that could authenticate")
    void storesOnlyAFingerprint() {
        writer.write(event("req-1", Instant.parse("2026-11-02T10:00:00Z")))
                .block(Duration.ofSeconds(10));

        String stored = db.sql("SELECT api_key_fingerprint FROM audit_log LIMIT 1")
                .map((row, meta) -> row.get(0, String.class)).one()
                .block(Duration.ofSeconds(10));

        // An audit table is exactly the kind of thing exported to a compliance reviewer.
        assertThat(stored).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    @DisplayName("an event lands in the time partition covering its timestamp")
    void routesToTheCorrectTimePartition() {
        writer.write(event("q4", Instant.parse("2026-11-02T10:00:00Z")))
                .block(Duration.ofSeconds(10));
        writer.write(event("q1", Instant.parse("2027-02-15T10:00:00Z")))
                .block(Duration.ofSeconds(10));

        assertThat(count(
                "SELECT count(*) FROM audit_log_2026q4")).isEqualTo(1L);
        assertThat(count(
                "SELECT count(*) FROM audit_log_2027q1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("an event outside the provisioned windows is kept, not rejected")
    void usesTheDefaultPartitionRatherThanFailing() {
        // Without a DEFAULT partition, a clock skew or a late backfill would fail the
        // insert outright and the record would be lost. Losing audit records to a
        // partitioning detail would be indefensible.
        writer.write(event("ancient", Instant.parse("2020-01-01T00:00:00Z")))
                .block(Duration.ofSeconds(10));

        assertThat(count("SELECT count(*) FROM audit_log_default")).isEqualTo(1L);
    }

    @Test
    void toleratesMissingOptionalFields() {
        GatewayEvents.AuditEvent sparse = new GatewayEvents.AuditEvent(
                GatewayEvents.SCHEMA_VERSION, "req-sparse", "acme",
                null, null, null, null, null, Instant.parse("2026-11-02T10:00:00Z"));

        writer.write(sparse).block(Duration.ofSeconds(10));

        assertThat(count("SELECT count(*) FROM audit_log")).isEqualTo(1L);
    }
}
