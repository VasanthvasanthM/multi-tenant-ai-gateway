package io.vasanth.gateway.web;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Routes for the dashboard session exchange.
 *
 * <p>Separate from {@code UsageRouter} because these two endpoints are the only ones that
 * take an API key in order to STOP needing one, which is a different job from serving
 * tenant data. Keeping them in their own routing table also makes it obvious at a glance
 * which paths the session cookie may authenticate - the allow-list in
 * {@code ApiKeyAuthFilter} covers {@code /v1/usage}, and deliberately not these.
 */
@Configuration(proxyBeanMethods = false)
public class SessionRouter {

    @Bean
    public RouterFunction<ServerResponse> sessionRoutes(SessionHandler handler) {
        return route(POST("/v1/dashboard/session"), handler::create)
                .andRoute(DELETE("/v1/dashboard/session"), handler::destroy);
    }
}
