package io.vasanth.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binding and defaults for {@code gateway.budget.*}.
 *
 * <p>Worth its own test because the binding is the part that silently does nothing when it
 * is wrong. {@code BudgetProperties} lives outside the package covered by
 * {@code @ConfigurationPropertiesScan}, so without {@link BudgetConfig} every field would
 * take its Java default - and {@code enabled} would be false. Budgets would never be
 * enforced no matter what the YAML said, and the only symptom would be their absence.
 */
class BudgetPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(BudgetConfig.class);

    @Test
    @DisplayName("BudgetConfig actually binds the properties")
    void configBindsProperties() {
        runner.withPropertyValues(
                        "gateway.budget.enabled=true",
                        "gateway.budget.shadow-mode=false",
                        "gateway.budget.period=DAILY",
                        "gateway.budget.default-limit-tokens=5000",
                        "gateway.budget.fail-open=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(BudgetProperties.class);
                    BudgetProperties properties = context.getBean(BudgetProperties.class);

                    // Each of these would read as a plausible Java default if binding were
                    // broken, which is why all five are asserted rather than one.
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.shadowMode()).isFalse();
                    assertThat(properties.period()).isEqualTo(BudgetPeriod.DAILY);
                    assertThat(properties.defaultLimitTokens()).isEqualTo(5_000L);
                    assertThat(properties.failOpen()).isFalse();
                });
    }

    @Test
    @DisplayName("an omitted period defaults to MONTHLY rather than null")
    void periodDefaultsToMonthly() {
        runner.withPropertyValues("gateway.budget.enabled=true")
                .run(context -> assertThat(
                        context.getBean(BudgetProperties.class).period())
                        .isEqualTo(BudgetPeriod.MONTHLY));
    }

    @Test
    @DisplayName("the period is bound case-insensitively, as Spring does for enums")
    void periodIsCaseInsensitive() {
        runner.withPropertyValues("gateway.budget.period=monthly")
                .run(context -> assertThat(
                        context.getBean(BudgetProperties.class).period())
                        .isEqualTo(BudgetPeriod.MONTHLY));
    }

    @Test
    @DisplayName("an unknown period refuses to start rather than falling back")
    void unknownPeriodFailsFast() {
        // A typo must not silently become MONTHLY. The compact constructor defaults only
        // a MISSING period; an unparseable one is a stated intention that cannot be
        // honoured, and guessing would apply the wrong window to every tenant.
        runner.withPropertyValues("gateway.budget.period=WEEKLY")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a negative default limit refuses to start")
    void negativeLimitFailsFast() {
        // @Min(0) with zero meaning unlimited. A negative has no meaning at all, and
        // whichever way it were coerced - refuse everything, or allow everything - would
        // be a guess about billing.
        runner.withPropertyValues("gateway.budget.default-limit-tokens=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("defaults are safe when nothing is configured at all")
    void defaultsAreSafe() {
        runner.run(context -> {
            BudgetProperties properties = context.getBean(BudgetProperties.class);

            // Off, and that is the point: enabling budgets turns previously successful
            // requests into 402s, and until something publishes per-tenant limits every
            // tenant would get one hard-coded number.
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.period()).isEqualTo(BudgetPeriod.MONTHLY);
        });
    }
}
