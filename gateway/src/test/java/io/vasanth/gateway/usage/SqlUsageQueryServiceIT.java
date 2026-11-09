package io.vasanth.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.r2dbc.spi.ConnectionFactories;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.shard.ShardedDatabase;

/**
 * The usage queries against real PostgreSQL.
 *
 * <h2>Why an integration test</h2>
 *
 * <p>Everything in {@link SqlUsageQueryService} that can be wrong is in the SQL: the
 * {@code coalesce(sum(...))} that turns an empty window into zeros rather than nulls, the
 * {@code period_hour >= :from} window predicate, the tenant predicate, and the ordering the
 * dashboard's chart depends on. Mocking {@code DatabaseClient} would assert that the code
 * calls the methods the test expects and exercise none of it.
 *
 * <p>It also pins a type hazard that only appears against a real driver: {@code sum()} over
 * a {@code BIGINT} column returns {@code numeric}, which arrives as a {@code BigDecimal},
 * not a {@code Long}. The service funnels every aggregate through {@code asLong} for that
 * reason, and a mock returning tidy {@code Long}s would hide it.
 *
 * <h2>The schema comes from metering, not from here</h2>
 *
 * <p>{@code tenant_usage_rollup} is owned by the metering service - the gateway is a
 * read-only consumer. So Flyway is pointed at metering's own migration directory on the
 * filesystem rather than at a copy of the DDL in this test. A hand-written copy drifts
 * silently, and a cross-service read is exactly where drift hurts most: the gateway would
 * keep passing its own tests while querying columns metering had renamed.
 */
@Testcontainers
class SqlUsageQueryServiceIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine")
                    .withDatabaseName("usage")
                    .withUsername("test")
                    .withPassword("test");

    private static final TenantId ACME = TenantId.of("acme");
    private static final TenantId GLOBEX = TenantId.of("globex");

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    private static DatabaseClient db;
    private SqlUsageQueryService service;

    @BeforeAll
    static void migrateAndConnect() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + meteringMigrations())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        db = DatabaseClient.create(ConnectionFactories.get(String.format(
                "r2dbc:postgresql://%s:%s@%s:%d/%s",
                POSTGRES.getUsername(), POSTGRES.getPassword(),
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(),
                POSTGRES.getDatabaseName())));
    }

    /**
     * Locates metering's migration directory relative to wherever the test is running.
     *
     * <p>Surefire and Failsafe set the working directory to the module base, so one level
     * up is the reactor root - but that is a convention rather than a guarantee, and a
     * silently wrong path would make Flyway migrate nothing and every query fail with
     * "relation does not exist". Checking both candidates and failing with the paths tried
     * turns that into a one-line diagnosis.
     */
    private static String meteringMigrations() {
        List<Path> candidates = List.of(
                Path.of("..", "metering", "src", "main", "resources", "db", "migration"),
                Path.of("metering", "src", "main", "resources", "db", "migration"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        throw new IllegalStateException(
                "metering migrations not found; tried " + candidates
                        + " from working directory " + Path.of("").toAbsolutePath());
    }

    @BeforeEach
    void reset() {
        db.sql("TRUNCATE tenant_usage_rollup").fetch().rowsUpdated().block(PATIENCE);
        service = new SqlUsageQueryService(ShardedDatabase.single(db), FIXED);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** One rollup row, {@code hoursAgo} before the fixed clock. */
    private void insert(TenantId tenant, long hoursAgo, long requests, long hits,
            long prompt, long completion, long upstream) {
        db.sql("""
                        INSERT INTO tenant_usage_rollup (
                            tenant_id, period_hour, request_count, cache_hit_count,
                            prompt_tokens, completion_tokens, upstream_tokens)
                        VALUES (:tenant, :period, :requests, :hits, :prompt, :completion,
                                :upstream)
                        """)
                .bind("tenant", tenant.value())
                .bind("period", NOW.minus(Duration.ofHours(hoursAgo)))
                .bind("requests", requests)
                .bind("hits", hits)
                .bind("prompt", prompt)
                .bind("completion", completion)
                .bind("upstream", upstream)
                .fetch()
                .rowsUpdated()
                .block(PATIENCE);
    }

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("sums every row inside the window")
        void sumsAcrossTheWindow() {
            insert(ACME, 1, 100, 70, 60_000, 40_000, 30_000);
            insert(ACME, 2, 200, 140, 120_000, 80_000, 60_000);

            UsageSummary summary = service.summary(ACME, 24).block(PATIENCE);

            // Each aggregate arrives as a BigDecimal from sum(); asLong is what keeps that
            // from being a ClassCastException, and these assertions are what prove it.
            assertThat(summary.requests()).isEqualTo(300L);
            assertThat(summary.cacheHits()).isEqualTo(210L);
            assertThat(summary.promptTokens()).isEqualTo(180_000L);
            assertThat(summary.completionTokens()).isEqualTo(120_000L);
            assertThat(summary.upstreamTokens()).isEqualTo(90_000L);
            assertThat(summary.totalTokens()).isEqualTo(300_000L);
            assertThat(summary.tokensSaved()).isEqualTo(210_000L);
            assertThat(summary.hitRatio()).isEqualTo(0.7d);
        }

        @Test
        @DisplayName("excludes rows older than the window")
        void excludesOlderRows() {
            insert(ACME, 1, 100, 70, 10, 10, 10);
            // 48 hours back, outside a 24-hour window. Without the period_hour predicate
            // the dashboard would show all history under a "last 24 hours" label.
            insert(ACME, 48, 999, 999, 999, 999, 999);

            assertThat(service.summary(ACME, 24).block(PATIENCE).requests()).isEqualTo(100L);
        }

        @Test
        @DisplayName("a wider window picks up the older rows")
        void widerWindowIncludesThem() {
            insert(ACME, 1, 100, 70, 10, 10, 10);
            insert(ACME, 48, 900, 0, 10, 10, 10);

            assertThat(service.summary(ACME, 72).block(PATIENCE).requests())
                    .isEqualTo(1_000L);
        }

        @Test
        @DisplayName("one tenant never sees another's usage")
        void tenantsAreIsolated() {
            insert(ACME, 1, 100, 70, 10, 10, 10);
            insert(GLOBEX, 1, 555, 555, 999, 999, 999);

            // A usage endpoint that leaked across tenants would disclose another
            // customer's traffic volume and spend.
            assertThat(service.summary(ACME, 24).block(PATIENCE).requests()).isEqualTo(100L);
            assertThat(service.summary(GLOBEX, 24).block(PATIENCE).requests())
                    .isEqualTo(555L);
        }

        @Test
        @DisplayName("a tenant with no rows gets zeros, not an error and not nulls")
        void emptyWindowIsZeros() {
            UsageSummary summary = service.summary(ACME, 24).block(PATIENCE);

            // A new tenant opening the dashboard before their first request is the common
            // case, not an exception. coalesce() in the SQL is what makes the sums zero
            // rather than null; without it every field would be null and asLong would
            // quietly return 0 anyway - so this asserts the window bounds too, which
            // only the defaultIfEmpty path can supply.
            assertThat(summary.requests()).isZero();
            assertThat(summary.totalTokens()).isZero();
            assertThat(summary.hitRatio()).isZero();
            assertThat(summary.to()).isEqualTo(NOW);
            assertThat(summary.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        }

        @Test
        @DisplayName("the window is reported from the injected clock")
        void windowUsesTheInjectedClock() {
            insert(ACME, 1, 1, 0, 0, 0, 0);

            UsageSummary summary = service.summary(ACME, 6).block(PATIENCE);

            assertThat(summary.to()).isEqualTo(NOW);
            assertThat(summary.from()).isEqualTo(NOW.minus(Duration.ofHours(6)));
        }
    }

    // ------------------------------------------------------------------
    // Window clamping
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("window clamping")
    class Clamping {

        @Test
        @DisplayName("a window below 1 clamps to 1 hour")
        void clampsLow() {
            insert(ACME, 0, 5, 0, 0, 0, 0);

            // Clamped rather than rejected: the UI should not have to know the bounds.
            assertThat(service.summary(ACME, 0).block(PATIENCE).from())
                    .isEqualTo(NOW.minus(Duration.ofHours(1)));
            assertThat(service.summary(ACME, -99).block(PATIENCE).from())
                    .isEqualTo(NOW.minus(Duration.ofHours(1)));
        }

        @Test
        @DisplayName("an absurd window clamps to 90 days, not a full table scan")
        void clampsHigh() {
            insert(ACME, 1, 5, 0, 0, 0, 0);

            // The parameter is caller-controlled, so without the clamp `?hours=1000000000`
            // is a denial-of-service anyone can issue with a query string.
            assertThat(service.summary(ACME, Integer.MAX_VALUE).block(PATIENCE).from())
                    .isEqualTo(NOW.minus(Duration.ofHours(SqlUsageQueryService.MAX_HOURS)));
        }
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("series")
    class Series {

        @Test
        @DisplayName("returns points in chronological order, whatever the insert order")
        void ordered() {
            // Inserted newest-first on purpose: Postgres has no inherent row order, so
            // without ORDER BY the chart would plot points in whatever order the heap
            // happened to yield - which usually looks right until it does not.
            insert(ACME, 1, 300, 0, 0, 0, 0);
            insert(ACME, 3, 100, 0, 0, 0, 0);
            insert(ACME, 2, 200, 0, 0, 0, 0);

            List<UsagePoint> points = service.series(ACME, 24).block(PATIENCE);

            assertThat(points).hasSize(3);
            assertThat(points).extracting(UsagePoint::requests)
                    .containsExactly(100L, 200L, 300L);
            assertThat(points.get(0).hour()).isBefore(points.get(1).hour());
            assertThat(points.get(1).hour()).isBefore(points.get(2).hour());
        }

        @Test
        @DisplayName("carries every per-point figure the chart draws")
        void pointFields() {
            insert(ACME, 1, 100, 70, 60_000, 40_000, 30_000);

            UsagePoint point = service.series(ACME, 24).block(PATIENCE).get(0);

            assertThat(point.requests()).isEqualTo(100L);
            assertThat(point.cacheHits()).isEqualTo(70L);
            assertThat(point.promptTokens()).isEqualTo(60_000L);
            assertThat(point.completionTokens()).isEqualTo(40_000L);
            assertThat(point.upstreamTokens()).isEqualTo(30_000L);
            assertThat(point.hour()).isEqualTo(NOW.minus(Duration.ofHours(1)));
        }

        @Test
        @DisplayName("respects the window and the tenant")
        void windowAndTenant() {
            insert(ACME, 1, 1, 0, 0, 0, 0);
            insert(ACME, 48, 1, 0, 0, 0, 0);
            insert(GLOBEX, 1, 1, 0, 0, 0, 0);

            assertThat(service.series(ACME, 24).block(PATIENCE)).hasSize(1);
            assertThat(service.series(ACME, 72).block(PATIENCE)).hasSize(2);
            assertThat(service.series(GLOBEX, 24).block(PATIENCE)).hasSize(1);
        }

        @Test
        @DisplayName("no rows is an empty list, not null and not an error")
        void emptyIsEmptyList() {
            assertThat(service.series(ACME, 24).block(PATIENCE)).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Degradation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("when the database is unreachable")
    class Degraded {

        private SqlUsageQueryService brokenService() {
            DatabaseClient broken = DatabaseClient.create(ConnectionFactories.get(
                    "r2dbc:postgresql://test:test@localhost:1/nothing?connectTimeout=2s"));
            return new SqlUsageQueryService(ShardedDatabase.single(broken), FIXED);
        }

        @Test
        @DisplayName("a failed summary shows an empty window rather than a 500")
        void summaryDegrades() {
            UsageSummary summary = brokenService().summary(ACME, 24).block(PATIENCE);

            // The dashboard is not the request path. A failure here must not produce a 500
            // that reads as "the gateway is down" - it shows an empty window and logs the
            // cause. The window bounds still come back, so the UI can render axes.
            assertThat(summary).isNotNull();
            assertThat(summary.requests()).isZero();
            assertThat(summary.to()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a failed series is an empty list rather than a 500")
        void seriesDegrades() {
            assertThat(brokenService().series(ACME, 24).block(PATIENCE)).isEmpty();
        }
    }
}
