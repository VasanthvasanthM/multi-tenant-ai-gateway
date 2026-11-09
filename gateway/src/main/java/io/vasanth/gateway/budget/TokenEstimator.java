package io.vasanth.gateway.budget;

/**
 * Approximates a token count from character length.
 *
 * <h2>Why an estimate exists at all</h2>
 *
 * <p>Only for the streaming path, and only because the alternative is worse. A streamed
 * response arrives as opaque text deltas: the provider sends no usage frame unless asked
 * with {@code stream_options.include_usage}, and the gateway deliberately passes deltas
 * through without parsing them, because reassembling the response to count it would mean
 * buffering every stream in memory - the exact cost the streaming path exists to avoid.
 *
 * <p>So the choice is between an approximate drawdown and none at all. None at all means a
 * tenant that streams exclusively is bounded by the rate limiter and not by its budget,
 * which is a hole large enough to drive through deliberately. An approximation that errs
 * upward closes it.
 *
 * <h2>This must never be used for billing</h2>
 *
 * <p>{@code usage_ledger} in Postgres is the authoritative record and is written from the
 * provider's own reported counts. This estimate feeds the <em>enforcement</em> counter in
 * Redis only, which is already documented as approximate at the edges. A tenant is never
 * charged from this number.
 */
public final class TokenEstimator {

    /**
     * Characters per token.
     *
     * <p>Four is the long-standing rule of thumb for English BPE tokenisers and is what
     * every vendor's own "rough estimate" guidance uses. It is wrong in both directions for
     * other scripts - CJK runs closer to one character per token, so this UNDER-counts
     * there - and that limitation is stated rather than papered over, because the honest
     * fix is {@code include_usage}, not a bigger table of constants.
     */
    private static final int CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    /**
     * Estimated tokens for {@code characters} characters of text.
     *
     * <p>Rounds <b>up</b>, deliberately. For a budget, over-counting makes a tenant hit
     * their limit slightly early; under-counting lets them exceed it. Erring toward the
     * limit is the direction that keeps the control meaningful, and the error is bounded by
     * one token per call rather than accumulating.
     *
     * @param characters character count; negative is treated as zero
     */
    public static long estimateFromCharacters(long characters) {
        if (characters <= 0L) {
            return 0L;
        }
        return (characters + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /** Estimated tokens for the given text, null-safe. */
    public static long estimate(CharSequence text) {
        return text == null ? 0L : estimateFromCharacters(text.length());
    }
}
