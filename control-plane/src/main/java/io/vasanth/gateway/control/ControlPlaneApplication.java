package io.vasanth.gateway.control;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Control plane: tenants, API keys, quotas and thresholds.
 *
 * <p>Separate from the data plane because the two have opposite characteristics. Admin
 * traffic is low-volume and consistency-critical; gateway traffic is high-volume and
 * latency-critical. Sharing a process would put a slow administrative query on the same
 * event loop that serves 2 ms cache hits, and would force both to scale and deploy
 * together.
 *
 * <p><b>The gateway never calls this service synchronously.</b> Changes propagate through
 * the outbox to Kafka and into the gateway's local Redis view. This service can be down
 * for an hour and existing traffic is unaffected - only the propagation of new or revoked
 * keys stalls.
 *
 * <p>{@code @EnableScheduling} drives the outbox relay.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
