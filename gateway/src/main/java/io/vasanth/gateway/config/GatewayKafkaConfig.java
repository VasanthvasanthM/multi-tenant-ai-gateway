package io.vasanth.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vasanth.gateway.kafka.KafkaFactory;
import reactor.kafka.sender.KafkaSender;

/**
 * Kafka producer for the data plane.
 *
 * <p>Conditional on {@code gateway.kafka.enabled}. The gateway must be able to run with
 * no broker at all - that is how the P2/P3 demos and most tests work - and more
 * importantly it must keep serving if Kafka fails in production. Telemetry is the only
 * thing lost, which is exactly the trade documented in {@code GatewayEventPublisher}.
 *
 * <p>There is no receiver here. The data plane publishes and never consumes: a
 * synchronous dependency on a broker would put Kafka's availability directly in front of
 * every request.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "true")
public class GatewayKafkaConfig {

    @Bean
    public KafkaSender<String, String> kafkaSender(
            @Value("${gateway.kafka.bootstrap-servers}") String bootstrapServers) {
        return KafkaFactory.sender(bootstrapServers);
    }
}
