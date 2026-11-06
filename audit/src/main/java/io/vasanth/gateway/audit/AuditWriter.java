package io.vasanth.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Mono;

/**
 * Appends audit entries.
 *
 * <p>Deliberately the simplest component in the system. An audit writer that does
 * anything clever - enriching, deduplicating by content, summarising - is an audit writer
 * that can distort the record it exists to preserve. It inserts what it was given, once.
 */
@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    /**
     * The conflict target must name the unique constraint exactly, including
     * {@code occurred_at}.
     *
     * <p>{@code audit_log} is range-partitioned by time, and Postgres requires the
     * partition key in every unique constraint - so the idempotency key is the triple,
     * not the pair. A conflict target that does not match a real constraint fails at
     * runtime with "no unique or exclusion constraint matching the ON CONFLICT
     * specification", not at startup.
     */
    private static final String INSERT = """
            INSERT INTO audit_log (
                tenant_id, request_id, api_key_fingerprint, model_id,
                served_by, outcome, source_address, occurred_at)
            VALUES (
                :tenantId, :requestId, :apiKeyFingerprint, :modelId,
                :servedBy, :outcome, :sourceAddress, :occurredAt)
            ON CONFLICT (occurred_at, tenant_id, request_id) DO NOTHING
            """;

    private final DatabaseClient db;
    private final Counter written;
    private final Counter duplicates;

    public AuditWriter(DatabaseClient db, MeterRegistry meterRegistry) {
        this.db = db;
        this.written = Counter.builder("audit.entries.written").register(meterRegistry);
        this.duplicates = Counter.builder("audit.entries.duplicate").register(meterRegistry);
    }

    public Mono<Void> write(GatewayEvents.AuditEvent event) {
        return db.sql(INSERT)
                .bind("tenantId", event.tenantId())
                .bind("requestId", event.requestId())
                .bind("apiKeyFingerprint", nullSafe(event.apiKeyFingerprint()))
                .bind("modelId", nullSafe(event.modelId()))
                .bind("servedBy", nullSafe(event.servedBy()))
                .bind("outcome", nullSafe(event.outcome()))
                .bind("sourceAddress", nullSafe(event.sourceAddress()))
                .bind("occurredAt", event.occurredAt())
                .fetch()
                .rowsUpdated()
                .doOnNext(rows -> {
                    if (rows == 0L) {
                        duplicates.increment();
                        log.debug("duplicate audit event ignored: {}", event.requestId());
                    } else {
                        written.increment();
                    }
                })
                .then();
    }

    /**
     * R2DBC rejects a null bind outright rather than writing SQL NULL, so optional fields
     * are normalised here.
     *
     * <p>Empty string is used rather than a sentinel such as "unknown", because in an
     * audit export a sentinel is indistinguishable from a value someone actually supplied.
     */
    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
