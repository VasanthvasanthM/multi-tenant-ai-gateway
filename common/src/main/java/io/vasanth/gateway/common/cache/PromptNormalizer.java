package io.vasanth.gateway.common.cache;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Canonicalises prompt text before it is hashed into a cache key.
 *
 * <p>Normalisation affects <em>only</em> the key. The original, unmodified text is what gets
 * sent upstream. Conflating the two would mean quietly altering the user's prompt, which is
 * never acceptable.
 *
 * <h2>Why this is deliberately conservative</h2>
 *
 * <p>Every normalisation step trades cache hits for the risk of merging two prompts that are
 * not actually the same question. The asymmetry matters: a missed hit costs money, while a
 * wrong merge returns an incorrect answer. So each step below is only applied where the two
 * inputs are genuinely the same text.
 *
 * <h3>NFC, not NFKC</h3>
 *
 * <p>NFC unifies sequences Unicode defines as <em>canonically equivalent</em> - "é" as a
 * single code point versus "e" followed by a combining acute. Those are the same character
 * by definition, so merging them is safe.
 *
 * <p>NFKC additionally applies <em>compatibility</em> folding, and that is not safe here. It
 * rewrites "x²" to "x2" and "½" to "1⁄2". In a mathematical prompt those are different
 * questions, and a cache that cannot tell them apart will confidently return the wrong
 * answer.
 *
 * <h3>Internal whitespace is preserved</h3>
 *
 * <p>The obvious optimisation - collapsing runs of whitespace - is wrong for this workload.
 * A large share of LLM prompts contain source code, where indentation is semantic. Collapsing
 * it would give these the same cache key:
 *
 * <pre>{@code
 * def f():
 *     return 1      // returns 1
 *
 * def f():
 * return 1          // IndentationError
 * }</pre>
 *
 * <p>Only leading and trailing whitespace is stripped, and line endings are normalised, since
 * those genuinely do not change meaning. Prompts that differ only in internal spacing are
 * left to the Tier-2 semantic cache, which is designed for exactly that kind of near-miss.
 *
 * <h3>Case is preserved</h3>
 *
 * <p>Models treat case as meaningful, and case folding is locale-dependent in ways that bite
 * silently - Turkish maps "I" to "ı", not "i". Lowercasing would make the cache key depend on
 * the server's default locale.
 */
public final class PromptNormalizer {

    /**
     * Unicode-aware whitespace class.
     *
     * <p>Java's {@code \s} is ASCII-only by default: it matches none of U+00A0 NO-BREAK SPACE,
     * U+2007 FIGURE SPACE or U+3000 IDEOGRAPHIC SPACE. A prompt pasted from a word processor
     * or typed in CJK would therefore not be stripped, and the same prompt would produce two
     * different cache keys depending on where it was copied from.
     */
    private static final Pattern UNICODE_WHITESPACE_EDGE =
            Pattern.compile("^[\\p{IsWhite_Space}]+|[\\p{IsWhite_Space}]+$");

    /** Matches CRLF and lone CR, both normalised to LF. */
    private static final Pattern LINE_ENDINGS = Pattern.compile("\\r\\n?");

    private PromptNormalizer() {
        // utility
    }

    /**
     * Returns the canonical form of {@code text} for cache-key purposes.
     *
     * @param text raw prompt text; must not be null
     * @return normalised text, never null
     */
    public static String normalize(String text) {
        if (text.isEmpty()) {
            return text;
        }

        // Order matters. Normalising line endings first means the subsequent edge-strip sees
        // a single line-ending form, and NFC last guarantees the returned value is composed
        // regardless of what the earlier steps produced.
        String result = LINE_ENDINGS.matcher(text).replaceAll("\n");
        result = UNICODE_WHITESPACE_EDGE.matcher(result).replaceAll("");

        if (!Normalizer.isNormalized(result, Normalizer.Form.NFC)) {
            result = Normalizer.normalize(result, Normalizer.Form.NFC);
        }
        return result;
    }

    /**
     * Counts the characters a human would perceive, for length limits and error messages.
     *
     * <p>{@link String#length()} returns UTF-16 code units, so it reports 2 for a single
     * emoji and 2 for most characters outside the Basic Multilingual Plane. Enforcing a
     * prompt-length limit with it would reject a valid Japanese or emoji-bearing prompt at
     * roughly half the advertised limit.
     *
     * <p>This still counts code points rather than grapheme clusters, so a flag emoji or an
     * accented character written in decomposed form counts as more than one. That is
     * acceptable for a size guard; it would not be for anything user-facing.
     */
    public static int lengthInCodePoints(String text) {
        return text.codePointCount(0, text.length());
    }
}
