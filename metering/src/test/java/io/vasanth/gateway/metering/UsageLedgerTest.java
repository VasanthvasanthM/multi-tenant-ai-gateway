package io.vasanth.gateway.metering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.transaction.reactive.TransactionalOperator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The usage ledger's idempotency and rollup arithmetic.
 *
 * <h2>The invariant this exists to protect</h2>
 *
 * <p>{@code UsageLedgerIT} proves the insert and the upsert work against real Postgres. It
 * does not prove the thing that actually breaks billing: that a <b>duplicate event must not
 * touch the rollup</b>. Kafka redelivers on every rebalance and every consumer restart, so
 * duplicates are routine rather than exceptional. If a redelivered event skipped the ledger
 * insert via {@code ON CONFLICT DO NOTHING} but still incremented the aggregate, the rollup
 * would drift upward from the ledger on every restart - and the aggregate is what the
 * dashboard and the invoice read.
 *
 * <p>That is a branch on a row count, so it is testable without a database, and worth
 * testing there because an integration test that never redelivers will never see it.
 */
class UsageLedgerTest {

    private static final Instant OCCURRED = Instant.parse("2026-11-09T12:34:56Z");

    /** Every statement, with the parameters that were bound to it. */
    private record Statement(String sql, Map<String, Object> binds) {
    }

    private List<Statement> statements;
    private SimpleMeterRegistry meters;
    private UsageLedger ledger;
    private long rowsForInsert;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        statements = new CopyOnWriteArrayList<>();
        meters = new SimpleMeterRegistry();
        rowsForInsert = 1L;

        DatabaseClient db = mock(DatabaseClient.class);

        when(db.sql(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            Map<String, Object> binds = new LinkedHashMap<>();
            Statement statement = new Statement(sql, binds);

            DatabaseClient.GenericExecuteSpec spec =
                    mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
            when(spec.bind(anyString(), any())).thenAnswer(bind -> {
                binds.put(bind.getArgument(0), bind.getArgument(1));
                return spec;
            });

            FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
            when(spec.fetch()).thenReturn(fetch);
            when(fetch.rowsUpdated()).thenReturn(Mono.fromCallable(() -> {
                statements.add(statement);
                return sql.contains("INSERT INTO usage_ledger") ? rowsForInsert : 1L;
            }));
            return spec;
        });

        // Pass-through: the transaction boundary itself is Spring's concern and is
        // covered by the IT. What matters here is that record() goes THROUGH it.
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ledger = new UsageLedger(db, transactions, meters);
    }

    /**
     * A usage event served by the given tier.
     *
     * <p>{@code billableUpstream()} is DERIVED from {@code servedBy} rather than being a
     * field, so the tier is the only knob - which is the right shape: an event cannot
     * claim to be a cache hit and be billed upstream at the same time.
     */
    private static GatewayEvents.UsageEvent servedBy(String tier) {
        return new GatewayEvents.UsageEvent(
                1, "req-1", "acme", "gpt-4o-mini", tier,
                "upstream".equals(tier) ? "openai" : "cache",
                100L, 40L, 820L, OCCURRED);
    }

    private static GatewayEvents.UsageEvent upstreamEvent() {
        return servedBy("upstream");
    }

    private static GatewayEvents.UsageEvent cachedEvent() {
        return servedBy("tier1_exact");
    }

    private double counter(String name) {
        return Optional.ofNullable(meters.find(name).counter())
                .map(Counter::count)
                .orElse(0.0d);
    }

    private List<String> executedKinds() {
        List<String> kinds = new ArrayList<>();
        for (Statement statement : statements) {
            kinds.add(statement.sql().contains("INSERT INTO usage_ledger")
                    ? "ledger" : "rollup");
        }
        return kinds;
    }

    private Map<String, Object> rollupBinds() {
        return statements.stream()
                .filter(s -> !s.sql().contains("INSERT INTO usage_ledger"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no rollup statement was executed"))
                .binds();
    }

    // ------------------------------------------------------------------
    // Idempotency
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("a first-time event writes the ledger AND the rollup")
        void firstTimeWritesBoth() {
            StepVerifier.create(ledger.record(upstreamEvent())).verifyComplete();

            assertThat(executedKinds()).containsExactly("ledger", "rollup");
            assertThat(counter("metering.usage.recorded")).isEqualTo(1.0d);
            assertThat(counter("metering.usage.duplicate")).isZero();
        }

        @Test
        @DisplayName("a DUPLICATE event writes the ledger attempt and NOT the rollup")
        void duplicateSkipsTheRollup() {
            // ON CONFLICT DO NOTHING fired, so the insert affected no rows.
            rowsForInsert = 0L;

            StepVerifier.create(ledger.record(upstreamEvent())).verifyComplete();

            // The invariant. Incrementing the rollup here would make the aggregate drift
            // upward from the ledger on every Kafka rebalance, and the aggregate is what
            // the dashboard and the invoice read.
            assertThat(executedKinds()).containsExactly("ledger");
            assertThat(counter("metering.usage.duplicate")).isEqualTo(1.0d);
            assertThat(counter("metering.usage.recorded")).isZero();
        }

        @Test
        @DisplayName("a duplicate is a normal outcome, not an error signal")
        void duplicateCompletesQuietly() {
            rowsForInsert = 0L;

            // A consumer that treated redelivery as a failure would retry forever and
            // never advance its offset.
            StepVerifier.create(ledger.record(upstreamEvent())).verifyComplete();
        }
    }

    // ------------------------------------------------------------------
    // Rollup arithmetic
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("rollup")
    class Rollup {

        @Test
        @DisplayName("an upstream call contributes its tokens and no cache hit")
        void upstreamCall() {
            StepVerifier.create(ledger.record(upstreamEvent())).verifyComplete();

            Map<String, Object> binds = rollupBinds();
            assertThat(binds.get("cacheHit")).isEqualTo(0);
            assertThat(binds.get("upstreamTokens")).isEqualTo(140L);
            assertThat(binds.get("promptTokens")).isEqualTo(100L);
            assertThat(binds.get("completionTokens")).isEqualTo(40L);
        }

        @Test
        @DisplayName("a cache hit contributes a hit and ZERO upstream tokens")
        void cacheHit() {
            StepVerifier.create(ledger.record(cachedEvent())).verifyComplete();

            Map<String, Object> binds = rollupBinds();
            assertThat(binds.get("cacheHit")).isEqualTo(1);
            // The whole economic claim of the product rests on this number. Counting a
            // cache hit's tokens as upstream spend would make the saving invisible; the
            // tenant still consumed quota, which is why promptTokens is unchanged.
            assertThat(binds.get("upstreamTokens")).isEqualTo(0L);
            assertThat(binds.get("promptTokens")).isEqualTo(100L);
        }

        @Test
        @DisplayName("the period is truncated to the hour, not the raw timestamp")
        void periodIsTruncated() {
            StepVerifier.create(ledger.record(upstreamEvent())).verifyComplete();

            // 12:34:56 belongs in the 12:00 bucket. Binding the raw instant would create a
            // new rollup row per request, which defeats the point of an aggregate - at a
            // thousand requests a second that is 86 million rows a day.
            assertThat(rollupBinds().get("periodHour"))
                    .isEqualTo(Instant.parse("2026-11-09T12:00:00Z"));
        }

        @Test
        @DisplayName("the rollup is keyed by the same tenant as the ledger row")
        void tenantMatches() {
            StepVerifier.create(ledger.record(upstreamEvent())).verifyComplete();

            assertThat(rollupBinds().get("tenantId")).isEqualTo("acme");
        }
    }

    @Test
    @DisplayName("the whole operation goes through the transactional operator")
    void isTransactional() {
        TransactionalOperator transactions = mock(TransactionalOperator.class);
        when(transactions.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DatabaseClient db = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec spec =
                mock(DatabaseClient.GenericExecuteSpec.class, RETURNS_SELF);
        @SuppressWarnings("unchecked")
        FetchSpec<Map<String, Object>> fetch = mock(FetchSpec.class);
        when(db.sql(anyString())).thenReturn(spec);
        when(spec.fetch()).thenReturn(fetch);
        when(fetch.rowsUpdated()).thenReturn(Mono.just(1L));

        UsageLedger scoped = new UsageLedger(db, transactions, new SimpleMeterRegistry());
        StepVerifier.create(scoped.record(upstreamEvent())).verifyComplete();

        // Ledger insert and rollup update must share a transaction, or the aggregate can
        // end up reflecting a request the ledger does not contain - which is unauditable.
        org.mockito.Mockito.verify(transactions).transactional(any(Mono.class));
    }

    @Test
    @DisplayName("nothing is written before subscription")
    void coldUntilSubscribed() {
        Mono<Void> work = ledger.record(upstreamEvent());

        // A consumer that assembles without subscribing must not have billed anyone.
        assertThat(statements).isEmpty();

        StepVerifier.create(work).verifyComplete();
        assertThat(statements).isNotEmpty();
    }
}
