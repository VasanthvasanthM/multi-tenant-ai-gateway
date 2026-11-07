package io.vasanth.gateway.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The blended token price used for the "money saved" figure.
 *
 * <p>Its own properties record rather than another component of
 * {@code GatewayProperties}: adding a fifth component to that record would change every
 * construction site, including five test files, for a value unrelated to any of them.
 *
 * <p>ONE blended rate, not separate input and output prices. The rollup records prompt
 * and completion tokens separately but records {@code upstream_tokens} as a single
 * total, so the saving cannot be split by direction - and inventing a split would give
 * the number a precision it does not have.
 *
 * @param usdPerMillionTokens blended price, or {@code null} when unset.
 *     <p>A boxed {@code Double} specifically so that UNSET and EXPLICITLY ZERO are
 *     distinguishable. A primitive binds an absent property to 0.0, which would make
 *     "no price configured" and "this deployment has no per-token cost" the same value -
 *     and the second is a real case: a self-hosted model behind
 *     {@code OllamaProvider} costs nothing per token, so a money figure there is
 *     meaningless rather than merely unknown.
 *     <p>Unset falls back to the default. Zero means "show no money figure".
 */
@ConfigurationProperties(prefix = "gateway.pricing")
public record UsagePricing(Double usdPerMillionTokens) {

    /**
     * Matches the figure derived in docs/architecture/scaling-model.md: $0.15 per
     * million input tokens and $0.60 per million output at an even split is $0.375
     * blended.
     */
    public static final double DEFAULT_USD_PER_MILLION = 0.375d;

    public UsagePricing {
        if (usdPerMillionTokens == null) {
            usdPerMillionTokens = DEFAULT_USD_PER_MILLION;
        } else if (usdPerMillionTokens < 0.0d) {
            throw new IllegalArgumentException(
                    "gateway.pricing.usd-per-million-tokens must not be negative: "
                            + usdPerMillionTokens);
        }
    }

    public static UsagePricing defaults() {
        return new UsagePricing(null);
    }

    /** True when a money figure should be shown at all. */
    public boolean showsMoney() {
        return usdPerMillionTokens > 0.0d;
    }

    /** The rate, as a primitive, for arithmetic. */
    public double rate() {
        return usdPerMillionTokens;
    }
}
