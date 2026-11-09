package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Known-answer tests pinning every byte of {@link MurmurHash3#hash64}.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>A mutation run found <b>51 surviving mutants</b> in {@code MurmurHash3}: the existing
 * tests exercised it thoroughly for <em>properties</em> - determinism, distribution,
 * avalanche - and pinned none of its actual outputs. Every constant, shift and mix step
 * could therefore be changed and the whole suite would still pass.
 *
 * <p>That is not a cosmetic gap. This function decides <b>which shard every tenant lands
 * on</b>. A silent change to the arithmetic remaps every tenant at once, which orphans
 * their entire Tier-2 cache: the rows are still there, nothing errors, and the hit ratio
 * collapses for reasons no log explains. It is the same stability requirement
 * {@link SubShard} has, with a far wider blast radius and - until now - none of the
 * protection.
 *
 * <h2>What these vectors are, and are not</h2>
 *
 * <p>They were generated from this implementation and pin <b>stability</b>, not conformance
 * to the reference MurmurHash3 specification. That is the property the system actually
 * needs: the seed comment says the value is "arbitrary but must be identical across every
 * node", so what matters is that the mapping never moves, not that it matches a published
 * vector. If the implementation were ever replaced with a canonical one, these values
 * change and every cache entry written before the change is orphaned - which is exactly
 * the migration this test forces someone to think about rather than discover.
 */
class MurmurHash3KnownAnswerTest {

    @ParameterizedTest(name = "seed {0}, \"{1}\"")
    @CsvSource(delimiter = ',', value = {
            "0,,0",
            "0,a,-8839064797231613815",
            "0,ab,-7815133031266706642",
            "0,abc,-5434086359492102041",
            "0,abcd,-5153323217664422577",
            "0,abcde,2321271983248423864",
            "0,abcdef,-1982280103179862187",
            "0,abcdefg,-6427428730009885543",
            "0,abcdefgh,-3708139591217214462",
            "0,hello world,5998619086395760910",
            "0,acme,3961280000524817226",
            "0,globex,869956143073419513",
            "0,whale-corp#0,-4249804228516525346",
            "0,whale-corp#1,5239025279866509871",
            "0,tenant:acme,572295870699354340",
            "0,The quick brown fox jumps over the lazy dog,-2068352364225029268",
            "1,,5048724184180415669",
            "1,a,5182201742351716208",
            "1,ab,-7822495797836671860",
            "1,abc,-7167269562150657552",
            "1,abcd,6030581221757532747",
            "1,abcde,3259575753454099899",
            "1,abcdef,-7534367239278780987",
            "1,abcdefg,3215762674943488611",
            "1,abcdefgh,1639468528556552526",
            "1,hello world,-3346660118948336160",
            "1,acme,1177392628438331210",
            "1,globex,7936186255314692413",
            "1,whale-corp#0,3422902856766682632",
            "1,whale-corp#1,263122609743975835",
            "1,tenant:acme,7070949520074996795",
            "1,The quick brown fox jumps over the lazy dog,-1931104785909292738",
            "-1756908916,,4119422061735754675",
            "-1756908916,a,6694838689256856093",
            "-1756908916,ab,-8920242534676682741",
            "-1756908916,abc,3982135406228655836",
            "-1756908916,abcd,5310993687375067025",
            "-1756908916,abcde,-8991628186561541963",
            "-1756908916,abcdef,8799301533841076235",
            "-1756908916,abcdefg,-6967842704277260846",
            "-1756908916,abcdefgh,-8155006614711818242",
            "-1756908916,hello world,2847908598898625521",
            "-1756908916,acme,-4485103038650495960",
            "-1756908916,globex,-2445188958398645410",
            "-1756908916,whale-corp#0,1815762041690041905",
            "-1756908916,whale-corp#1,3597893457423699838",
            "-1756908916,tenant:acme,2675075989491351755",
            "-1756908916,The quick brown fox jumps over the lazy dog,8325606756057297185",
            "104729,,-7122613646888064702",
            "104729,a,3702349557448790408",
            "104729,ab,8790855041734910294",
            "104729,abc,-8302830256874342818",
            "104729,abcd,3291415483613967444",
            "104729,abcde,-8347375753862846636",
            "104729,abcdef,-7282194572728468364",
            "104729,abcdefg,-9073644070686348014",
            "104729,abcdefgh,-3899774358444468009",
            "104729,hello world,-4798133121895066616",
            "104729,acme,-5158466783067068539",
            "104729,globex,-4688254785556122066",
            "104729,whale-corp#0,-1790513101551962137",
            "104729,whale-corp#1,834742106954515803",
            "104729,tenant:acme,4446341755314398958",
            "104729,The quick brown fox jumps over the lazy dog,-1779949751512360504",
    })
    @DisplayName("every seed and input hashes to its pinned value")
    void pinnedVectors(int seed, String text, long expected) {
        byte[] data = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);

        assertThat(MurmurHash3.hash64(data, seed)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a 1 KiB input hashes to its pinned value")
    void longInputIsPinned() {
        // Exercises the full-block loop repeatedly rather than only the tail, which is
        // where most of the surviving mutants lived: the short inputs above never run the
        // body more than a handful of times.
        byte[] data = new byte[1024];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31);
        }

        assertThat(MurmurHash3.hash64(data, 0)).isEqualTo(-2405744053524595184L);
    }

    @Test
    @DisplayName("the empty input is not zero for a non-zero seed")
    void emptyInputMixesTheSeed() {
        // A finaliser that ignored the seed would return the same value for every seed on
        // empty input, and the distribution tests would not notice.
        assertThat(MurmurHash3.hash64(new byte[0], 0))
                .isNotEqualTo(MurmurHash3.hash64(new byte[0], 1));
    }

    @Test
    @DisplayName("one flipped bit changes the hash")
    void singleBitFlipChanges() {
        byte[] a = "tenant:acme".getBytes(StandardCharsets.UTF_8);
        byte[] b = a.clone();
        b[0] ^= 0x01;

        assertThat(MurmurHash3.hash64(a, 0)).isNotEqualTo(MurmurHash3.hash64(b, 0));
    }
}
