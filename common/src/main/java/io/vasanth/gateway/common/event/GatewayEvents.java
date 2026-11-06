package io.vasanth.gateway.common.event;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The event contracts shared between the data plane and the asynchronous services.
 *
 * <h2>Why these live in {@code common} rather than in each service</h2>
 *
 * <p>An event schema is a contract between two deployables. Defining it twice guarantees
 * the copies diverge, and the divergence shows up as a consumer silently dropping a field
 * rather than as a compile error. One definition, compiled into both sides, makes an
 * incompatible change fail the build.
 *
 * <h2>Every event carries a schema version</h2>
 *
 * <p>Producers and consumers are deployed independently, so at any moment an old consumer
 * may receive a new event. The version lets a consumer recognise what it cannot handle and
 * route it to a dead-letter topic, instead of parsing it half-correctly and writing
 * plausible-looking nonsense to the ledger.
 *
 * <h2>Every event carries a tenant id</h2>
 *
 * <p>That is the Kafka partition key. Partitioning by tenant gives per-tenant ordering,
 * which the metering consumer needs - usage must be applied in the order it occurred - and
 * it matches the sharding scheme used everywhere else in the system.
 */
public final class GatewayEvents {

    /** Bump when a record below changes in a way an old consumer cannot read. */
    public static final int SCHEMA_VERSION = 1;

    public static final String TOPIC_USAGE = "gateway.usage.v1";
    public static final String TOPIC_CACHE_WRITE = "gateway.cache-write.v1";
    public static final String TOPIC_AUDIT = "gateway.audit.v1";
    public static final String TOPIC_TENANT = "gateway.tenant.v1";
    public static final String TOPIC_DEAD_LETTER = "gateway.dead-letter.v1";

    private GatewayEvents() {
        // constants holder
    }

    /**
     * One completed request, for billing and quota drawdown.
     *
     * <p>Consumed by the metering service, which must treat it as <b>at-least-once</b>:
     * Kafka can redeliver after a consumer restart, and double-counting here would
     * double-bill. Idempotency comes from the {@code (tenantId, requestId)} unique
     * constraint on the usage ledger, not from hoping redelivery never happens.
     *
     * @param schemaVersion contract version
     * @param requestId unique per request; the idempotency key for the whole pipeline
     * @param tenantId owning tenant, and the Kafka partition key
     * @param modelId model used
     * @param servedBy which tier answered: tier1_exact, tier2_semantic or upstream
     * @param provider upstream that produced it, or "cache"
     * @param promptTokens input tokens
     * @param completionTokens generated tokens; zero for a cache hit
     * @param latencyMillis wall-clock duration
     * @param occurredAt when the request completed
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageEvent(
            @JsonProperty("v") int schemaVersion,
            @JsonProperty("rid") String requestId,
            @JsonProperty("tid") String tenantId,
            @JsonProperty("model") String modelId,
            @JsonProperty("tier") String servedBy,
            @JsonProperty("prov") String provider,
            @JsonProperty("pt") long promptTokens,
            @JsonProperty("ct") long completionTokens,
            @JsonProperty("ms") long latencyMillis,
            @JsonProperty("at") Instant occurredAt) {

        public UsageEvent {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }

        public long totalTokens() {
            return promptTokens + completionTokens;
        }

        /**
         * Whether this request actually cost money upstream.
         *
         * <p>A cache hit still consumes quota - the tenant made a request - but it incurs
         * no vendor cost, and the two have to be billed differently or the saving is
         * invisible.
         */
        public boolean billableUpstream() {
            return "upstream".equals(servedBy);
        }
    }

    /**
     * A completion to be written into the Tier-2 vector store.
     *
     * <p>The embedding travels with the event. The gateway already computed it during
     * lookup, so recomputing it in the indexer would pay the same ~6 ms of CPU twice for
     * no benefit. The indexer's job is the write itself - HNSW insertion is expensive and
     * write-amplifying, and keeping it off the request path is why this service exists.
     *
     * @param embedding the L2-normalised vector in pgvector literal form
     * @param embeddingModel which model produced it; vectors from different models must
     *     never be compared
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CacheWriteEvent(
            @JsonProperty("v") int schemaVersion,
            @JsonProperty("tid") String tenantId,
            @JsonProperty("key") String cacheKey,
            @JsonProperty("model") String modelId,
            @JsonProperty("em") String embeddingModel,
            @JsonProperty("prompt") String promptText,
            @JsonProperty("vec") String embedding,
            @JsonProperty("resp") String response,
            @JsonProperty("pt") long promptTokens,
            @JsonProperty("ct") long completionTokens,
            @JsonProperty("prov") String provider,
            @JsonProperty("at") Instant createdAt,
            @JsonProperty("exp") Instant expiresAt) {

        public CacheWriteEvent {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(cacheKey, "cacheKey must not be null");
        }
    }

    /**
     * An immutable record of who did what.
     *
     * <p>Separate from {@link UsageEvent} because the requirements differ completely:
     * audit is write-once and retained for years, usage is mutable and kept for a billing
     * cycle. Conflating them means applying the strictest rule of both to all of it.
     *
     * @param apiKeyFingerprint first 16 hex characters of the key's SHA-256. Enough to
     *     identify which key was used without storing anything that could authenticate
     * @param outcome HTTP status or error code
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditEvent(
            @JsonProperty("v") int schemaVersion,
            @JsonProperty("rid") String requestId,
            @JsonProperty("tid") String tenantId,
            @JsonProperty("key") String apiKeyFingerprint,
            @JsonProperty("model") String modelId,
            @JsonProperty("tier") String servedBy,
            @JsonProperty("out") String outcome,
            @JsonProperty("ip") String sourceAddress,
            @JsonProperty("at") Instant occurredAt) {

        public AuditEvent {
            Objects.requireNonNull(requestId, "requestId must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
        }
    }

    /**
     * A change to tenant state that the data plane must learn about.
     *
     * <p>This is what keeps the hot path free of synchronous calls to the control plane.
     * Key and quota changes arrive here, the gateway updates its local Redis view, and a
     * cache hit therefore touches Redis and nothing else.
     *
     * <p>The consequence, stated plainly: revocation is eventually consistent. A revoked
     * key keeps working until this event is consumed - normally well under a second. For
     * an immediate kill the control plane also deletes the Redis entry directly, which
     * makes revocation synchronous in practice while leaving this as the durable path.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TenantEvent(
            @JsonProperty("v") int schemaVersion,
            @JsonProperty("tid") String tenantId,
            @JsonProperty("type") Type type,
            @JsonProperty("key") String apiKeyHash,
            @JsonProperty("quota") Long quotaTokens,
            @JsonProperty("thr") Double similarityThreshold,
            @JsonProperty("at") Instant occurredAt) {

        public TenantEvent {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(type, "type must not be null");
        }

        public enum Type {
            TENANT_CREATED,
            TENANT_SUSPENDED,
            TENANT_DELETED,
            API_KEY_ISSUED,
            API_KEY_REVOKED,
            QUOTA_CHANGED,
            THRESHOLD_CHANGED
        }
    }
}
