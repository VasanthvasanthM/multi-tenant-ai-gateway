package io.vasanth.gateway.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the deterministic lexical fallback.
 *
 * <p>These assert what it genuinely provides - determinism and surface-form similarity -
 * and explicitly pin what it does NOT provide, so nobody mistakes it for a semantic model
 * and promotes it to production.
 */
class HashingEmbeddingModelTest {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel();

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void sameTextAlwaysProducesTheSameVector() {
            Embedding first = model.embed("what is a semantic cache?");

            for (int i = 0; i < 500; i++) {
                assertThat(model.embed("what is a semantic cache?")).isEqualTo(first);
            }
        }

        @Test
        void outputIsAlwaysUnitLength() {
            for (String text : List.of("a", "ab", "hello world", "", "   ", "x".repeat(5000))) {
                Embedding embedding = model.embed(text);
                assertThat(embedding.cosineSimilarity(embedding)).isCloseTo(1.0d, within());
            }
        }

        @Test
        void dimensionMatchesTheRealModel() {
            // Must equal the vector(n) column width, or swapping models breaks every insert.
            assertThat(model.dimension()).isEqualTo(Embedding.MINILM_DIMENSION);
        }

        private static org.assertj.core.data.Offset<Double> within() {
            return org.assertj.core.data.Offset.offset(1e-6d);
        }
    }

    @Nested
    @DisplayName("lexical similarity - what it genuinely captures")
    class LexicalSimilarity {

        @Test
        void nearIdenticalTextScoresHigh() {
            double score = model.embed("hello world").cosineSimilarity(model.embed("hello world!"));

            assertThat(score).isGreaterThan(0.7d);
        }

        @Test
        void unrelatedTextScoresLow() {
            double score = model.embed("hello world")
                    .cosineSimilarity(model.embed("quantum chromodynamics lagrangian"));

            assertThat(score).isLessThan(0.3d);
        }

        @Test
        void identicalTextScoresExactlyOne() {
            double score = model.embed("same").cosineSimilarity(model.embed("same"));

            assertThat(score).isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-6d));
        }

        @Test
        void caseIsFoldedWithRootLocale() {
            // Locale.ROOT, not the default: in a Turkish locale "I" lowercases to a dotless
            // i, and the same text would embed differently depending on the server's locale.
            assertThat(model.embed("HELLO")).isEqualTo(model.embed("hello"));
            assertThat(model.embed("Istanbul".toLowerCase(Locale.ROOT)))
                    .isEqualTo(model.embed("istanbul"));
        }
    }

    @Nested
    @DisplayName("what it explicitly does NOT capture")
    class NotSemantic {

        @Test
        void synonymsAreNotRecognised() {
            // Pinned deliberately. The real model places these close together; this one
            // cannot, because they share no character trigrams. If this test ever starts
            // failing, someone has swapped the implementation and Tier 2's safety
            // assumptions need rechecking.
            double score = model.embed("car").cosineSimilarity(model.embed("automobile"));

            assertThat(score)
                    .as("the hashing model is lexical, not semantic - it must not be "
                            + "mistaken for the real thing")
                    .isLessThan(0.5d);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        void emptyTextProducesAValidVector() {
            assertThat(model.embed("").dimension()).isEqualTo(Embedding.MINILM_DIMENSION);
        }

        @Test
        void textShorterThanOneTrigramWorks() {
            assertThat(model.embed("a")).isNotNull();
            assertThat(model.embed("ab")).isNotNull();
        }

        @Test
        void astralCharactersAreHandledAsSingleUnits() {
            // Operating on code points rather than chars: a surrogate pair must not be
            // split into two meaningless half-characters.
            Embedding rocket = model.embed(Character.toString(0x1F680).repeat(5));

            assertThat(rocket.dimension()).isEqualTo(Embedding.MINILM_DIMENSION);
            assertThat(rocket.cosineSimilarity(rocket)).isCloseTo(
                    1.0d, org.assertj.core.data.Offset.offset(1e-6d));
        }

        @Test
        void batchMatchesIndividualCalls() {
            List<String> texts = List.of("alpha", "beta", "gamma");
            List<Embedding> batch = model.embedBatch(texts);

            assertThat(batch).hasSize(3);
            for (int i = 0; i < texts.size(); i++) {
                assertThat(batch.get(i)).isEqualTo(model.embed(texts.get(i)));
            }
        }
    }
}
