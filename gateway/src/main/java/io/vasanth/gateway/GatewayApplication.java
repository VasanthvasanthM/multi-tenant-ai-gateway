package io.vasanth.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import io.vasanth.gateway.config.GatewayProperties;

/**
 * Entry point for the data plane.
 *
 * <p>This process is stateless by design. Everything durable lives in Redis or Postgres, so
 * a replica can be killed mid-request without data loss and new replicas need no warm-up
 * beyond their connection pools. That property is what makes horizontal autoscaling
 * meaningful rather than decorative, and it is asserted directly by the chaos test that
 * deletes a pod under load and expects zero failed requests.
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
