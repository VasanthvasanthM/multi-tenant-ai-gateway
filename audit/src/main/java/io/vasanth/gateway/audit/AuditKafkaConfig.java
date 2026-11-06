package io.vasanth.gateway.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.event.GatewayEvents;
import io.vasanth.gateway.kafka.DeadLetterPublisher;
import io.vasanth.gateway.kafka.EventConsumerRunner;
import io.vasanth.gateway.kafka.KafkaFactory;
import reactor.kafka.sender.KafkaSender;

/** Kafka wiring for the audit service. */
@Configuration(proxyBeanMethods = false)
public class AuditKafkaConfig {

    @Bean
    public KafkaSender<String, String> kafkaSender(
            @Value("${gateway.kafka.bootstrap-servers}") String bootstrapServers) {
        return KafkaFactory.sender(bootstrapServers);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(KafkaSender<String, String> sender) {
        return new DeadLetterPublisher(sender);
    }

    /**
     * Note the consumer group is distinct from metering's.
     *
     * <p>Two services sharing a group would have the partitions split between them, so
     * each would see roughly half the events - a silent data-loss bug that presents as a
     * throughput mystery rather than an error.
     */
    @Bean
    public EventConsumerRunner<GatewayEvents.AuditEvent> auditConsumer(
            @Value("${gateway.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${gateway.kafka.consumer-group}") String consumerGroup,
            AuditWriter writer,
            DeadLetterPublisher deadLetters,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {

        return new EventConsumerRunner<>(
                "audit",
                KafkaFactory.receiver(bootstrapServers, consumerGroup, GatewayEvents.TOPIC_AUDIT),
                GatewayEvents.AuditEvent.class,
                writer::write,
                GatewayEvents.AuditEvent::schemaVersion,
                deadLetters,
                objectMapper,
                meterRegistry);
    }
}
