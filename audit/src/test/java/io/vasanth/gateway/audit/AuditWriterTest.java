package io.vasanth.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * What the audit writer binds, and what it must never change.
 *
 * <h2>Why these assertions matter more than they look</h2>
 *
 * <p>An audit log's value is that it records what happened, unaltered. The two ways to
 * destroy that quietly are to drop a field and to invent one, and both live in
 * {@code nullSafe}: R2DBC refuses a plain null bind, so optional fields have to be
 * normalised to something - and the choice of <b>empty string rather than a sentinel like
 * "unknown"</b> is the whole point. In an audit export, "unknown" is indistinguishable from
 * a value someone actually supplied, which makes the record unusable as evidence.
 *
 * <p>{@code AuditWriterIT} proves the insert works against real Postgres. It does not prove
 * that a null stays empty rather than becoming a word.
 */
class AuditWriterTest {

    private static final Instant OCCURRED = Instant.parse("2026-11-09T12:34:56Z");

    private Map<String, Object> binds;
    private SimpleMeterRegistry meters;
    private AuditWriter writer;
    private long rowsUpdated;

    /** How many times the statement was actually SUBSCRIBED, i.e. sent to the database. */
    private final java.util.concurrent.atomic.AtomicInteger executions =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        binds = new LinkedHashMap<>();
        meters = new SimpleMeterRegistry();
        rowsUpdated = 1L;

        DatabaseClient db = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);

        when(db.sql(anyString())).thenReturn(spec);
        when(spec.bind(anyString(), any())).thenAnswer(bind -> {
            binds.put(bind.getArgument(0), bind.getArgument(1));
            return spec;
        });
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.fromCallable(() -> {
            executions.incrementAndGet();
            return rowsUpdated;
        }));

        writer = new AuditWriter(db, meters);
    }

    private static GatewayEvents.AuditEvent event(
            String fingerprint, String model, String tier, String outcome, String address) {
        return new GatewayEvents.AuditEvent(
                1, "req-1", "acme", fingerprint, model, tier, outcome, address, OCCURRED);
    }

    private static GatewayEvents.AuditEvent complete() {
        return event("a1b2c3d4e5f60718", "gpt-4o-mini", "tier1_exact", "200", "203.0.113.7");
    }

    private double counter(String name) {
        return Optional.ofNullable(meters.find(name).counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    @Test
    @DisplayName("every field is written through unchanged")
    void writesEveryFieldVerbatim() {
        StepVerifier.create(writer.write(complete())).verifyComplete();

        assertThat(binds.get("tenantId")).isEqualTo("acme");
        assertThat(binds.get("requestId")).isEqualTo("req-1");
        assertThat(binds.get("apiKeyFingerprint")).isEqualTo("a1b2c3d4e5f60718");
        assertThat(binds.get("modelId")).isEqualTo("gpt-4o-mini");
        assertThat(binds.get("servedBy")).isEqualTo("tier1_exact");
        assertThat(binds.get("outcome")).isEqualTo("200");
        assertThat(binds.get("sourceAddress")).isEqualTo("203.0.113.7");
        assertThat(binds.get("occurredAt")).isEqualTo(OCCURRED);
        assertThat(counter("audit.entries.written")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a null optional field becomes EMPTY, never a sentinel word")
    void nullsBecomeEmptyNotASentinel() {
        StepVerifier.create(writer.write(event(null, null, null, null, null)))
                .verifyComplete();

        // Empty string, because in an export a sentinel such as "unknown" or "n/a" cannot
        // be told apart from a value a caller actually sent - which turns the audit log
        // from evidence into a guess.
        assertThat(binds.get("apiKeyFingerprint")).isEqualTo("");
        assertThat(binds.get("modelId")).isEqualTo("");
        assertThat(binds.get("servedBy")).isEqualTo("");
        assertThat(binds.get("outcome")).isEqualTo("");
        assertThat(binds.get("sourceAddress")).isEqualTo("");

        for (Object value : binds.values()) {
            assertThat(String.valueOf(value))
                    .as("no bound value may be a sentinel")
                    .isNotEqualToIgnoringCase("unknown")
                    .isNotEqualToIgnoringCase("null")
                    .isNotEqualToIgnoringCase("n/a");
        }
    }

    @Test
    @DisplayName("the identity fields are never normalised away")
    void identityIsNotNormalised() {
        StepVerifier.create(writer.write(event(null, null, null, null, null)))
                .verifyComplete();

        // tenantId and requestId are non-null by the record's own constructor, so they
        // must arrive untouched rather than through nullSafe - an audit row that lost its
        // tenant would be unattributable.
        assertThat(binds.get("tenantId")).isEqualTo("acme");
        assertThat(binds.get("requestId")).isEqualTo("req-1");
    }

    @Test
    @DisplayName("an empty string stays empty rather than being re-normalised")
    void emptyStaysEmpty() {
        StepVerifier.create(writer.write(event("", "", "", "", ""))).verifyComplete();

        assertThat(binds.get("outcome")).isEqualTo("");
    }

    @Test
    @DisplayName("a duplicate is counted as such and is not an error")
    void duplicateIsCounted() {
        // ON CONFLICT DO NOTHING fired: Kafka redelivered an event already written.
        rowsUpdated = 0L;

        StepVerifier.create(writer.write(complete())).verifyComplete();

        assertThat(counter("audit.entries.duplicate")).isEqualTo(1.0d);
        assertThat(counter("audit.entries.written")).isZero();
    }

    @Test
    @DisplayName("nothing reaches the database before subscription")
    void coldUntilSubscribed() {
        Mono<Void> work = writer.write(complete());

        // The BINDS are already populated at this point, and that is not a defect: the
        // fluent chain is built eagerly and only the statement's execution is deferred.
        // Asserting on binds here would be asserting about assembly, which says nothing
        // about whether anything was written. The same shape appears in
        // SagaOrchestrator.markState and is pinned there too.
        assertThat(binds).isNotEmpty();
        assertThat(executions.get()).isZero();

        StepVerifier.create(work).verifyComplete();

        assertThat(executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("each subscription writes exactly once")
    void oneWritePerSubscription() {
        StepVerifier.create(writer.write(complete())).verifyComplete();

        // An audit log is write-once per event. A chain that wrote twice per subscription
        // would duplicate the record it exists to preserve - caught by the ON CONFLICT in
        // the schema, but it would show up as a phantom duplicate rate.
        assertThat(executions.get()).isEqualTo(1);
    }
}
