package io.vasanth.gateway.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Metering service: turns raw usage events into billable state.
 *
 * <p>Separate from the gateway because it scales on a different axis. The gateway scales
 * with request rate and has a 15 ms latency budget; this scales with event volume and may
 * lag by minutes without any user-visible effect. Sharing a process would force both to
 * scale and deploy together, and would put billing aggregation on the same event loop that
 * serves cache hits.
 *
 * <p>It owns billing correctness, which makes it the one consumer that genuinely cannot
 * tolerate duplicate delivery - see the unique constraint in {@code V1__metering.sql}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MeteringApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeteringApplication.class, args);
    }
}
