package io.vasanth.gateway.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic and the guards in {@link Embedding}, pinned at their boundaries.
 *
 * <h2>Why exact values and exact edges</h2>
 *
 * <p>A mutation run left 50 survivors in this module, and the two largest groups were
 * {@code MathMutator} - an operator in a dot product or a normalisation swapped for
 * another - and {@code ConditionalsBoundary} / {@code RemoveConditional} on the validation
 * guards. Both groups survive the same way: tests that pass comfortably valid vectors and
 * assert "it did not throw" or "the result is about right".
 *
 * <p>Neither is a cosmetic gap. {@code cosineSimilarity} is the comparison Tier 2 ranks
 * every cache candidate by, so a sign flip or a swapped operator changes which cached
 * answer a user receives. And the normalisation guard is what the HNSW index's
 * correctness depends on: the index is built with {@code vector_ip_ops}, where an inner
 * product only equals cosine similarity while both vectors are unit length, so a vector
 * that slips past the check ranks wrongly against every other row.
 *
 * <p>So the similarity values here are hand-computable - 0, 1, -1 and cos(45 degrees) -
 * and each guard is asserted on BOTH sides of its edge.
 */
class EmbeddingArithmeticTest {

    /** A unit vector in the plane of the first two axes, at the given angle. */
    private static Embedding at(double radians) {
        float[] values = new float[Embedding.MINILM_DIMENSION];
        values[0] = (float) Math.cos(radians);
        values[1] = (float) Math.sin(radians);
        return Embedding.normalizing(values);
    }

    @Nested
    @DisplayName("cosineSimilarity")
    class Similarity {

        @Test
        @DisplayName("identical vectors score exactly 1")
        void identical() {
            assertThat(at(0.0d).cosineSimilarity(at(0.0d))).isCloseTo(1.0d, within());
        }

        @Test
        @DisplayName("orthogonal vectors score exactly 0")
        void orthogonal() {
            // The dot product of (1,0,...) and (0,1,...). Any mutation that turns the
            // accumulating + into a - or a * leaves this at 0 only by accident, and the
            // cases below close that off.
            assertThat(at(0.0d).cosineSimilarity(at(Math.PI / 2))).isCloseTo(0.0d, within());
        }

        @Test
        @DisplayName("opposite vectors score exactly -1")
        void opposite() {
            // The sign is the point. Tier 2 refuses anything below its threshold, so a
            // lost minus sign turns the least similar candidate into the best match.
            assertThat(at(0.0d).cosineSimilarity(at(Math.PI))).isCloseTo(-1.0d, within());
        }

        @Test
        @DisplayName("a 45 degree separation scores cos(45), not something near it")
        void fortyFiveDegrees() {
            // An intermediate value, because 0, 1 and -1 are each reachable by more than
            // one wrong implementation. 0.70710678 is reachable by only the right one.
            assertThat(at(0.0d).cosineSimilarity(at(Math.PI / 4)))
                    .isCloseTo(Math.cos(Math.PI / 4), within());
        }

        @Test
        @DisplayName("similarity is symmetric")
        void symmetric() {
            double forwards = at(0.3d).cosineSimilarity(at(1.1d));
            double backwards = at(1.1d).cosineSimilarity(at(0.3d));

            assertThat(forwards).isCloseTo(backwards, within());
        }

        @Test
        @DisplayName("comparing different dimensions is refused, not silently truncated")
        void dimensionMismatch() {
            Embedding small = Embedding.normalizing(new float[] {1.0f, 0.0f});

            // Truncating to the shorter vector would return a plausible number from two
            // incomparable models - exactly the kind of answer nobody checks.
            assertThatThrownBy(() -> small.cosineSimilarity(at(0.0d)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static org.assertj.core.data.Offset<Double> within() {
            // Tight enough that an operator swap cannot hide inside it; loose enough for
            // float-to-double widening of cos/sin.
            return org.assertj.core.data.Offset.offset(1e-5d);
        }
    }

    @Nested
    @DisplayName("of - the normalisation guard")
    class Of {

        @Test
        @DisplayName("an exactly unit vector is accepted")
        void unitAccepted() {
            assertThatCode(() -> Embedding.of(new float[] {1.0f, 0.0f}))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("just inside the tolerance is accepted, just outside is refused")
        void toleranceEdges() {
            // NORM_TOLERANCE is 1e-4. Passing 1.0 proves nothing about where the edge is,
            // and a mutant moving > to >= or widening the comparison survives any test
            // that only uses obviously-valid input.
            assertThatCode(() -> Embedding.of(new float[] {1.00005f, 0.0f}))
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> Embedding.of(new float[] {1.01f, 0.0f}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("L2-normalised");

            // Both sides: too short as well as too long.
            assertThatThrownBy(() -> Embedding.of(new float[] {0.5f, 0.0f}))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an empty vector is refused")
        void emptyRefused() {
            assertThatThrownBy(() -> Embedding.of(new float[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("toArray returns a copy, so the instance cannot be mutated")
        void toArrayIsACopy() {
            Embedding embedding = Embedding.of(new float[] {1.0f, 0.0f});

            float[] first = embedding.toArray();
            first[0] = 99.0f;

            // Handing out the live array would let a caller un-normalise a vector that
            // has already passed the guard, which is the one state the class exists to
            // make impossible.
            assertThat(embedding.toArray()[0]).isEqualTo(1.0f);
        }
    }

    @Nested
    @DisplayName("normalizing")
    class Normalizing {

        @Test
        @DisplayName("a raw vector comes back unit length")
        void normalises() {
            Embedding embedding = Embedding.normalizing(new float[] {3.0f, 4.0f});

            // 3-4-5 triangle: the magnitude is 5, so the components must be 0.6 and 0.8.
            // Asserting the components rather than just the norm pins the division.
            assertThat(embedding.toArray()[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(1e-5f));
            assertThat(embedding.toArray()[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(1e-5f));
        }

        @Test
        @DisplayName("an already-normalised vector is unchanged")
        void idempotent() {
            Embedding once = Embedding.normalizing(new float[] {3.0f, 4.0f});
            Embedding twice = Embedding.normalizing(once.toArray());

            assertThat(twice.toArray()).containsExactly(once.toArray());
        }

        @Test
        @DisplayName("a zero vector is refused rather than producing NaN")
        void zeroRefused() {
            // Dividing by a zero magnitude yields NaN in every component, and NaN
            // compares false against everything - so the vector would be stored and then
            // never match anything again, silently.
            assertThatThrownBy(() -> Embedding.normalizing(new float[] {0.0f, 0.0f}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zero-magnitude");
        }

        @Test
        @DisplayName("a tiny but non-zero vector still normalises")
        void tinyButNonZero() {
            // The guard is magnitude < 1e-12, so this is the other side of that edge: a
            // vector small enough to look like noise must still be usable, or a mutant
            // widening the guard would start rejecting valid input.
            assertThatCode(() -> Embedding.normalizing(new float[] {1e-6f, 0.0f}))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an empty vector is refused")
        void emptyRefused() {
            assertThatThrownBy(() -> Embedding.normalizing(new float[0]))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("dimension reports the vector length")
        void dimension() {
            assertThat(at(0.0d).dimension()).isEqualTo(Embedding.MINILM_DIMENSION);
            assertThat(Embedding.normalizing(new float[] {1.0f, 0.0f}).dimension()).isEqualTo(2);
        }
    }
}
