package io.vasanth.gateway.web;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RequestPredicates.contentType;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * The gateway's routing table.
 *
 * <p>Paths mirror the OpenAI API exactly, so pointing an existing client at this gateway is
 * a base-URL change and nothing more.
 */
@Configuration(proxyBeanMethods = false)
public class CompletionRouter {

    @Bean
    public RouterFunction<ServerResponse> completionRoutes(CompletionHandler handler) {
        return route(
                POST("/v1/chat/completions")
                        .and(contentType(MediaType.APPLICATION_JSON))
                        // Accepts both content types from one route rather than splitting
                        // into two: the OpenAI protocol selects streaming via the `stream`
                        // field in the body, not by Accept header, so the handler has to
                        // decide after parsing regardless.
                        .and(accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)),
                handler::completions);
    }
}
