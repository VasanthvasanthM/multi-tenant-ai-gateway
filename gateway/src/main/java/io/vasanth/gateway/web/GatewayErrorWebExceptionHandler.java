package io.vasanth.gateway.web;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.web.dto.ErrorResponseDto;
import reactor.core.publisher.Mono;

/**
 * Translates every failure into the error envelope.
 *
 * <p>Ordered ahead of Spring's default handler so that {@link GatewayException} is rendered
 * from its typed {@link GatewayError} rather than flattened into a generic 500.
 *
 * <h2>Unknown exceptions never reach the client</h2>
 *
 * <p>Anything that is not a {@code GatewayException} becomes a generic 500 with a fixed
 * message. The original is logged with its stack trace. This is not defensive
 * over-engineering: exception messages routinely contain SQL fragments, file paths,
 * library versions and occasionally connection strings, and all of those are
 * reconnaissance for an attacker.
 */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public GatewayErrorWebExceptionHandler(
            ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable throwable) {
        GatewayError error = toGatewayError(throwable);

        Counter.builder("gateway.errors")
                .tag("code", error.code())
                .tag("status", Integer.toString(error.httpStatus()))
                .register(meterRegistry)
                .increment();

        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // Headers already flushed - typically a failure partway through an SSE stream.
            // Nothing can be rewritten at this point, so the connection is simply closed
            // and the client sees a truncated stream.
            return Mono.error(throwable);
        }

        response.setStatusCode(HttpStatus.valueOf(error.httpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        if (error instanceof GatewayError.RateLimited rateLimited) {
            // Seconds, per RFC 9110. The rounding lives on the error type so that this
            // header and the message in the body can never disagree.
            response.getHeaders().set(
                    HttpHeaders.RETRY_AFTER, Long.toString(rateLimited.retryAfterSeconds()));
        }

        return response.writeWith(Mono.just(encode(response, error)));
    }

    private GatewayError toGatewayError(Throwable throwable) {
        if (throwable instanceof GatewayException gatewayException) {
            return gatewayException.error();
        }

        if (throwable instanceof ResponseStatusException statusException) {
            // Raised by the framework for malformed JSON, unsupported media types and
            // unmatched routes. The reason is framework-generated, not internal state, so
            // it is safe to echo.
            HttpStatus status = HttpStatus.resolve(statusException.getStatusCode().value());
            String reason = statusException.getReason() != null
                    ? statusException.getReason()
                    : "request could not be processed";
            if (status != null && status.is4xxClientError()) {
                return new GatewayError.InvalidRequest(reason);
            }
        }

        if (throwable instanceof IllegalArgumentException illegalArgument) {
            // Thrown by the domain value types - an invalid tenant id, model id or role.
            // These messages describe the caller's input, not our internals.
            return new GatewayError.InvalidRequest(illegalArgument.getMessage());
        }

        log.error("unhandled exception, returning generic 500", throwable);
        return GatewayError.Internal.generic();
    }

    private DataBuffer encode(ServerHttpResponse response, GatewayError error) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(ErrorResponseDto.from(error));
            return response.bufferFactory().wrap(body);
        } catch (JsonProcessingException e) {
            // Serialising a tiny fixed-shape record cannot realistically fail, but if it
            // did, returning a hand-built body is better than recursing into this handler.
            log.error("failed to serialise error response", e);
            return response.bufferFactory().wrap(
                    "{\"error\":{\"message\":\"internal error\",\"code\":\"internal_error\"}}"
                            .getBytes(StandardCharsets.UTF_8));
        }
    }
}
