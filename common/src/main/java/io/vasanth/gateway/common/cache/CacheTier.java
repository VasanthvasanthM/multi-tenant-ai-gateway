package io.vasanth.gateway.common.cache;

/**
 * Which layer satisfied a request.
 *
 * <p>Carried on every response as the {@code X-Cache-Tier} header and emitted as a metric
 * label, because hit-rate per tier is the single number that determines whether this system
 * is worth running at all.
 */
public enum CacheTier {

    /** Redis exact SHA-256 prompt hash. Target p99 under 2 ms. */
    TIER1_EXACT("tier1_exact", true),

    /** pgvector cosine similarity over prompt embeddings. Target p99 under 15 ms. */
    TIER2_SEMANTIC("tier2_semantic", true),

    /** No cache could serve it; an upstream provider was called. */
    UPSTREAM("upstream", false);

    private final String label;
    private final boolean hit;

    CacheTier(String label, boolean hit) {
        this.label = label;
        this.hit = hit;
    }

    /**
     * Stable, lowercase, snake_case label for metrics and headers.
     *
     * <p>Deliberately not {@code name().toLowerCase()}: that would couple the external metric
     * label to the Java constant name, so renaming the enum would silently break every
     * dashboard and alert built on it.
     */
    public String label() {
        return label;
    }

    public boolean isHit() {
        return hit;
    }
}
