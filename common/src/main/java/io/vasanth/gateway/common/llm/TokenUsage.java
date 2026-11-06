package io.vasanth.gateway.common.llm;

/**
 * Token counts for a single completion. The unit of billing and of quota drawdown.
 *
 * @param promptTokens tokens consumed by the input
 * @param completionTokens tokens produced by the model
 */
public record TokenUsage(long promptTokens, long completionTokens) {

    public static final TokenUsage NONE = new TokenUsage(0L, 0L);

    public TokenUsage {
        if (promptTokens < 0L || completionTokens < 0L) {
            throw new IllegalArgumentException(
                    "token counts must not be negative: prompt=" + promptTokens
                            + " completion=" + completionTokens);
        }
    }

    public long total() {
        return promptTokens + completionTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }

    /**
     * Usage recorded for a cache hit.
     *
     * <p>Prompt tokens are still charged because the caller's input was still processed and
     * still occupies the cache; completion tokens are zero because no model generated
     * anything. Reporting a hit as {@link #NONE} would make the saving invisible, and
     * reporting it at full cost would make the cache look worthless. This asymmetry is what
     * the dashboard's "tokens saved" figure is computed from.
     */
    public static TokenUsage cacheHit(long promptTokens) {
        return new TokenUsage(promptTokens, 0L);
    }
}
