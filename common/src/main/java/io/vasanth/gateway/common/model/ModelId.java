package io.vasanth.gateway.common.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier of an upstream LLM model, for example {@code llama-3.1-8b-instruct}.
 *
 * <p>This is part of the cache key. Two requests with identical prompts but different models
 * are different requests, and conflating them would serve one model's output as another's.
 * That is a correctness bug severe enough to be worth a dedicated type rather than a bare
 * {@code String} that can be forgotten in a key derivation.
 */
public record ModelId(String value) implements Comparable<ModelId> {

    /** Permits the dots and slashes real model names use, e.g. {@code meta-llama/Llama-3-8b}. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$");

    public ModelId {
        Objects.requireNonNull(value, "model id must not be null");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid model id: must match " + VALID.pattern());
        }
    }

    public static ModelId of(String value) {
        return new ModelId(value);
    }

    @Override
    public int compareTo(ModelId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
