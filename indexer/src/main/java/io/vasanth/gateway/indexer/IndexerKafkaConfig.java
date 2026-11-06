package io.vasanth.gateway.indexer;

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

/** Kafka wiring for the indexer service. */
@Configuration(proxyBeanMethods = false)
public class IndexerKafkaConfig {

    @Bean
    public KafkaSender<String, String> kafkaSender(
            @Value("${gateway.kafka.bootstrap-servers}") String bootstrapServers) {
        return KafkaFactory.sender(bootstrapServers);
    }

    @Bean
    public DeadLetterPublisher deadLetterPublisher(KafkaSender<String, String> sender) {
        return new DeadLetterPublisher(sender);
    }

    @Bean
    public EventConsumerRunner<GatewayEvents.CacheWriteEvent> cacheWriteConsumer(
            @Value("${gateway.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${gateway.kafka.consumer-group}") String consumerGroup,
            VectorIndexer indexer,
            DeadLetterPublisher deadLetters,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {

        return new EventConsumerRunner<>(
                "indexer-cache-write",
                KafkaFactory.receiver(
                        bootstrapServers, consumerGroup, GatewayEvents.TOPIC_CACHE_WRITE),
                GatewayEvents.CacheWriteEvent.class,
                indexer::index,
                GatewayEvents.CacheWriteEvent::schemaVersion,
                deadLetters,
                objectMapper,
                meterRegistry);
    }
}
