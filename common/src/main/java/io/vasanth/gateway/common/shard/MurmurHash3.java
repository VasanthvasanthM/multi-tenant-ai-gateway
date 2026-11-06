package io.vasanth.gateway.common.shard;

/**
 * MurmurHash3, 128-bit x64 variant, returning the low 64 bits.
 *
 * <p>Used to place keys and virtual nodes on the consistent-hash ring. It is chosen over
 * SHA-256 because ring placement happens on every request: MurmurHash3 costs tens of
 * nanoseconds against roughly a microsecond for a cryptographic digest, and the ring needs
 * uniformity, not collision resistance against an adversary.
 *
 * <p><b>On correctness testing.</b> Byte-for-byte compatibility with the reference C
 * implementation is not a requirement here - no other system needs to reproduce these
 * values, and the ring is rebuilt from the shard list at startup. What matters is that the
 * function is deterministic and distributes uniformly. The tests therefore assert
 * distribution quality over large samples rather than comparing against published vectors.
 *
 * <p>This is not a cryptographic hash and must never be used for anything security-bearing.
 * {@code CacheKey} uses SHA-256 for precisely that reason.
 */
public final class MurmurHash3 {

    private static final long C1 = 0x87c37b91114253d5L;
    private static final long C2 = 0x4cf5ad432745937fL;

    private static final long FMIX_1 = 0xff51afd7ed558ccdL;
    private static final long FMIX_2 = 0xc4ceb9fe1a85ec53L;

    private static final int BLOCK_BYTES = 16;

    private MurmurHash3() {
        // utility
    }

    /**
     * Hashes {@code data} with the given seed.
     *
     * @param data bytes to hash; not modified
     * @param seed arbitrary but must be identical across every node, or two gateways will
     *     route the same tenant to different shards
     * @return the low 64 bits of the 128-bit digest
     */
    public static long hash64(byte[] data, int seed) {
        final int length = data.length;
        final int blocks = length / BLOCK_BYTES;

        long h1 = seed & 0xffffffffL;
        long h2 = seed & 0xffffffffL;

        // Body: consume whole 16-byte blocks.
        for (int i = 0; i < blocks; i++) {
            final int base = i * BLOCK_BYTES;
            long k1 = littleEndianLong(data, base);
            long k2 = littleEndianLong(data, base + 8);

            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;

            h1 = Long.rotateLeft(h1, 27);
            h1 += h2;
            h1 = h1 * 5 + 0x52dce729L;

            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;

            h2 = Long.rotateLeft(h2, 31);
            h2 += h1;
            h2 = h2 * 5 + 0x38495ab5L;
        }

        // Tail: the reference implementation uses a fallthrough switch. An explicit loop is
        // equivalent and avoids a construct that every static analyser flags and that is
        // easy to break when edited.
        long k1 = 0L;
        long k2 = 0L;
        final int tailStart = blocks * BLOCK_BYTES;
        for (int i = 0; i < length - tailStart; i++) {
            final long b = data[tailStart + i] & 0xffL;
            if (i < 8) {
                k1 |= b << (8 * i);
            } else {
                k2 |= b << (8 * (i - 8));
            }
        }
        if (k1 != 0L) {
            k1 *= C1;
            k1 = Long.rotateLeft(k1, 31);
            k1 *= C2;
            h1 ^= k1;
        }
        if (k2 != 0L) {
            k2 *= C2;
            k2 = Long.rotateLeft(k2, 33);
            k2 *= C1;
            h2 ^= k2;
        }

        // Finalisation: mixes length in so that inputs differing only by trailing zero
        // bytes do not collide.
        h1 ^= length;
        h2 ^= length;
        h1 += h2;
        h2 += h1;
        h1 = fmix64(h1);
        h2 = fmix64(h2);
        h1 += h2;

        return h1;
    }

    /**
     * Reads eight bytes as a little-endian long.
     *
     * <p>The {@code & 0xffL} mask is what matters here, and it is not optional: a Java
     * {@code byte} is signed, so any value above 127 sign-extends to a negative long and
     * corrupts every subsequent bit operation. Masking against a {@code long} literal both
     * clears the sign extension and widens the operand, which is why no explicit
     * {@code (long)} cast is needed.
     */
    private static long littleEndianLong(byte[] data, int offset) {
        return (data[offset] & 0xffL)
                | ((data[offset + 1] & 0xffL) << 8)
                | ((data[offset + 2] & 0xffL) << 16)
                | ((data[offset + 3] & 0xffL) << 24)
                | ((data[offset + 4] & 0xffL) << 32)
                | ((data[offset + 5] & 0xffL) << 40)
                | ((data[offset + 6] & 0xffL) << 48)
                | ((data[offset + 7] & 0xffL) << 56);
    }

    /** Avalanche step: ensures a single input bit flip changes about half the output bits. */
    private static long fmix64(long input) {
        long k = input;
        k ^= k >>> 33;
        k *= FMIX_1;
        k ^= k >>> 33;
        k *= FMIX_2;
        k ^= k >>> 33;
        return k;
    }
}
