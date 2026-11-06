package io.vasanth.gateway.common.llm;

/**
 * Sampling parameters that change the model's output and therefore belong in the cache key.
 *
 * <p>Omitting any of these from the key is a correctness bug: a request at
 * {@code temperature=0} and the same prompt at {@code temperature=1.5} are different
 * questions, and serving one for the other would make the cache actively wrong rather than
 * merely unhelpful.
 *
 * @param temperature randomness, 0.0 (deterministic) to 2.0
 * @param topP nucleus sampling cutoff, 0.0 exclusive to 1.0 inclusive
 * @param maxTokens upper bound on generated tokens
 */
public record SamplingParams(double temperature, double topP, int maxTokens) {

    /** Deterministic defaults. Temperature 0 is the only setting a cache can fully trust. */
    public static final SamplingParams DETERMINISTIC = new SamplingParams(0.0d, 1.0d, 1024);

    public SamplingParams {
        if (!(temperature >= 0.0d && temperature <= 2.0d)) {
            throw new IllegalArgumentException("temperature must be within [0, 2]: " + temperature);
        }
        if (!(topP > 0.0d && topP <= 1.0d)) {
            throw new IllegalArgumentException("topP must be within (0, 1]: " + topP);
        }
        if (maxTokens < 1 || maxTokens > 131_072) {
            throw new IllegalArgumentException("maxTokens must be within [1, 131072]: " + maxTokens);
        }
    }

    /**
     * Whether the model is being asked to produce deterministic output.
     *
     * <p>The semantic (Tier 2) cache is only safe to consult for deterministic requests. At a
     * non-zero temperature the caller has explicitly asked for variety, and returning a
     * stored answer for a merely <em>similar</em> prompt defeats the thing they asked for.
     * Tier 1 exact-match remains valid at any temperature because the caller is free to
     * resend the identical request and accept an identical answer.
     */
    public boolean isDeterministic() {
        return Double.compare(temperature, 0.0d) == 0;
    }
}
