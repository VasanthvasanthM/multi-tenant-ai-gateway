package io.vasanth.gateway.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Audit service: an immutable record of every request.
 *
 * <p>Its own deployable because its retention, access-control and immutability rules are
 * unlike anything else in the system. Audit data is kept for years and must never change;
 * usage data is kept for a billing cycle and is corrected routinely. Housing both in one
 * service means applying the strictest rule of each to all of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
