package io.vasanth.gateway.common.llm;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifies which upstream implementation served a request, e.g. {@code mock},
 * {@code openai-compatible}, {@code ollama}.
 *
 * <p>Recorded on every response and emitted as a metric label so that a latency or error-rate
 * regression can be attributed to one provider rather than blamed on the gateway.
 */
public record ProviderId(String value) {

    // ORDER MATTERS. Static initialisers run in declaration order, and the compact
    // constructor below reads VALID. If the CACHE constant were declared first it would be
    // constructed while VALID is still null, and the class would fail to initialise with an
    // ExceptionInInitializerError - at bean-creation time, far from the real cause.
    private static final Pattern VALID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,31}$");

    /** Used on responses served entirely from cache, where no provider was involved. */
    public static final ProviderId CACHE = new ProviderId("cache");

    public ProviderId {
        Objects.requireNonNull(value, "provider id must not be null");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid provider id: must match " + VALID.pattern());
        }
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
