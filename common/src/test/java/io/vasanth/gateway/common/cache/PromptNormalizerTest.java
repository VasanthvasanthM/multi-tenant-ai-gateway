package io.vasanth.gateway.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unicode correctness tests.
 *
 * <p>Each case corresponds to a specific way a Latin-centric assumption produces a wrong
 * cache key: ASCII-only whitespace classes, UTF-16 length counting, locale-sensitive case
 * folding, and compatibility folding that changes mathematical meaning.
 *
 * <p><b>Why code points are written as {@code cp(0x00A0)} rather than as {@code \}{@code
 * u00A0} escapes.</b> Editors and formatting tools routinely rewrite a unicode escape into
 * the literal character it denotes. For a printing character that is harmless, but for
 * U+00A0, U+001C or U+2007 it leaves an invisible byte in the source: unreviewable in a
 * diff, and silently corrupted by the next tool that touches the file. An explicit hex code
 * point survives any amount of reformatting and states plainly which character is meant.
 */
class PromptNormalizerTest {

    /** Builds a single-code-point string. See the class javadoc for why. */
    private static String cp(int codePoint) {
        return Character.toString(codePoint);
    }

    @Nested
    @DisplayName("line endings")
    class LineEndings {

        @Test
        void crlfBecomesLf() {
            assertThat(PromptNormalizer.normalize("a\r\nb")).isEqualTo("a\nb");
        }

        @Test
        void loneCarriageReturnBecomesLf() {
            assertThat(PromptNormalizer.normalize("a\rb")).isEqualTo("a\nb");
        }

        @Test
        void mixedLineEndingsAreUnified() {
            assertThat(PromptNormalizer.normalize("a\r\nb\rc\nd")).isEqualTo("a\nb\nc\nd");
        }
    }

    @Nested
    @DisplayName("edge whitespace - Unicode aware")
    class EdgeWhitespace {

        /**
         * Java's {@code \s} matches none of the non-ASCII entries below, and
         * {@link String#trim()} only strips characters at or below U+0020. Without a
         * Unicode-aware class, the same prompt pasted from a word processor or typed on a
         * CJK keyboard would hash differently from its plain-ASCII twin.
         */
        @ParameterizedTest(name = "U+{0} {1} is stripped")
        @CsvSource({
            "00A0, NO-BREAK SPACE",
            "2007, FIGURE SPACE",
            "202F, NARROW NO-BREAK SPACE",
            "2003, EM SPACE",
            "3000, IDEOGRAPHIC SPACE",
            "2028, LINE SEPARATOR",
            "2029, PARAGRAPH SEPARATOR",
            "0009, CHARACTER TABULATION",
            "000B, LINE TABULATION",
            "000C, FORM FEED",
            "0085, NEXT LINE",
        })
        void unicodeWhitespaceIsStrippedFromEdges(String hex, String name) {
            String space = cp(Integer.parseInt(hex, 16));

            assertThat(PromptNormalizer.normalize(space + "hello" + space))
                    .as("%s (U+%s) should be stripped", name, hex)
                    .isEqualTo("hello");
        }

        /**
         * Pins a real divergence between three competing definitions of "whitespace".
         * U+001C to U+001F are category Cc, the information separators. Unicode's
         * {@code White_Space} property <em>excludes</em> them, while Java's
         * {@code Character.isWhitespace()} and Python's {@code str.isspace()} both
         * <em>include</em> them.
         *
         * <p>The normaliser follows the Unicode property, so these survive. That is the
         * defensible reading - they are control codes, not spacing - but it is surprising
         * enough to pin with a test, so that nobody "simplifies" the implementation to
         * {@code String.strip()} and silently changes every affected cache key.
         */
        @ParameterizedTest(name = "U+{0} is NOT Unicode whitespace")
        @CsvSource({"001C", "001D", "001E", "001F"})
        void c0InformationSeparatorsAreNotUnicodeWhitespace(String hex) {
            String sep = cp(Integer.parseInt(hex, 16));

            assertThat(PromptNormalizer.normalize(sep + "hello" + sep))
                    .isEqualTo(sep + "hello" + sep);
        }

        @Test
        void mixedEdgeWhitespaceIsStripped() {
            String padded = cp(0x3000) + " \t\nhello\n\t " + cp(0x00A0);

            assertThat(PromptNormalizer.normalize(padded)).isEqualTo("hello");
        }

        @Test
        void emptyStringIsUnchanged() {
            assertThat(PromptNormalizer.normalize("")).isEmpty();
        }

        @Test
        void allWhitespaceCollapsesToEmpty() {
            assertThat(PromptNormalizer.normalize("   \n\t  ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("internal content is preserved")
    class InternalPreservation {

        @Test
        void indentationSurvives() {
            String python = "def f():\n    return 1";

            assertThat(PromptNormalizer.normalize(python)).isEqualTo(python);
        }

        @Test
        void internalRunsOfSpacesSurvive() {
            assertThat(PromptNormalizer.normalize("a    b")).isEqualTo("a    b");
        }

        @Test
        void blankLinesInsideSurvive() {
            assertThat(PromptNormalizer.normalize("para one\n\npara two"))
                    .isEqualTo("para one\n\npara two");
        }

        @Test
        void caseIsPreserved() {
            // Case folding is locale-dependent - Turkish maps "I" to U+0131, not "i" - so a
            // lowercasing step would make cache keys depend on the server's default locale.
            assertThat(PromptNormalizer.normalize("Hello WORLD")).isEqualTo("Hello WORLD");
        }
    }

    @Nested
    @DisplayName("NFC canonical composition")
    class CanonicalComposition {

        @Test
        void decomposedAccentComposesToSingleCodePoint() {
            String decomposed = "e" + cp(0x0301);  // e + COMBINING ACUTE ACCENT
            String composed = cp(0x00E9);          // LATIN SMALL LETTER E WITH ACUTE

            assertThat(decomposed).hasSize(2);
            assertThat(composed).hasSize(1);
            assertThat(PromptNormalizer.normalize(decomposed))
                    .isEqualTo(composed)
                    .isEqualTo(PromptNormalizer.normalize(composed));
        }

        @Test
        void decomposedHangulComposes() {
            // HANGUL CHOSEONG KIYEOK + JUNGSEONG A + JONGSEONG KIYEOK -> single syllable
            String jamo = cp(0x1100) + cp(0x1161) + cp(0x11A8);

            assertThat(PromptNormalizer.normalize(jamo)).isEqualTo(cp(0xAC01));
        }

        @Test
        void resultIsAlwaysNfcNormalised() {
            String input = "cafe" + cp(0x0301) + " " + cp(0x1100) + cp(0x1161);

            assertThat(Normalizer.isNormalized(
                            PromptNormalizer.normalize(input), Normalizer.Form.NFC))
                    .isTrue();
        }

        /**
         * NFKC would rewrite U+00B2 (superscript two) to "2" and U+00BD to "1/2". In a
         * mathematical prompt those are different questions, so compatibility folding is
         * deliberately not applied.
         */
        @ParameterizedTest(name = "U+{0} survives NFC")
        @CsvSource({
            "00B2, SUPERSCRIPT TWO",
            "00BD, VULGAR FRACTION ONE HALF",
            "FB01, LATIN SMALL LIGATURE FI",
            "FF21, FULLWIDTH LATIN CAPITAL LETTER A",
        })
        void compatibilityCharactersAreNotFolded(String hex, String name) {
            String ch = cp(Integer.parseInt(hex, 16));

            assertThat(PromptNormalizer.normalize("x" + ch))
                    .as("%s must not be compatibility-folded", name)
                    .isEqualTo("x" + ch);
        }
    }

    @Nested
    @DisplayName("code point counting")
    class CodePointCounting {

        @Test
        void asciiCountsAsOnePerCharacter() {
            assertThat(PromptNormalizer.lengthInCodePoints("hello")).isEqualTo(5);
        }

        /**
         * {@link String#length()} returns 2 for an astral character because it is stored as
         * a surrogate pair. Enforcing a prompt-size limit with it would reject valid input
         * at roughly half the advertised limit.
         */
        @Test
        void astralCharactersCountAsOne() {
            String rocket = cp(0x1F680); // ROCKET

            assertThat(rocket.length()).isEqualTo(2);
            assertThat(PromptNormalizer.lengthInCodePoints(rocket)).isEqualTo(1);
        }

        @Test
        void mixedScriptTextCountsCorrectly() {
            // "hi " + two kana + " " + rocket
            String mixed = "hi " + cp(0x3053) + cp(0x3093) + " " + cp(0x1F680);

            // 3 ASCII + 2 kana + 1 space = 6 units, plus the rocket's surrogate pair = 8.
            // Seven code points. That gap is exactly what a naive length() check misses.
            assertThat(mixed.length()).isEqualTo(8);
            assertThat(PromptNormalizer.lengthInCodePoints(mixed)).isEqualTo(7);
        }

        @Test
        void emptyStringIsZero() {
            assertThat(PromptNormalizer.lengthInCodePoints("")).isZero();
        }
    }

    @Test
    void normalisationIsIdempotent() {
        String input = "  \r\n cafe" + cp(0x0301) + "  " + cp(0x3000);
        String once = PromptNormalizer.normalize(input);

        assertThat(PromptNormalizer.normalize(once)).isEqualTo(once);
    }
}
