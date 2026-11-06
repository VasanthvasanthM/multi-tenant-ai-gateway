package io.vasanth.gateway.config;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.vasanth.gateway.common.llm.LlmProvider;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.provider.MockLlmProvider;
import io.vasanth.gateway.provider.OllamaProvider;
import io.vasanth.gateway.provider.OpenAiCompatibleProvider;
import io.vasanth.gateway.provider.ResilientLlmProvider;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Creates every provider and wraps each one in its own resilience decorators.
 *
 * <p>Built centrally rather than with {@code @Component} on each provider, for one
 * reason: every provider must be wrapped, and a component-scanned provider would quietly
 * bypass the circuit breaker. Making construction explicit means forgetting to wrap one is
 * visible here rather than invisible at runtime.
 *
 * <p>Each provider gets its <b>own</b> breaker, bulkhead and retry instance, keyed by
 * provider id. Sharing them would mean one vendor's outage opening the circuit for all of
 * them, which defeats the point of having a fallback chain.
 */
@Configuration(proxyBeanMethods = false)
public class ProvidersConfig {

    private static final Logger log = LoggerFactory.getLogger(ProvidersConfig.class);

    /**
     * The primary deterministic mock.
     *
     * <p>Always registered. It is the default provider, the benchmark target, and the
     * fallback of last resort for the resilience demos - none of which should depend on a
     * vendor being reachable.
     */
    @Bean
    public LlmProvider mockProvider(
            MockProviderProperties properties,
            Clock clock,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters,
            RetryRegistry retries) {

        return wrap(
                new MockLlmProvider(ProviderId.of("mock"), properties, clock),
                breakers, bulkheads, timeLimiters, retries);
    }

    /**
     * A second mock that never fails, for demonstrating the fallback chain.
     *
     * <p>Set {@code gateway.providers.mock.failure-rate=1.0} and list this as the fallback:
     * the primary's breaker opens, traffic moves here, and the whole sequence is
     * observable with no API key, no network, and no waiting for a real vendor to break at
     * a convenient moment.
     */
    @Bean
    @ConditionalOnProperty(name = "gateway.providers.mock-secondary.enabled", havingValue = "true")
    public LlmProvider mockSecondaryProvider(
            Clock clock,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters,
            RetryRegistry retries) {

        log.info("mock-secondary provider enabled (fallback demonstration)");
        return wrap(
                new MockLlmProvider(
                        ProviderId.of("mock-secondary"),
                        MockProviderProperties.instant(),
                        clock),
                breakers, bulkheads, timeLimiters, retries);
    }

    @Bean
    @ConditionalOnProperty(
            name = "gateway.providers.openai-compatible.enabled", havingValue = "true")
    public LlmProvider openAiCompatibleProvider(
            ProviderProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters,
            RetryRegistry retries) {

        ProviderProperties.OpenAiCompatible config = properties.openaiCompatible();
        log.info("openai-compatible provider enabled, base-url={}", config.baseUrl());

        WebClient client = buildWebClient(
                config.baseUrl(),
                config.connectTimeout(),
                config.responseTimeout(),
                config.maxConnections());

        return wrap(
                new OpenAiCompatibleProvider(client, config, objectMapper, clock),
                breakers, bulkheads, timeLimiters, retries);
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.providers.ollama.enabled", havingValue = "true")
    public LlmProvider ollamaProvider(
            ProviderProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters,
            RetryRegistry retries) {

        ProviderProperties.Ollama config = properties.ollama();
        log.warn(
                "ollama provider enabled at {}. On a CPU-only machine this yields single-digit "
                        + "tokens/sec and will distort any benchmark it participates in.",
                config.baseUrl());

        WebClient client = buildWebClient(
                config.baseUrl(), Duration.ofSeconds(5), config.responseTimeout(), 16);

        return wrap(
                new OllamaProvider(client, config, objectMapper, clock),
                breakers, bulkheads, timeLimiters, retries);
    }

    private static LlmProvider wrap(
            LlmProvider delegate,
            CircuitBreakerRegistry breakers,
            BulkheadRegistry bulkheads,
            TimeLimiterRegistry timeLimiters,
            RetryRegistry retries) {

        String name = delegate.id().value();
        // Keyed by provider id so each upstream has independent state. A shared breaker
        // would let one vendor's outage shut off the fallback that exists to rescue it.
        return new ResilientLlmProvider(
                delegate,
                breakers.circuitBreaker(name),
                bulkheads.bulkhead(name),
                timeLimiters.timeLimiter(name),
                retries.retry(name));
    }

    private static WebClient buildWebClient(
            String baseUrl, Duration connectTimeout, Duration responseTimeout, int maxConnections) {

        ConnectionProvider connections = ConnectionProvider.builder("llm-upstream")
                .maxConnections(maxConnections)
                // Bounded queue, not the default unbounded one. Without this, requests
                // waiting for a connection accumulate invisibly and the time limiter fires
                // on calls that never left the process - which looks like an upstream
                // problem and is not.
                .pendingAcquireMaxCount(maxConnections * 2)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                // Recycle connections periodically. Long-lived pooled connections to a
                // load-balanced vendor endpoint pin traffic to whichever backend was
                // chosen at connect time, and stop following their DNS changes.
                .maxIdleTime(Duration.ofSeconds(60))
                .maxLifeTime(Duration.ofMinutes(10))
                .evictInBackground(Duration.ofSeconds(30))
                .build();

        HttpClient httpClient = HttpClient.create(connections)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(responseTimeout)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(responseTimeout.toSeconds(),
                                java.util.concurrent.TimeUnit.SECONDS)))
                .compress(true);

        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // LLM responses are large. The 256 KB default would truncate a long
                // completion and surface as a confusing decode error rather than an
                // obvious size problem.
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }
}
