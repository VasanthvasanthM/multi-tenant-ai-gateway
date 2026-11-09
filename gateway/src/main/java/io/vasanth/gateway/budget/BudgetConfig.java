package io.vasanth.gateway.budget;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link BudgetProperties}.
 *
 * <p>Needed because {@code GatewayApplication} declares
 * {@code @ConfigurationPropertiesScan(basePackageClasses = GatewayProperties.class)}, which
 * covers {@code io.vasanth.gateway.config} and below - not this package. Without this the
 * properties bind to nothing, every field takes its Java default, and {@code enabled} is
 * false: budgets would silently never be enforced no matter what the YAML said, and the
 * only symptom would be their absence.
 *
 * <p>Same reason {@code ShardConfig} and {@code UsageRouter} carry the annotation. The
 * alternative - widening the scan to {@code io.vasanth.gateway} - would bind every
 * {@code @ConfigurationProperties} in the service including ones that are deliberately
 * wired by hand, so the explicit declaration is the narrower change.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BudgetProperties.class)
public class BudgetConfig {
}
