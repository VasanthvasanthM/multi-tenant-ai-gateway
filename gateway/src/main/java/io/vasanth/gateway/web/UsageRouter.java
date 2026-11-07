package io.vasanth.gateway.web;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes for the tenant usage API the dashboard reads.
 *
 * <p>Under {@code /v1/usage} rather than {@code /admin} or {@code /api}: these are
 * tenant-facing reads authenticated by the same API key as the completions endpoint, so
 * they belong on the same versioned surface. An {@code /admin} prefix would imply the
 * operator credentials the control plane uses, which is a different trust level
 * entirely.
 */
@Configuration(proxyBeanMethods = false)
// UsagePricing lives in the `usage` package, outside the ConfigurationPropertiesScan
// on GatewayApplication - which is scoped to the `config` package. Registered
// explicitly here, the same way ShardProperties is, rather than moved: it belongs with
// the code that uses it, and a scan that silently misses a properties record binds
// every field to its default with nothing reporting it.
@EnableConfigurationProperties(io.vasanth.gateway.usage.UsagePricing.class)
public class UsageRouter {

    @Bean
    public RouterFunction<ServerResponse> usageRoutes(UsageHandler handler) {
        return route(
                GET("/v1/usage/summary").and(RequestPredicates.accept(MediaType.APPLICATION_JSON)),
                handler::summary)
                .andRoute(
                        GET("/v1/usage/series")
                                .and(RequestPredicates.accept(MediaType.APPLICATION_JSON)),
                        handler::series);
    }
}
