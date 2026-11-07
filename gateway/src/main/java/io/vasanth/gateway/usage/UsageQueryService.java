package io.vasanth.gateway.usage;

import java.util.List;

import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Reads a tenant's own usage.
 *
 * <p>An interface rather than the SQL class directly, so the handler can be tested
 * without mocking {@code DatabaseClient}. That is not a theoretical concern: mocking
 * DatabaseClient is why {@code Tier2SemanticCache} is the one class still below the
 * coverage gate - its fluent chain needs about a dozen stubs to exercise one query.
 *
 * <p>The SQL implementation is covered by an integration test against real PostgreSQL
 * instead, which is the right split: the handler logic and the query deserve different
 * kinds of test.
 */
public interface UsageQueryService {

    /** Totals over the last {@code hours} hours. */
    Mono<UsageSummary> summary(TenantId tenant, int hours);

    /** Hourly series over the last {@code hours} hours, oldest first. */
    Mono<List<UsagePoint>> series(TenantId tenant, int hours);
}
