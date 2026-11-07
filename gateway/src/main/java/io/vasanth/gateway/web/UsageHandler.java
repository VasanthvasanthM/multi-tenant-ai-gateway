package io.vasanth.gateway.web;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import io.vasanth.gateway.auth.ApiKeyAuthFilter;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import io.vasanth.gateway.common.tenant.TenantId;
import io.vasanth.gateway.usage.SqlUsageQueryService;
import io.vasanth.gateway.usage.UsagePricing;
import io.vasanth.gateway.usage.UsageQueryService;
import io.vasanth.gateway.web.dto.UsageSeriesResponse;
import io.vasanth.gateway.web.dto.UsageSummaryResponse;
import reactor.core.publisher.Mono;

/**
 * The tenant's own usage, for the dashboard.
 *
 * <p>The tenant is taken from the AUTHENTICATED key, never from a path or query
 * parameter. A {@code /usage?tenant=globex} style endpoint would make cross-tenant
 * reads a URL away, and the mistake would look like a feature.
 */
@Component
public class UsageHandler {

    private static final int DEFAULT_WINDOW_HOURS = 24;

    /** Digits only. Rejects "1.5", "-1", "undefined" and "NaN" alike. */
    private static final java.util.regex.Pattern DIGITS =
            java.util.regex.Pattern.compile("\\d+");

    private final UsageQueryService usage;
    private final UsagePricing pricing;

    public UsageHandler(UsageQueryService usage, UsagePricing pricing) {
        this.usage = usage;
        this.pricing = pricing;
    }

    public Mono<ServerResponse> summary(ServerRequest request) {
        TenantId tenant = ApiKeyAuthFilter.tenantOf(request.exchange());

        // Mono.fromCallable, not a bare call.
        //
        // windowHours throws on a malformed parameter, and a SYNCHRONOUS throw out of a
        // handler function does not travel the reactive error path - so whether it
        // reaches the GatewayError mapping depends on how the route is wired. Inside
        // fromCallable it is an error signal like any other, and the 400 is produced by
        // the same code path as every other GatewayException.
        return Mono.fromCallable(() -> windowHours(request))
                .flatMap(hours -> usage.summary(tenant, hours)
                        .flatMap(summary -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(UsageSummaryResponse.from(
                                        tenant.value(), hours, summary, pricing))));
    }

    public Mono<ServerResponse> series(ServerRequest request) {
        TenantId tenant = ApiKeyAuthFilter.tenantOf(request.exchange());

        return Mono.fromCallable(() -> windowHours(request))
                .flatMap(hours -> usage.series(tenant, hours)
                        .flatMap(points -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(UsageSeriesResponse.from(
                                        tenant.value(), hours, points))));
    }

    /**
     * Parses {@code ?hours=}, rejecting what cannot be parsed.
     *
     * <p>A non-numeric value is a 400 rather than a silent fall back to the default: a
     * dashboard sending {@code ?hours=undefined} - which is what a JavaScript bug
     * produces - would otherwise render 24 hours of data under a label saying something
     * else, and nobody would notice.
     *
     * <p>Out-of-range values ARE clamped rather than rejected, by the query service.
     * Asking for more history than exists is not an error.
     */
    private static int windowHours(ServerRequest request) {
        String raw = request.queryParam("hours").orElse(null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_WINDOW_HOURS;
        }

        String trimmed = raw.trim();

        // Validated before parsing rather than catching NumberFormatException.
        //
        // Two reasons. PMD objects to throwing a new exception from a catch
        // without the cause, and attaching the cause here would be worse than
        // useless: GatewayException deliberately captures no stack trace, so
        // the cause would be carried only to be discarded. More importantly, a
        // malformed query parameter is an expected input, and expected inputs
        // should not travel as exceptions.
        if (!DIGITS.matcher(trimmed).matches()) {
            throw new GatewayException(new GatewayError.InvalidRequest(
                    "hours must be a positive integer; got '" + raw + "'"));
        }

        // Still bounded before parseLong, because a 30-digit string of digits
        // passes the pattern and overflows an int.
        long hours = trimmed.length() > 9
                ? SqlUsageQueryService.MAX_HOURS
                : Long.parseLong(trimmed);

        if (hours < 1L) {
            throw new GatewayException(new GatewayError.InvalidRequest(
                    "hours must be at least 1; got " + hours));
        }
        return (int) Math.min(hours, SqlUsageQueryService.MAX_HOURS);
    }
}
