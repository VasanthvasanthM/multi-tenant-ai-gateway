package io.vasanth.gateway.common.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * The Tier-1 exact-match cache key: a SHA-256 digest over every input that can change the
 * model's answer.
 *
 * <p>This is the most security-sensitive class in the codebase. A key that is too coarse
 * returns one tenant's answer to another; a key that is too fine merely wastes money. The
 * design therefore errs entirely toward being too fine.
 *
 * <h2>Why fields are length-prefixed</h2>
 *
 * <p>The naive implementation concatenates fields before hashing. That is exploitable.
 * Without delimiters or length prefixes these two requests hash identically:
 *
 * <pre>{@code
 * tenant = "acme",  model = "gpt-4"      ->  "acmegpt-4"
 * tenant = "acmeg", model = "pt-4"       ->  "acmegpt-4"
 * }</pre>
 *
 * <p>A tenant who can choose their own identifier could therefore craft one that collides
 * with a victim's namespace and read their cached completions. A delimiter byte is not
 * sufficient either, because the delimiter can appear inside a prompt. Prefixing each field
 * with its byte length makes the encoding unambiguous, so distinct field tuples can never
 * produce the same digest input.
 *
 * <h2>Why there is a schema version</h2>
 *
 * <p>The version byte is the first thing hashed. Changing the derivation - adding a field,
 * changing normalisation - and bumping the version changes every key at once, so old entries
 * become unreachable rather than being served under new semantics. Cache entries then age
 * out naturally by TTL instead of requiring a flush.
 *
 * @param tenantId owning tenant, retained so the Redis key can be namespaced
 * @param hex lowercase hex SHA-256 digest, 64 characters
 */
public record CacheKey(TenantId tenantId, String hex) {

    /** Bump when the derivation below changes in any way. See class javadoc. */
    private static final byte SCHEMA_VERSION = 1;

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    public CacheKey {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(hex, "hex must not be null");
        if (hex.length() != 64) {
            throw new IllegalArgumentException(
                    "expected a 64-character SHA-256 hex digest, got " + hex.length());
        }
    }

    /**
     * Derives the cache key for a request.
     *
     * <p>Field order is fixed and must not be rearranged without bumping
     * {@code SCHEMA_VERSION}, since reordering changes every key.
     */
    public static CacheKey derive(CompletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        MessageDigest digest = newDigest();

        digest.update(SCHEMA_VERSION);

        putString(digest, request.tenantId().value());
        putString(digest, request.model().value());

        // doubleToLongBits rather than Double.toString: the string form of a double is
        // locale-independent in Java but its precision rules have changed between releases,
        // and a key derivation must be stable across JVM upgrades. The bit pattern is not.
        putLong(digest, Double.doubleToLongBits(request.sampling().temperature()));
        putLong(digest, Double.doubleToLongBits(request.sampling().topP()));
        putInt(digest, request.sampling().maxTokens());

        putString(digest, request.toolDefinitions());

        // The message count is hashed before the messages so that a conversation cannot be
        // confused with a differently-split one carrying the same concatenated text.
        putInt(digest, request.messages().size());
        for (Message message : request.messages()) {
            putString(digest, message.role().wireValue());
            putString(digest, PromptNormalizer.normalize(message.content()));
        }

        // Deliberately excluded: `stream` and `idempotencyKey`.
        //
        // `stream` is a transport choice, not a semantic one - the same answer is simply
        // delivered in chunks, so a streamed request should be allowed to hit an entry
        // stored by a non-streamed one and vice versa.
        //
        // `idempotencyKey` identifies a retry of one specific call. Including it would give
        // every retry a distinct key, which defeats the entire point of the cache.

        return new CacheKey(request.tenantId(), HEX.formatHex(digest.digest()));
    }

    /**
     * The Redis key for this entry, namespaced by tenant and wrapped in a cluster hash tag.
     *
     * <p>Shape: <code>{tenant:acme}:c1:&lt;hex&gt;</code>. The {@code c1} segment is the
     * Tier-1 namespace, keeping cache entries distinguishable from rate-limit buckets and
     * quota counters that share the same hash tag.
     */
    public String redisKey() {
        return tenantId.redisHashTag() + ":c1:" + hex;
    }

    /**
     * First 16 hex characters, for logs and trace attributes.
     *
     * <p>The full digest is not secret, but it is a stable identifier for user-supplied
     * content; truncating keeps logs readable and reduces how much a log reader can
     * correlate.
     */
    public String shortForm() {
        return hex.substring(0, 16);
    }

    // ---------------------------------------------------------------------
    // Unambiguous field encoding
    // ---------------------------------------------------------------------

    private static void putString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void putLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static MessageDigest newDigest() {
        try {
            // A fresh instance per call because MessageDigest is not thread-safe. The
            // provider lookup costs roughly a microsecond, which is immaterial next to the
            // sub-2 ms Tier-1 budget; a ThreadLocal would be a premature optimisation that
            // leaks on virtual threads.
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every conforming JRE, so this cannot happen. If it
            // somehow does, the system has no safe way to namespace tenants and must not
            // continue serving.
            throw new IllegalStateException(ALGORITHM + " is unavailable in this JRE", e);
        }
    }
}
