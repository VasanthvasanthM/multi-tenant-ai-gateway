package io.vasanth.gateway.embedding;

import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * A dense vector representation of a prompt.
 *
 * <h2>Vectors are stored L2-normalised</h2>
 *
 * <p>After normalisation, cosine similarity reduces to a plain dot product - the division
 * by the product of magnitudes becomes a division by 1. That matters twice over:
 *
 * <ul>
 *   <li>In Java, {@link #cosineSimilarity} skips two square roots per comparison.
 *   <li>In Postgres, pgvector's inner-product operator {@code <#>} is cheaper than its
 *       cosine operator {@code <=>}, and an HNSW index built for inner product gives the
 *       same ranking as one built for cosine <em>only if</em> the vectors are normalised.
 * </ul>
 *
 * <p>So normalisation is not a stylistic choice; the index correctness depends on it. The
 * invariant is enforced at construction rather than trusted.
 *
 * <h2>Defensive copying</h2>
 *
 * <p>The backing {@code float[]} is copied in and out. An embedding is used as a cache
 * lookup key, and a caller retaining a reference to the array could mutate a vector after
 * it had been indexed, leaving a stored row that no longer matches the text it came from.
 */
public final class Embedding {

    /** all-MiniLM-L6-v2 output width. */
    public static final int MINILM_DIMENSION = 384;

    /**
     * Tolerance for the unit-length check.
     *
     * <p>float32 accumulation over a few hundred terms drifts by more than the obvious
     * 1e-6; 1e-4 is loose enough to accept genuinely normalised vectors and tight enough to
     * catch an un-normalised one, whose magnitude is typically far from 1.
     */
    private static final double NORM_TOLERANCE = 1e-4d;

    private final float[] values;

    private Embedding(float[] values) {
        this.values = values;
    }

    /**
     * Wraps an already-normalised vector.
     *
     * @throws IllegalArgumentException if it is empty or not unit length
     */
    public static Embedding of(float[] values) {
        Objects.requireNonNull(values, "values must not be null");
        if (values.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }

        float[] copy = values.clone();
        double magnitude = magnitudeOf(copy);
        if (Math.abs(magnitude - 1.0d) > NORM_TOLERANCE) {
            throw new IllegalArgumentException(
                    "embedding must be L2-normalised; magnitude was " + magnitude
                            + ". Use Embedding.normalizing(float[]) if the source is raw.");
        }
        return new Embedding(copy);
    }

    /** Normalises a raw model output and wraps it. */
    public static Embedding normalizing(float[] raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        if (raw.length == 0) {
            throw new IllegalArgumentException("embedding must not be empty");
        }

        float[] copy = raw.clone();
        double magnitude = magnitudeOf(copy);

        // A genuinely zero vector has no direction, so there is no meaningful unit vector
        // to produce. It means the model emitted nothing usable; failing loudly beats
        // storing NaNs that would silently poison every later similarity comparison.
        if (magnitude < 1e-12d) {
            throw new IllegalArgumentException(
                    "cannot normalise a zero-magnitude vector; the model produced no signal");
        }

        for (int i = 0; i < copy.length; i++) {
            copy[i] = (float) (copy[i] / magnitude);
        }
        return new Embedding(copy);
    }

    public int dimension() {
        return values.length;
    }

    /** A copy of the backing array. */
    public float[] toArray() {
        return values.clone();
    }

    /**
     * Cosine similarity with another embedding, in [-1, 1].
     *
     * <p>A plain dot product, valid because both operands are unit length by construction.
     */
    public double cosineSimilarity(Embedding other) {
        Objects.requireNonNull(other, "other must not be null");
        if (other.values.length != values.length) {
            throw new IllegalArgumentException(
                    "dimension mismatch: " + values.length + " vs " + other.values.length);
        }

        double dot = 0.0d;
        for (int i = 0; i < values.length; i++) {
            dot += (double) values[i] * other.values[i];
        }
        // Floating-point accumulation can nudge an identical pair just past 1.0, which then
        // fails a "similarity <= 1" assertion or an exact-match threshold comparison.
        return Math.clamp(dot, -1.0d, 1.0d);
    }

    /**
     * pgvector literal form, e.g. {@code [0.1,-0.2,0.3]}.
     *
     * <p>Built by hand rather than via {@code Arrays.toString} because that inserts spaces
     * after commas, which pgvector's parser rejects.
     */
    public String toPgVector() {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : values) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }

    /** Parses a pgvector literal. */
    public static Embedding fromPgVector(String literal) {
        Objects.requireNonNull(literal, "literal must not be null");
        String trimmed = literal.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '[' || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("not a pgvector literal: " + abbreviate(trimmed));
        }

        String body = trimmed.substring(1, trimmed.length() - 1);
        if (body.isBlank()) {
            throw new IllegalArgumentException("pgvector literal is empty");
        }

        String[] parts = body.split(",");
        float[] parsed = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            parsed[i] = Float.parseFloat(parts[i].trim());
        }

        // Round-tripped through text and back, so re-normalise rather than assert: the
        // decimal representation loses enough precision to drift outside the tolerance.
        return normalizing(parsed);
    }

    private static double magnitudeOf(float[] values) {
        double sumOfSquares = 0.0d;
        for (float value : values) {
            sumOfSquares += (double) value * value;
        }
        return Math.sqrt(sumOfSquares);
    }

    private static String abbreviate(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Embedding embedding && Arrays.equals(values, embedding.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Embedding[dim=" + values.length + ']';
    }
}
