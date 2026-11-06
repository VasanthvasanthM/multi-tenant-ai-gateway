package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ring needs determinism and uniformity, not reference compatibility, so these tests
 * assert statistical quality over large samples rather than comparing against published
 * vectors. See the class javadoc on {@link MurmurHash3}.
 */
class MurmurHash3Test {

    private static final int SEED = 0x9747_b28c;

    private static long hash(String value) {
        return MurmurHash3.hash64(value.getBytes(StandardCharsets.UTF_8), SEED);
    }

    @Test
    void isDeterministic() {
        long expected = hash("tenant-acme");

        for (int i = 0; i < 10_000; i++) {
            assertThat(hash("tenant-acme")).isEqualTo(expected);
        }
    }

    @Test
    void differentSeedsGiveDifferentResults() {
        byte[] data = "tenant-acme".getBytes(StandardCharsets.UTF_8);

        assertThat(MurmurHash3.hash64(data, 1)).isNotEqualTo(MurmurHash3.hash64(data, 2));
    }

    @Test
    void handlesEmptyInput() {
        assertThat(MurmurHash3.hash64(new byte[0], SEED))
                .isEqualTo(MurmurHash3.hash64(new byte[0], SEED));
    }

    @Test
    @DisplayName("every tail length from 0 to 31 bytes is handled")
    void handlesAllTailLengths() {
        // Exercises both the 16-byte block loop and every possible remainder, which is
        // where a hand-written tail is most likely to be wrong.
        Set<Long> seen = new HashSet<>();
        StringBuilder builder = new StringBuilder();

        for (int length = 0; length < 32; length++) {
            seen.add(hash(builder.toString()));
            builder.append('x');
        }

        assertThat(seen).as("inputs of differing length must not collide").hasSize(32);
    }

    @Test
    void trailingZeroBytesDoNotCollide() {
        // The finalisation step mixes the length in specifically to prevent this.
        long a = MurmurHash3.hash64(new byte[] {1, 0}, SEED);
        long b = MurmurHash3.hash64(new byte[] {1, 0, 0}, SEED);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("no collisions across 200k distinct inputs")
    void isCollisionFreeOverALargeSample() {
        Set<Long> hashes = new HashSet<>(300_000);
        for (int i = 0; i < 200_000; i++) {
            hashes.add(hash("tenant-" + i));
        }

        assertThat(hashes).hasSize(200_000);
    }

    @Test
    @DisplayName("output distributes evenly across 16 buckets")
    void distributesUniformly() {
        int buckets = 16;
        int samples = 160_000;
        int[] counts = new int[buckets];

        for (int i = 0; i < samples; i++) {
            long h = hash("tenant-" + i);
            counts[(int) Math.floorMod(h, buckets)]++;
        }

        double expected = (double) samples / buckets;
        for (int i = 0; i < buckets; i++) {
            assertThat(counts[i])
                    .as("bucket %d held %d, expected about %.0f", i, counts[i], expected)
                    .isBetween((int) (expected * 0.93), (int) (expected * 1.07));
        }
    }

    @Test
    @DisplayName("avalanche: one flipped input bit changes about half the output bits")
    void exhibitsAvalancheEffect() {
        int trials = 5_000;
        long totalFlipped = 0;

        for (int i = 0; i < trials; i++) {
            byte[] a = ("input-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] b = a.clone();
            b[0] ^= 0x01; // flip exactly one bit

            totalFlipped += Long.bitCount(
                    MurmurHash3.hash64(a, SEED) ^ MurmurHash3.hash64(b, SEED));
        }

        double averageFlipped = (double) totalFlipped / trials;
        assertThat(averageFlipped)
                .as("expected about 32 of 64 output bits to change, got %.1f", averageFlipped)
                .isBetween(29.0d, 35.0d);
    }
}
