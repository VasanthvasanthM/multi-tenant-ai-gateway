package io.vasanth.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Period keys and expiry.
 *
 * <p>Pure arithmetic, no I/O - and worth testing precisely because of that. The period key
 * is what makes a budget reset at all: get it wrong and either every request writes a
 * different key (so nothing accumulates and no budget is ever reached) or the key never
 * changes (so a budget is spent once and never refills). Both failures are silent.
 */
class BudgetPeriodTest {

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("monthly")
    class Monthly {

        @Test
        @DisplayName("the key is stable across the whole month and changes at the boundary")
        void keyIsStableWithinTheMonth() {
            BudgetPeriod period = BudgetPeriod.MONTHLY;

            String first = period.key(Instant.parse("2026-11-01T00:00:00Z"));
            String middle = period.key(Instant.parse("2026-11-15T13:47:11Z"));
            String last = period.key(Instant.parse("2026-11-30T23:59:59Z"));

            // Stability IS the mechanism. If the key varied within the period, every
            // request would increment a fresh counter and no budget would ever be reached.
            assertThat(first).isEqualTo("2026-11").isEqualTo(middle).isEqualTo(last);

            // And it must actually change, or a budget spent once never refills.
            assertThat(period.key(Instant.parse("2026-12-01T00:00:00Z"))).isEqualTo("2026-12");
            assertThat(period.key(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo("2026-10");
        }

        @Test
        @DisplayName("the month is zero-padded, so keys sort chronologically")
        void monthIsZeroPadded() {
            // "2027-1" would sort after "2027-10" in any lexical listing, which makes a
            // KEYS scan or a sorted dump of the keyspace misleading rather than wrong.
            assertThat(BudgetPeriod.MONTHLY.key(Instant.parse("2027-01-09T00:00:00Z")))
                    .isEqualTo("2027-01");
            assertThat(BudgetPeriod.MONTHLY.key(Instant.parse("2027-09-09T00:00:00Z")))
                    .isEqualTo("2027-09");
        }

        @Test
        @DisplayName("the TTL reaches past the end of the month by the grace margin")
        void ttlCoversTheMonthPlusGrace() {
            // Mid-November: 15 days to the boundary, plus one day of grace.
            long ttl = BudgetPeriod.MONTHLY.ttlSeconds(at("2026-11-16T00:00:00Z"));

            assertThat(ttl).isEqualTo(Duration.ofDays(16).toSeconds());
        }

        @Test
        @DisplayName("a December counter expires into the next YEAR, not month 13")
        void crossesTheYearBoundary() {
            // The off-by-one here would be computing month+1 arithmetically. LocalDate
            // handles it, and this pins that it is actually used.
            long ttl = BudgetPeriod.MONTHLY.ttlSeconds(at("2026-12-31T00:00:00Z"));

            assertThat(ttl).isEqualTo(Duration.ofDays(2).toSeconds());
        }

        @Test
        @DisplayName("February in a leap year is 29 days")
        void handlesLeapFebruary() {
            // 2028 is a leap year. Hand-rolled month arithmetic gets this wrong; the
            // calendar classes do not.
            long ttl = BudgetPeriod.MONTHLY.ttlSeconds(at("2028-02-01T00:00:00Z"));

            assertThat(ttl).isEqualTo(Duration.ofDays(30).toSeconds());
        }
    }

    @Nested
    @DisplayName("daily")
    class Daily {

        @Test
        @DisplayName("the key is the UTC calendar date")
        void keyIsTheDate() {
            assertThat(BudgetPeriod.DAILY.key(Instant.parse("2026-11-08T00:00:00Z")))
                    .isEqualTo("2026-11-08");
            assertThat(BudgetPeriod.DAILY.key(Instant.parse("2026-11-08T23:59:59Z")))
                    .isEqualTo("2026-11-08");
            assertThat(BudgetPeriod.DAILY.key(Instant.parse("2026-11-09T00:00:00Z")))
                    .isEqualTo("2026-11-09");
        }

        @Test
        @DisplayName("boundaries are UTC, not the machine's zone")
        void boundariesAreUtc() {
            // 23:30 UTC is already the next day in much of Asia. If this used the system
            // zone, the same instant would land in different periods on different pods -
            // so a tenant's spend would split across two counters and neither would reach
            // the limit. The services all run in UTC; so does this.
            assertThat(BudgetPeriod.DAILY.key(Instant.parse("2026-11-08T23:30:00Z")))
                    .isEqualTo("2026-11-08");
            assertThat(BudgetPeriod.DAILY.key(Instant.parse("2026-11-09T00:30:00Z")))
                    .isEqualTo("2026-11-09");
        }

        @Test
        @DisplayName("the TTL reaches past midnight by the grace margin")
        void ttlCoversTheDayPlusGrace() {
            long ttl = BudgetPeriod.DAILY.ttlSeconds(at("2026-11-08T00:00:00Z"));

            assertThat(ttl).isEqualTo(Duration.ofDays(2).toSeconds());
        }
    }

    @Test
    @DisplayName("the TTL is never zero or negative, whatever the instant")
    void ttlIsAlwaysPositive() {
        // Redis treats a non-positive EXPIRE as DEL. Passing one would delete the counter
        // that was just incremented and silently reset the tenant's spend to zero, which
        // is the worst possible failure for a budget: it never refuses anything again.
        for (String instant : new String[] {
                "2026-11-30T23:59:59.999Z",
                "2026-12-31T23:59:59.999Z",
                "2026-11-08T23:59:59.999Z",
                "1970-01-01T00:00:00Z"}) {
            Clock clock = at(instant);
            assertThat(BudgetPeriod.MONTHLY.ttlSeconds(clock))
                    .as("monthly at %s", instant)
                    .isPositive();
            assertThat(BudgetPeriod.DAILY.ttlSeconds(clock))
                    .as("daily at %s", instant)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("a daily period never outlives a monthly one")
    void dailyIsShorterThanMonthly() {
        Clock clock = at("2026-11-08T12:00:00Z");

        assertThat(BudgetPeriod.DAILY.ttlSeconds(clock))
                .isLessThan(BudgetPeriod.MONTHLY.ttlSeconds(clock));
    }
}
