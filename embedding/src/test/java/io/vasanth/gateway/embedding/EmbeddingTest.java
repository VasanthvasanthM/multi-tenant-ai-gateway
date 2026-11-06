package io.vasanth.gateway.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EmbeddingTest {

    /**
     * Tolerance for any value derived from float32 arithmetic.
     *
     * <p>Normalising through float32 and taking a dot product of 384 terms accumulates
     * about 3.4e-8 of error - the unit vector 1/sqrt(2) is representable only as
     * 0.7071067690849304, and squaring it twice does not return exactly 1.0. Asserting to
     * 1e-9 is therefore not a strict test, it is an impossible one.
     */
    private static final double FLOAT32_TOLERANCE = 1e-6d;

    private static float[] raw(float... values) {
        return values;
    }

    @Nested
    @DisplayName("normalisation invariant")
    class Normalisation {

        @Test
        void normalizingProducesUnitLength() {
            Embedding embedding = Embedding.normalizing(raw(3.0f, 4.0f));

            // 3-4-5 triangle: magnitude 5, so components become 0.6 and 0.8.
            assertThat(embedding.toArray()[0]).isCloseTo(0.6f, within(1e-6f));
            assertThat(embedding.toArray()[1]).isCloseTo(0.8f, within(1e-6f));
        }

        @Test
        void ofRejectsUnnormalisedInput() {
            // The HNSW index is built for inner product, which only equals cosine when the
            // vectors are unit length. Accepting a raw vector here would silently corrupt
            // every ranking the index produces.
            assertThatThrownBy(() -> Embedding.of(raw(3.0f, 4.0f)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("L2-normalised");
        }

        @Test
        void ofAcceptsAlreadyNormalisedInput() {
            assertThat(Embedding.of(raw(0.6f, 0.8f)).dimension()).isEqualTo(2);
        }

        @Test
        void zeroVectorIsRejected() {
            // A zero vector has no direction. Normalising it would yield NaNs that poison
            // every later similarity comparison without ever throwing.
            assertThatThrownBy(() -> Embedding.normalizing(raw(0.0f, 0.0f, 0.0f)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero-magnitude");
        }

        @Test
        void emptyVectorIsRejected() {
            assertThatThrownBy(() -> Embedding.normalizing(new float[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void normalisingIsIdempotent() {
            Embedding once = Embedding.normalizing(raw(1.0f, 2.0f, 3.0f));
            Embedding twice = Embedding.normalizing(once.toArray());

            assertThat(twice.cosineSimilarity(once)).isCloseTo(1.0d, within(FLOAT32_TOLERANCE));
        }
    }

    @Nested
    @DisplayName("cosine similarity")
    class Cosine {

        @Test
        void identicalVectorsScoreOne() {
            Embedding a = Embedding.normalizing(raw(1.0f, 2.0f, 3.0f));

            assertThat(a.cosineSimilarity(a)).isCloseTo(1.0d, within(FLOAT32_TOLERANCE));
        }

        @Test
        void orthogonalVectorsScoreZero() {
            Embedding a = Embedding.normalizing(raw(1.0f, 0.0f));
            Embedding b = Embedding.normalizing(raw(0.0f, 1.0f));

            assertThat(a.cosineSimilarity(b)).isCloseTo(0.0d, within(FLOAT32_TOLERANCE));
        }

        @Test
        void oppositeVectorsScoreMinusOne() {
            Embedding a = Embedding.normalizing(raw(1.0f, 1.0f));
            Embedding b = Embedding.normalizing(raw(-1.0f, -1.0f));

            assertThat(a.cosineSimilarity(b)).isCloseTo(-1.0d, within(FLOAT32_TOLERANCE));
        }

        @Test
        void isSymmetric() {
            Embedding a = Embedding.normalizing(raw(1.0f, 2.0f, 3.0f));
            Embedding b = Embedding.normalizing(raw(4.0f, -1.0f, 2.0f));

            assertThat(a.cosineSimilarity(b)).isCloseTo(b.cosineSimilarity(a), within(1e-12d));
        }

        @Test
        @DisplayName("never exceeds 1.0 despite float accumulation")
        void isClamped() {
            // Without clamping, summing 384 float products can drift just past 1.0 and
            // break any "similarity <= 1" assertion or exact-match threshold check.
            float[] values = new float[384];
            java.util.Arrays.fill(values, 1.0f);
            Embedding a = Embedding.normalizing(values);

            assertThat(a.cosineSimilarity(a)).isLessThanOrEqualTo(1.0d);
        }

        @Test
        void dimensionMismatchIsRejected() {
            Embedding a = Embedding.normalizing(raw(1.0f, 0.0f));
            Embedding b = Embedding.normalizing(raw(1.0f, 0.0f, 0.0f));

            assertThatThrownBy(() -> a.cosineSimilarity(b))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dimension mismatch");
        }
    }

    @Nested
    @DisplayName("pgvector interchange")
    class PgVector {

        @Test
        void serialisesWithoutSpaces() {
            // pgvector's parser rejects the spaces Arrays.toString would insert.
            String literal = Embedding.normalizing(raw(1.0f, 0.0f, 0.0f)).toPgVector();

            assertThat(literal).startsWith("[").endsWith("]").doesNotContain(" ");
        }

        @Test
        void roundTripsFaithfully() {
            Embedding original = Embedding.normalizing(raw(0.3f, -0.5f, 0.81f, 0.02f));
            Embedding parsed = Embedding.fromPgVector(original.toPgVector());

            assertThat(parsed.dimension()).isEqualTo(original.dimension());
            assertThat(parsed.cosineSimilarity(original)).isCloseTo(1.0d, within(1e-6d));
        }

        @Test
        void rejectsMalformedLiterals() {
            assertThatThrownBy(() -> Embedding.fromPgVector("1,2,3"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Embedding.fromPgVector("[]"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Embedding.fromPgVector(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("defensive copying")
    class Immutability {

        @Test
        void mutatingTheSourceArrayDoesNotAffectTheEmbedding() {
            float[] source = raw(0.6f, 0.8f);
            Embedding embedding = Embedding.of(source);

            source[0] = 99.0f;

            assertThat(embedding.toArray()[0]).isCloseTo(0.6f, within(1e-6f));
        }

        @Test
        void mutatingTheReturnedArrayDoesNotAffectTheEmbedding() {
            // An embedding is used as a cache lookup key; a caller mutating it after
            // indexing would leave a stored row that no longer matches its own text.
            Embedding embedding = Embedding.of(raw(0.6f, 0.8f));

            embedding.toArray()[0] = 99.0f;

            assertThat(embedding.toArray()[0]).isCloseTo(0.6f, within(1e-6f));
        }
    }

    @Test
    void equalityIsByValue() {
        assertThat(Embedding.of(raw(0.6f, 0.8f)))
                .isEqualTo(Embedding.of(raw(0.6f, 0.8f)))
                .hasSameHashCodeAs(Embedding.of(raw(0.6f, 0.8f)));
    }

    @Test
    void toStringDoesNotDumpTheWholeVector() {
        // 384 floats in a log line is unreadable and crowds out everything useful.
        assertThat(Embedding.of(raw(0.6f, 0.8f)).toString()).isEqualTo("Embedding[dim=2]");
    }
}
