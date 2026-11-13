package io.vasanth.gateway.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Known-answer vectors for {@link HashingEmbeddingModel}.
 *
 * <h2>Why exact components and not properties</h2>
 *
 * <p>A mutation run left 22 survivors in this class, almost all {@code MathMutator} - an
 * operator swapped inside the hash or the accumulation. Every property-style assertion
 * passes against those mutants, which is why they survived: swap a {@code *} for a
 * {@code +} in the bucket hash and the output is still deterministic, still unit length,
 * still 384-dimensional, and still produces "similar" scores for similar strings. It is
 * simply a DIFFERENT vector - and a different vector means every row already written to
 * the pgvector index is now unreachable, because stored vectors and query vectors no
 * longer come from the same function.
 *
 * <p>That is the actual risk this class carries: it is the embedding used by every Tier-2
 * integration test and every local demo, so a silent change to it invalidates stored data
 * rather than failing a build. The only assertion that catches it is the exact output.
 *
 * <p>These vectors were GENERATED from the current implementation, so they pin
 * STABILITY, not correctness - exactly as
 * {@code common/.../MurmurHash3KnownAnswerTest} does. A deliberate algorithm change
 * means regenerating them and bumping {@code MODEL_ID}, because the id is what tells
 * Tier 2 that previously stored vectors were produced by a different model.
 */
class HashingKnownAnswerTest {

    private static final Offset<Float> EXACT = Offset.offset(1e-5f);

    private final HashingEmbeddingModel model = new HashingEmbeddingModel();

    /** Builds an ordered index -> value map; the index comparison below is order-sensitive. */
    private static Map<Integer, Float> expect(Object... pairs) {
        Map<Integer, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((Integer) pairs[i], (Float) pairs[i + 1]);
        }
        return out;
    }

    /** index -> value, for the non-zero components only. */
    private static Map<Integer, Float> nonZero(float[] values) {
        Map<Integer, Float> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] != 0.0f) {
                out.put(i, values[i]);
            }
        }
        return out;
    }

    private void assertVector(String text, Map<Integer, Float> expected) {
        float[] actual = model.embed(text).toArray();

        assertThat(actual).hasSize(Embedding.MINILM_DIMENSION);
        assertThat(nonZero(actual).keySet())
                .as("non-zero component indices for \"%s\"", text)
                .containsExactlyElementsOf(expected.keySet());

        expected.forEach((index, value) -> assertThat(actual[index])
                .as("component %d of \"%s\"", index, text)
                .isCloseTo(value, EXACT));
    }

    @Test
    @DisplayName("\"hello world\" hashes to nine trigram buckets at +/- 1/3")
    void helloWorld() {
        // Nine trigrams in an 11-character string, each contributing +1 or -1 to one
        // bucket, then L2-normalised: 1/sqrt(9) = 1/3. Both the MAGNITUDE and the SIGNS
        // are pinned - the sign comes from a second hash with its own seed, and nothing
        // else in the suite would notice if every feature started landing positive.
        assertVector("hello world", expect(
                42, 0.333333f,
                67, -0.333333f,
                172, -0.333333f,
                186, 0.333333f,
                203, -0.333333f,
                239, 0.333333f,
                251, -0.333333f,
                338, -0.333333f,
                343, -0.333333f));
    }

    @Test
    @DisplayName("case is folded with Locale.ROOT, so capitalisation cannot change a vector")
    void caseFolding() {
        // The class comment warns that a Turkish locale turns "I".toLowerCase() into a
        // dotless i, which would make the same prompt hash differently depending on which
        // host served it - and Tier 2 would then miss its own cached entries.
        assertThat(model.embed("Hello World").toArray())
                .containsExactly(model.embed("hello world").toArray());
        assertThat(model.embed("HELLO WORLD").toArray())
                .containsExactly(model.embed("hello world").toArray());
    }

    @Test
    @DisplayName("a string shorter than a trigram hashes as one whole feature")
    void shorterThanATrigram() {
        // The short-input branch: with fewer than three code points there is no trigram,
        // so the whole string becomes a single feature and the vector is a single 1.0.
        assertVector("ab", expect(0, 1.0f));
    }

    @Test
    @DisplayName("empty input hashes the empty feature, which is NOT the all-zero fallback")
    void emptyInput() {
        // Worth pinning precisely because it looks like it should hit the all-zero
        // guard and does not: the empty string still produces one feature, and it lands
        // in bucket 204 rather than bucket 0. If a future change routed empty input
        // through the fallback instead, this is the only test that would notice - and the
        // two are indistinguishable from the outside, since both yield a valid unit
        // vector.
        assertVector("", expect(204, 1.0f));
    }

    @Test
    @DisplayName("every vector is unit length, which vector_ip_ops depends on")
    void unitLength() {
        for (String text : new String[] {"hello world", "ab", "", "cat", "a much longer "
                + "prompt with several words in it"}) {
            float[] values = model.embed(text).toArray();
            double sumOfSquares = 0.0d;
            for (float value : values) {
                sumOfSquares += (double) value * value;
            }

            // The HNSW index is built with vector_ip_ops, where an inner product equals
            // cosine similarity ONLY for unit vectors. A vector that is not normalised
            // ranks wrongly against every other row rather than failing.
            assertThat(Math.sqrt(sumOfSquares))
                    .as("magnitude of \"%s\"", text)
                    .isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-5d));
        }
    }

    @Test
    @DisplayName("surface similarity behaves as documented: close for shared n-grams")
    void surfaceSimilarity() {
        Embedding hello = model.embed("hello world");
        Embedding helloBang = model.embed("hello world!");
        Embedding unrelated = model.embed("quarterly revenue reconciliation");

        // The documented contract: this model matches surface form, not meaning. Shared
        // trigrams score high; unrelated text scores near zero. That is what makes it a
        // usable test double for Tier 2 despite not being semantic.
        assertThat(hello.cosineSimilarity(helloBang)).isGreaterThan(0.8d);
        assertThat(hello.cosineSimilarity(unrelated)).isLessThan(0.3d);

        // And identical text is exactly 1, not approximately.
        assertThat(hello.cosineSimilarity(model.embed("hello world")))
                .isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-6d));
    }

    @Test
    @DisplayName("the model id is pinned, because Tier 2 stores it alongside every vector")
    void modelId() {
        // Tier 2 filters candidates by embedding_model, so this string is what keeps
        // vectors from two different models out of one another's search results.
        // Changing the algorithm REQUIRES changing this.
        assertThat(model.modelId()).isEqualTo("hashing-trigram-v1");
    }
}
