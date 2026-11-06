package io.vasanth.gateway.web;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.vasanth.gateway.auth.ApiKeyAuthFilter;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.pipeline.CompletionPipeline;
import io.vasanth.gateway.web.dto.ChatCompletionRequestDto;
import io.vasanth.gateway.web.dto.ChatCompletionResponseDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Handlers for the completions endpoint.
 *
 * <p>Functional endpoints rather than annotated controllers. The routing table is then an
 * ordinary value that can be read top to bottom and unit-tested without starting a servlet
 * context, and filter ordering around it is explicit rather than inferred from annotations.
 */
@Component
public class CompletionHandler {

    /** Terminator the OpenAI streaming protocol expects. Clients stop reading on it. */
    private static final String STREAM_DONE = "[DONE]";

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final CompletionPipeline pipeline;
    private final Validator validator;
    private final Clock clock;

    public CompletionHandler(CompletionPipeline pipeline, Validator validator, Clock clock) {
        this.pipeline = pipeline;
        this.validator = validator;
        this.clock = clock;
    }

    /** {@code POST /v1/chat/completions} - buffered or streaming depending on the body. */
    public Mono<ServerResponse> completions(ServerRequest request) {
        TenantId tenant = ApiKeyAuthFilter.tenantOf(request.exchange());
        String idempotencyKey = request.headers().firstHeader(IDEMPOTENCY_HEADER);

        return request.bodyToMono(ChatCompletionRequestDto.class)
                // An absent or empty body deserialises to null rather than failing, so it
                // has to be rejected explicitly or it becomes a confusing NPE downstream.
                .switchIfEmpty(Mono.error(new GatewayException(
                        new GatewayError.InvalidRequest("request body is required"))))
                .doOnNext(this::validateOrThrow)
                .flatMap(dto -> dto.wantsStream()
                        ? streamResponse(dto.toDomain(tenant, idempotencyKey))
                        : bufferedResponse(dto.toDomain(tenant, idempotencyKey)));
    }

    private Mono<ServerResponse> bufferedResponse(CompletionRequest domainRequest) {
        return pipeline.execute(domainRequest)
                .flatMap(response -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        // Surfaced as a header as well as in the body so that a proxy, a
                        // CDN or a curl -I can see cache behaviour without parsing JSON.
                        .header("X-Cache-Tier", response.servedBy().label())
                        .header("X-Provider", response.provider().value())
                        .bodyValue(ChatCompletionResponseDto.from(
                                response, Instant.now(clock).getEpochSecond())));
    }

    /**
     * Server-sent events.
     *
     * <p>{@code TEXT_EVENT_STREAM} alone is not enough to make streaming work end to end.
     * Nginx buffers proxied responses by default, which holds the whole stream until the
     * handler completes and silently converts streaming into a slow buffered response. The
     * {@code X-Accel-Buffering: no} header disables that per-response, and the ingress
     * configuration sets {@code proxy_buffering off} as well - both are needed, because the
     * header only helps if the proxy honours it.
     */
    private Mono<ServerResponse> streamResponse(CompletionRequest domainRequest) {
        Flux<ServerSentEvent<String>> events = pipeline.stream(domainRequest)
                .map(delta -> ServerSentEvent.builder(delta).build())
                .concatWith(Mono.just(ServerSentEvent.<String>builder(STREAM_DONE).build()));

        return ServerResponse.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(events, ServerSentEvent.class);
    }

    private void validateOrThrow(ChatCompletionRequestDto dto) {
        Set<ConstraintViolation<ChatCompletionRequestDto>> violations = validator.validate(dto);
        if (violations.isEmpty()) {
            return;
        }

        // Sorted so the message is deterministic. Without this the order varies between
        // runs, which makes the failure impossible to assert on in a test and noisy in logs.
        String detail = violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .sorted()
                .collect(Collectors.joining("; "));

        throw new GatewayException(new GatewayError.InvalidRequest(detail));
    }
}
