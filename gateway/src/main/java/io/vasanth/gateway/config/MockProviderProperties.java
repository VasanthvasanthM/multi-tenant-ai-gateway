package io.vasanth.gateway.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Behaviour of the deterministic mock provider.
 *
 * <p>Defaults model a fast small model: 40 ms to first token, 5 ms between tokens. These are
 * the numbers every published benchmark in this project is measured against, so changing
 * them invalidates comparisons with earlier runs - record the values alongside any result.
 *
 * @param latency delay before a non-streaming response is produced
 * @param interTokenDelay gap between streamed deltas
 * @param failureRate probability in [0,1] that a call fails, for resilience demos
 * @param healthy what {@code isHealthy()} reports, so readiness can be toggled in tests
 */
@Validated
@ConfigurationProperties(prefix = "gateway.providers.mock")
public record MockProviderProperties(
        @NotNull Duration latency,
        @NotNull Duration interTokenDelay,
        @Min(0) @Max(1) double failureRate,
        boolean healthy) {

    public MockProviderProperties {
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative: " + latency);
        }
        if (interTokenDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "interTokenDelay must not be negative: " + interTokenDelay);
        }
    }

    /** Zero-latency, always-succeeding configuration for unit tests. */
    public static MockProviderProperties instant() {
        return new MockProviderProperties(Duration.ZERO, Duration.ZERO, 0.0d, true);
    }
}
