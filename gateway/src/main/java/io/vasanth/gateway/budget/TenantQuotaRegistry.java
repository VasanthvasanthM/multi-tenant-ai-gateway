package io.vasanth.gateway.budget;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import io.vasanth.gateway.common.tenant.TenantId;
import reactor.core.publisher.Mono;

/**
 * Resolves a tenant's token limit, falling back to the configured default.
 *
 * <h2>Where the number comes from</h2>
 *
 * <p>{@code tenants.quota_tokens} is the system of record, in the control plane's database.
 * The gateway must not read that table on the request path - it is a different service's
 * schema, and a Postgres round trip per request to fetch a number that changes monthly
 * would be absurd. So the control plane publishes it to Redis and the gateway reads it
 * there, exactly as it does for API keys.
 *
 * <p><b>Nothing publishes it yet.</b> The control plane has no HTTP API and its provisioning
 * saga has no concrete steps, so in practice every tenant currently resolves to
 * {@code gateway.budget.default-limit-tokens}. That default is deliberately the same
 * 1,000,000 as the schema's, so behaviour does not change the day publishing starts working
 * for a tenant that was on the default anyway. This class is written so that the only thing
 * needed to make per-tenant limits real is a writer - no gateway change.
 *
 * <h2>Failure is not the same as absence</h2>
 *
 * <p>A missing key means "this tenant has no override", which is a normal state and resolves
 * to the default. A Redis <em>error</em> means the limit is unknown, and that is propagated
 * rather than silently defaulted: quietly substituting a default on a connection failure
 * would hand an unlimited tenant a 1,000,000-token cap, or a capped tenant a larger one,
 * with nothing in the logs. {@link TokenBudgetLedger} decides what an unknown limit means
 * according to the fail-open policy, which is where that decision belongs.
 */
@Component
public class TenantQuotaRegistry {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaRegistry.class);

    private final ReactiveStringRedisTemplate redis;
    private final BudgetProperties config;

    public TenantQuotaRegistry(ReactiveStringRedisTemplate redis, BudgetProperties config) {
        this.redis = redis;
        this.config = config;
    }

    /**
     * The tenant's limit in tokens; zero or less means unlimited.
     *
     * @return the published limit, or the configured default when none is published. Signals
     *     an error when the store cannot be reached.
     */
    public Mono<Long> limitFor(TenantId tenant) {
        return redis.opsForValue()
                .get(quotaKey(tenant))
                .mapNotNull(raw -> parse(tenant, raw))
                .defaultIfEmpty(config.defaultLimitTokens());
    }

    /**
     * Hash-tagged with the tenant, so the limit and the counter share a Redis Cluster slot.
     *
     * <p>Unlike the {@code apikey:} index, which is global because a lookup starts from a
     * key digest and not from a tenant, this is tenant-scoped data and belongs in the
     * tenant's slot alongside its cache entries and token bucket.
     */
    static String quotaKey(TenantId tenant) {
        return tenant.redisHashTag() + ":quota";
    }

    /**
     * Parses a published limit, treating a malformed value as absent.
     *
     * <p>Returning null here routes to {@code defaultIfEmpty}. The alternative - failing the
     * request - would let one bad write in Redis take a tenant off the air, and a limit that
     * cannot be read is better treated as "no override published" than as an outage. It is
     * logged at WARN because it means a writer is producing garbage, which someone needs to
     * fix even though traffic is unaffected.
     */
    private Long parse(TenantId tenant, String raw) {
        try {
            long limit = Long.parseLong(raw.trim());
            if (limit < 0L) {
                log.warn("negative token limit {} published for tenant {}; using the default",
                        limit, tenant);
                return null;
            }
            return limit;
        } catch (NumberFormatException malformed) {
            log.warn("unparseable token limit \"{}\" published for tenant {}; using the "
                    + "default", raw, tenant);
            return null;
        }
    }
}
