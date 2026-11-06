package io.vasanth.gateway.embedding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deterministic lexical embedding built by feature hashing over character n-grams.
 *
 * <h2>What this is for</h2>
 *
 * <p>It lets the whole Tier-2 pipeline - schema, HNSW index, similarity search, shadow
 * mode, integration tests - be exercised without a 90 MB model download, without ONNX
 * native libraries, and without the 6 ms per call the real model costs. Tests run in
 * milliseconds and produce identical results on every machine.
 *
 * <h2>What this is NOT</h2>
 *
 * <p><b>It is not semantic.</b> It captures surface form, not meaning. "car" and
 * "automobile" share no character n-grams, so it scores them as unrelated, while the real
 * model places them close together. Conversely "cat" and "cart" look similar to it and are
 * not.
 *
 * <p>So it must never be the production model, and the service logs a warning when it is
 * selected. It is a test double that happens to be good enough to make similarity
 * assertions meaningful: "hello world" and "hello world!" genuinely do score close, and
 * "hello world" and "quantum chromodynamics" genuinely do not.
 *
 * <h2>How feature hashing works here</h2>
 *
 * <p>Every character trigram in the text is hashed to a bucket in a fixed-width vector and
 * accumulated, with a second hash deciding the sign. The signed accumulation matters: with
 * unsigned counts, hash collisions only ever inflate a bucket, so unrelated documents drift
 * systematically closer together. Random signs make collisions cancel in expectation
 * instead.
 */
public final class HashingEmbeddingModel implements EmbeddingModel {

    /** Matches the real model so the Postgres {@code vector(384)} column is unchanged. */
    private static final int DIMENSION = Embedding.MINILM_DIMENSION;

    private static final int NGRAM_SIZE = 3;

    private static final String MODEL_ID = "hashing-trigram-v1";

    /** Distinct odd multipliers; using one hash for both bucket and sign correlates them. */
    private static final int BUCKET_SEED = 0x9E3779B1;
    private static final int SIGN_SEED = 0x85EBCA6B;

    @Override
    public Embedding embed(String text) {
        float[] accumulator = new float[DIMENSION];

        // Lowercased with Locale.ROOT, not the default locale: in a Turkish locale
        // "I".toLowerCase() yields a dotless i and the same text would hash differently
        // depending on where the server runs.
        String normalized = text.toLowerCase(Locale.ROOT);

        // Operates on code points so that an emoji or a CJK character is one unit rather
        // than two surrogate halves that would produce meaningless trigrams.
        int[] codePoints = normalized.codePoints().toArray();

        if (codePoints.length < NGRAM_SIZE) {
            // Too short for a trigram. Hash the whole thing as a single feature so that
            // short prompts still produce a usable, non-zero vector.
            accumulate(accumulator, codePoints, 0, codePoints.length);
        } else {
            for (int i = 0; i <= codePoints.length - NGRAM_SIZE; i++) {
                accumulate(accumulator, codePoints, i, NGRAM_SIZE);
            }
        }

        boolean allZero = true;
        for (float value : accumulator) {
            if (value != 0.0f) {
                allZero = false;
                break;
            }
        }
        if (allZero) {
            // Empty input, or every feature cancelled out. Pin one bucket so the result is
            // still a valid unit vector rather than an unnormalisable zero.
            accumulator[0] = 1.0f;
        }

        return Embedding.normalizing(accumulator);
    }

    @Override
    public List<Embedding> embedBatch(List<String> texts) {
        List<Embedding> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            results.add(embed(text));
        }
        return List.copyOf(results);
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public int maxConcurrency() {
        // Stateless and allocation-light, so it is safe on any number of threads.
        return Integer.MAX_VALUE;
    }

    @Override
    public void close() {
        // Nothing to release.
    }

    private static void accumulate(float[] accumulator, int[] codePoints, int offset, int length) {
        int hash = hashRange(codePoints, offset, length);

        int bucket = Math.floorMod(mix(hash, BUCKET_SEED), DIMENSION);
        int sign = (mix(hash, SIGN_SEED) & 1) == 0 ? 1 : -1;

        accumulator[bucket] += sign;
    }

    private static int hashRange(int[] codePoints, int offset, int length) {
        int hash = 1;
        for (int i = offset; i < offset + length; i++) {
            hash = 31 * hash + codePoints[i];
        }
        return hash;
    }

    /** Finalising avalanche, so adjacent n-gram hashes land in unrelated buckets. */
    private static int mix(int value, int seed) {
        int h = value ^ seed;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return h;
    }

    /** Non-obvious enough to be worth stating where a reader will see it. */
    public static byte[] marker() {
        return MODEL_ID.getBytes(StandardCharsets.UTF_8);
    }
}
