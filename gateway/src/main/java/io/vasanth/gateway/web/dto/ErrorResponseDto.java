package io.vasanth.gateway.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.vasanth.gateway.common.error.GatewayError;

/**
 * Error body, in the OpenAI error envelope so existing SDKs surface it correctly.
 *
 * <p>The {@code retryable} flag is a gateway addition. Clients otherwise have to infer
 * retry-safety from the status code, and that inference is wrong in the case that matters
 * most here: 402 {@code budget_exhausted} and 429 {@code rate_limited} are both "you have
 * hit a limit", but retrying the first is pointless until the budget is topped up. Making
 * it explicit stops clients retry-storming against a wall.
 */
public record ErrorResponseDto(@JsonInclude(JsonInclude.Include.NON_NULL) Error error) {

    public record Error(String message, String type, String code, boolean retryable) {
    }

    public static ErrorResponseDto from(GatewayError error) {
        return new ErrorResponseDto(new Error(
                error.message(),
                classify(error.httpStatus()),
                error.code(),
                error.retryable()));
    }

    /** OpenAI's coarse error families, derived from the status. */
    private static String classify(int httpStatus) {
        if (httpStatus == 401 || httpStatus == 403) {
            return "authentication_error";
        }
        if (httpStatus == 429) {
            return "rate_limit_error";
        }
        if (httpStatus >= 500) {
            return "server_error";
        }
        return "invalid_request_error";
    }
}
