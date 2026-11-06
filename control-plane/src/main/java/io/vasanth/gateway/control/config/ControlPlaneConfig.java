package io.vasanth.gateway.control.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import io.r2dbc.spi.ConnectionFactory;
import io.vasanth.gateway.kafka.KafkaFactory;
import reactor.kafka.sender.KafkaSender;

/** Wiring for the control plane. */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class ControlPlaneConfig {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ReactiveTransactionManager transactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    /**
     * Required by the outbox.
     *
     * <p>The whole pattern depends on the business write and the event row landing in one
     * transaction. Without a transaction manager they would be two independent writes and
     * the outbox would guarantee nothing at all.
     */
    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager manager) {
        return TransactionalOperator.create(manager);
    }

    @Bean
    public KafkaSender<String, String> kafkaSender(
            @Value("${gateway.kafka.bootstrap-servers}") String bootstrapServers) {
        return KafkaFactory.sender(bootstrapServers);
    }

    /**
     * Security for the admin API.
     *
     * <p>Admin endpoints carry far more authority than the data plane - creating tenants,
     * issuing keys, raising quotas - so they get HTTP Basic over a separate port rather
     * than the tenant API-key scheme. The gateway's keys must not grant administrative
     * access.
     *
     * <p>CSRF is disabled because this is a token-authenticated API with no browser
     * session and no cookies: there is no ambient credential for an attacker to ride on,
     * which is the only thing CSRF protection defends against.
     */
    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Probes and scrapes cannot carry a credential. These are bound
                        // to an internal listener and restricted by NetworkPolicy in the
                        // Helm chart rather than by authentication.
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
                        .anyExchange().authenticated())
                .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}
