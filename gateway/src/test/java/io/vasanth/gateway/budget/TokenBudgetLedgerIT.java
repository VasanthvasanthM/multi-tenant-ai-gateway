package io.vasanth.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.vasanth.gateway.common.tenant.TenantId;
import reactor.test.StepVerifier;

/**
 * The budget ledger against real Redis, including the Lua script.
 *
 * <h2>Why this cannot be a unit test</h2>
 *
 * <p>The two things most likely to be wrong here are both inside
 * {@code budget_record.lua}, and neither is reachable through a mocked
 * {@code ReactiveStringRedisTemplate}:
 *
 * <ol>
 *   <li><b>INCRBY and EXPIRE must be atomic.</b> A mock cannot show that an interrupted
 *       pair leaves a counter with no expiry, because a mock has no expiry.
 *   <li><b>The TTL is applied only when absent.</b> Re-issuing EXPIRE on every request
 *       would slide the window forward forever, so an actively used counter would never
 *       expire and a tenant's budget would never reset. Verifying that needs a server that
 *       actually tracks TTLs.
 * </ol>
 *
 * <p>This is also the first integration test in the repository that exercises Redis at all.
 * Tier 1 and the rate limiter are unit-tested over mocks, which means the atomic token
 * bucket script has never run against a real server in an automated test either - a gap
 * recorded in PROGRESS.md, and this establishes the pattern for closing it.
 */
@Testcontainers
class TokenBudgetLedgerIT {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    private static final TenantId ACME = TenantId.of("acme");
    private static final TenantId GLOBEX = TenantId.of("globex");

    private static final Instant NOVEMBER = Instant.parse("2026-11-08T12:00:00Z");
    private static final Clock IN_NOVEMBER = Clock.fixed(NOVEMBER, ZoneOffset.UTC);
    private static final Clock IN_DECEMBER =
            Clock.fixed(Instant.parse("2026-12-08T12:00:00Z"), ZoneOffset.UTC);

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;
    private static RedisScript<Long> script;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getFirstMappedPort()));
        factory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(factory);

        DefaultRedisScript<Long> record = new DefaultRedisScript<>();
        record.setLocation(new ClassPathResource("scripts/budget_record.lua"));
        record.setResultType(Long.class);
        script = record;
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory()
                .getReactiveConnection()
                .serverCommands()
                .flushAll()
                .block(PATIENCE);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static BudgetProperties config(boolean enabled, long defaultLimit) {
        return new BudgetProperties(
                enabled, false, BudgetPeriod.MONTHLY, defaultLimit, true);
    }

    private TokenBudgetLedger ledger(BudgetProperties config, Clock clock) {
        return new TokenBudgetLedger(
                redis, script, new TenantQuotaRegistry(redis, config), config, clock);
    }

    private TokenBudgetLedger ledger(long defaultLimit) {
        return ledger(config(true, defaultLimit), IN_NOVEMBER);
    }

    private void publishLimit(TenantId tenant, String value) {
        redis.opsForValue()
                .set(TenantQuotaRegistry.quotaKey(tenant), value)
                .block(PATIENCE);
    }

    /**
     * Seconds of TTL, where <b>zero means no expiry</b>.
     *
     * <p>Spring Data normalises away the sentinel: Redis's {@code TTL} returns -1 for a key
     * with no expiry and -2 for a missing key, but {@code getExpire} maps both to
     * {@link Duration#ZERO}. So a test cannot assert "negative" here however natural that
     * reads - which cost one failing assertion to discover.
     *
     * <p>Worth knowing because {@code budget_record.lua} tests {@code TTL < 0} against the
     * <em>raw</em> value, so the script and this helper see genuinely different things for
     * the same key. The script is right; this is the lossy view.
     */
    private long ttlSecondsOf(String key) {
        return redis.getExpire(key).block(PATIENCE).toSeconds();
    }

    private String counterKey(TenantId tenant) {
        return ledger(1_000L).counterKey(tenant, NOVEMBER);
    }

    // ------------------------------------------------------------------
    // The Lua script
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("a new counter is created WITH an expiry, in one atomic step")
        void newCounterGetsAnExpiry() {
            TokenBudgetLedger ledger = ledger(1_000L);

            assertThat(ledger.record(ACME, 30L).block(PATIENCE)).isEqualTo(30L);

            // The whole reason the script exists. INCRBY on a missing key creates it with
            // NO TTL, so a non-atomic INCRBY-then-EXPIRE that died in between would leave
            // a permanent key - and because the key is period-stamped, a new one every
            // period, for every tenant, forever. The gateway would keep working perfectly
            // while Redis filled up.
            assertThat(ttlSecondsOf(counterKey(ACME))).isPositive();
        }

        @Test
        @DisplayName("repeated records accumulate")
        void recordsAccumulate() {
            TokenBudgetLedger ledger = ledger(1_000L);

            assertThat(ledger.record(ACME, 30L).block(PATIENCE)).isEqualTo(30L);
            assertThat(ledger.record(ACME, 12L).block(PATIENCE)).isEqualTo(42L);
            assertThat(ledger.record(ACME, 1L).block(PATIENCE)).isEqualTo(43L);
        }

        @Test
        @DisplayName("the TTL is NOT slid forward by later records")
        void ttlIsNotRefreshed() {
            TokenBudgetLedger ledger = ledger(1_000L);
            String key = counterKey(ACME);

            ledger.record(ACME, 10L).block(PATIENCE);

            // Shorten it behind the ledger's back, then record again. If the script
            // re-issued EXPIRE unconditionally, the TTL would jump back up to the full
            // period - and a continuously busy tenant's counter would then never expire,
            // so its budget would never reset. The bug only shows up a month later, on a
            // tenant that never stopped sending traffic.
            redis.expire(key, Duration.ofSeconds(120)).block(PATIENCE);

            ledger.record(ACME, 5L).block(PATIENCE);

            assertThat(ttlSecondsOf(key)).isLessThanOrEqualTo(120L);
            assertThat(redis.opsForValue().get(key).block(PATIENCE)).isEqualTo("15");
        }

        @Test
        @DisplayName("a counter that somehow lost its expiry gets one back")
        void missingTtlIsRestored() {
            TokenBudgetLedger ledger = ledger(1_000L);
            String key = counterKey(ACME);

            ledger.record(ACME, 10L).block(PATIENCE);
            redis.persist(key).block(PATIENCE);
            // Zero, not negative - see ttlSecondsOf. PERSIST removed the expiry.
            assertThat(ttlSecondsOf(key)).isZero();

            ledger.record(ACME, 5L).block(PATIENCE);

            // Self-healing, which is why the script tests TTL rather than key existence:
            // a key left without an expiry by any means is repaired on the next write.
            assertThat(ttlSecondsOf(key)).isPositive();
        }

        @Test
        @DisplayName("usage over the limit is still recorded")
        void recordsPastTheLimit() {
            TokenBudgetLedger ledger = ledger(100L);

            ledger.record(ACME, 90L).block(PATIENCE);
            assertThat(ledger.record(ACME, 50L).block(PATIENCE)).isEqualTo(140L);

            // Refusing to record over-limit usage would make this counter disagree with
            // usage_ledger in Postgres, which is what the tenant is billed from. An
            // over-limit tenant must read as over-limit, not as exactly at it.
            assertThat(ledger.check(ACME).block(PATIENCE).usedTokens()).isEqualTo(140L);
        }

        @Test
        @DisplayName("zero and negative token counts write nothing")
        void nonPositiveTokensAreIgnored() {
            TokenBudgetLedger ledger = ledger(1_000L);

            StepVerifier.create(ledger.record(ACME, 0L)).verifyComplete();
            StepVerifier.create(ledger.record(ACME, -5L)).verifyComplete();

            // A fully-cached or empty response can legitimately cost nothing. Filtering
            // here also keeps the script's non-negative precondition unreachable, so the
            // error_reply branch is defence rather than a live path.
            assertThat(redis.hasKey(counterKey(ACME)).block(PATIENCE)).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // The decision boundary
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("checking")
    class Checking {

        @Test
        @DisplayName("an untouched tenant is allowed with zero used")
        void freshTenantIsAllowed() {
            BudgetDecision decision = ledger(1_000L).check(ACME).block(PATIENCE);

            assertThat(decision.allowed()).isTrue();
            assertThat(decision.usedTokens()).isZero();
            assertThat(decision.limitTokens()).isEqualTo(1_000L);
            assertThat(decision.degraded()).isFalse();
        }

        @Test
        @DisplayName("below the limit is allowed")
        void belowLimitIsAllowed() {
            TokenBudgetLedger ledger = ledger(1_000L);
            ledger.record(ACME, 999L).block(PATIENCE);

            assertThat(ledger.check(ACME).block(PATIENCE).allowed()).isTrue();
        }

        @Test
        @DisplayName("exactly at the limit is EXHAUSTED: the boundary is >=, not >")
        void exactlyAtTheLimitIsExhausted() {
            TokenBudgetLedger ledger = ledger(1_000L);
            ledger.record(ACME, 1_000L).block(PATIENCE);

            // A tenant that has spent precisely its limit has nothing left. Using > would
            // grant one extra request at every limit - an off-by-one that only ever
            // surfaces as a billing dispute.
            BudgetDecision decision = ledger.check(ACME).block(PATIENCE);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.usedTokens()).isEqualTo(1_000L);
            assertThat(decision.limitTokens()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("over the limit is exhausted")
        void overLimitIsExhausted() {
            TokenBudgetLedger ledger = ledger(1_000L);
            ledger.record(ACME, 1_001L).block(PATIENCE);

            assertThat(ledger.check(ACME).block(PATIENCE).allowed()).isFalse();
        }

        @Test
        @DisplayName("a zero limit means unlimited, not instantly exhausted")
        void zeroLimitIsUnlimited() {
            TokenBudgetLedger ledger = ledger(0L);
            ledger.record(ACME, 999_999L).block(PATIENCE);

            // The dangerous misreading: 0 as a literal ceiling would refuse every request
            // the moment budgets were switched on with an unset limit.
            BudgetDecision decision = ledger.check(ACME).block(PATIENCE);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.limitTokens()).isEqualTo(-1L);
        }
    }

    // ------------------------------------------------------------------
    // Isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one tenant's spend does not touch another's")
    void tenantsAreIsolated() {
        TokenBudgetLedger ledger = ledger(1_000L);

        ledger.record(ACME, 1_000L).block(PATIENCE);

        assertThat(ledger.check(ACME).block(PATIENCE).allowed()).isFalse();
        assertThat(ledger.check(GLOBEX).block(PATIENCE).allowed()).isTrue();
        assertThat(ledger.check(GLOBEX).block(PATIENCE).usedTokens()).isZero();
    }

    @Test
    @DisplayName("a new period starts from zero without anything resetting it")
    void periodsAreIsolated() {
        BudgetProperties config = config(true, 1_000L);

        ledger(config, IN_NOVEMBER).record(ACME, 1_000L).block(PATIENCE);
        assertThat(ledger(config, IN_NOVEMBER).check(ACME).block(PATIENCE).allowed())
                .isFalse();

        // Same tenant, same limit, next month. The period is in the key, so the rollover
        // needs no scheduled job and no reset command - the old key simply expires. That
        // is the only reset mechanism that cannot fail halfway or be forgotten.
        BudgetDecision december = ledger(config, IN_DECEMBER).check(ACME).block(PATIENCE);
        assertThat(december.allowed()).isTrue();
        assertThat(december.usedTokens()).isZero();
    }

    @Test
    @DisplayName("the counter key is tenant-hash-tagged and period-stamped")
    void counterKeyShape() {
        // Hash-tagged so the counter shares a Redis Cluster slot with the tenant's cache
        // entries, token bucket and published limit. Without the tag, a future script
        // touching the limit and the counter together would be illegal in cluster mode.
        assertThat(counterKey(ACME)).isEqualTo("{tenant:acme}:budget:2026-11");
    }

    // ------------------------------------------------------------------
    // The published limit
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolving the limit")
    class Limits {

        @Test
        @DisplayName("a published per-tenant limit overrides the default")
        void publishedLimitWins() {
            publishLimit(ACME, "50");
            TokenBudgetLedger ledger = ledger(1_000L);
            ledger.record(ACME, 60L).block(PATIENCE);

            BudgetDecision decision = ledger.check(ACME).block(PATIENCE);
            assertThat(decision.limitTokens()).isEqualTo(50L);
            assertThat(decision.allowed()).isFalse();
        }

        @Test
        @DisplayName("a published limit applies to that tenant only")
        void publishedLimitIsPerTenant() {
            publishLimit(ACME, "50");
            TokenBudgetLedger ledger = ledger(1_000L);

            assertThat(ledger.check(ACME).block(PATIENCE).limitTokens()).isEqualTo(50L);
            assertThat(ledger.check(GLOBEX).block(PATIENCE).limitTokens()).isEqualTo(1_000L);
        }

        @Test
        @DisplayName("a published zero means unlimited for that tenant")
        void publishedZeroIsUnlimited() {
            publishLimit(ACME, "0");
            TokenBudgetLedger ledger = ledger(1_000L);
            ledger.record(ACME, 5_000L).block(PATIENCE);

            assertThat(ledger.check(ACME).block(PATIENCE).allowed()).isTrue();
        }

        @Test
        @DisplayName("a malformed limit falls back to the default rather than failing")
        void malformedLimitFallsBack() {
            publishLimit(ACME, "not-a-number");

            // One bad write in Redis must not take a tenant off the air. It is logged at
            // WARN because a writer is producing garbage and someone has to fix it, but
            // traffic is unaffected.
            assertThat(ledger(1_000L).check(ACME).block(PATIENCE).limitTokens())
                    .isEqualTo(1_000L);
        }

        @Test
        @DisplayName("a negative limit falls back to the default")
        void negativeLimitFallsBack() {
            publishLimit(ACME, "-5");

            // Treating -5 as a ceiling would refuse everything; treating it as unlimited
            // would hand out an unbounded budget on a typo. Neither is acceptable, so it
            // is "no override published".
            assertThat(ledger(1_000L).check(ACME).block(PATIENCE).limitTokens())
                    .isEqualTo(1_000L);
        }

        @Test
        @DisplayName("surrounding whitespace in a published limit is tolerated")
        void whitespaceIsTrimmed() {
            publishLimit(ACME, "  250\n");

            assertThat(ledger(1_000L).check(ACME).block(PATIENCE).limitTokens())
                    .isEqualTo(250L);
        }
    }

    // ------------------------------------------------------------------
    // Disabled
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Degradation - the policy that keeps a Redis blip from being an outage
    // ------------------------------------------------------------------

    /**
     * A ledger pointed at a port with no listener.
     *
     * <p>Deterministic and immediate, and it does not disturb the shared container the way
     * stopping and restarting it would. The command timeout is short on purpose: Lettuce's
     * default is seconds, and a test that waits for it reads as a hang.
     */
    private TokenBudgetLedger unreachableLedger(boolean failOpen) {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", 1));
        dead.setTimeout(500L);
        dead.afterPropertiesSet();
        ReactiveStringRedisTemplate deadTemplate = new ReactiveStringRedisTemplate(dead);

        BudgetProperties config = new BudgetProperties(
                true, false, BudgetPeriod.MONTHLY, 1_000L, failOpen);
        return new TokenBudgetLedger(
                deadTemplate, script, new TenantQuotaRegistry(deadTemplate, config),
                config, IN_NOVEMBER);
    }

    @Test
    @DisplayName("an unreachable store with fail-open allows, and flags itself degraded")
    void failsOpen() {
        BudgetDecision decision = unreachableLedger(true).check(ACME).block(PATIENCE);

        assertThat(decision.allowed()).isTrue();
        // degraded, not merely allowed. An operator must be able to tell "within budget"
        // from "budget not being enforced right now"; the second is an incident, and
        // without the flag it is invisible.
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.usedTokens()).isEqualTo(-1L);
        assertThat(decision.limitTokens()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("an unreachable store with fail-closed refuses, also flagged degraded")
    void failsClosed() {
        BudgetDecision decision = unreachableLedger(false).check(ACME).block(PATIENCE);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.degraded()).isTrue();
        // The figures stay unknown rather than being invented, which is what lets the gate
        // return a retryable 500 instead of a 402 claiming "used -1 of -1".
        assertThat(decision.usedTokens()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("a failed record never fails the request that produced the answer")
    void recordSurvivesAnUnreachableStore() {
        // The tenant already has a valid response and has already paid for it. Losing the
        // chance to count it costs enforcement accuracy, not billing accuracy - the exact
        // figure still reaches usage_ledger through Kafka.
        StepVerifier.create(unreachableLedger(true).record(ACME, 500L))
                .expectComplete()
                .verify(PATIENCE);
    }

    @Test
    @DisplayName("disabled means no Redis traffic and no enforcement")
    void disabledDoesNothing() {
        TokenBudgetLedger ledger = ledger(config(false, 1_000L), IN_NOVEMBER);

        BudgetDecision decision = ledger.check(ACME).block(PATIENCE);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.usedTokens()).isEqualTo(-1L);
        assertThat(decision.limitTokens()).isEqualTo(-1L);

        StepVerifier.create(ledger.record(ACME, 500L)).verifyComplete();

        // Nothing written: a disabled control must not cost a round trip per request, and
        // must not leave state behind that would confuse the counter once enabled.
        assertThat(redis.hasKey(counterKey(ACME)).block(PATIENCE)).isFalse();
    }
}
