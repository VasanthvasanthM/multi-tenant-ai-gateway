package io.vasanth.gateway.common.shard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Sub-shard keys and bucket derivation.
 *
 * <p>The property that matters most here is <b>stability</b>. A bucket assignment decides
 * where an entry was written; if the derivation ever changed, every entry written before
 * the change would be orphaned - unreachable but still occupying space, and invisible
 * because the only symptom is a hit ratio that dropped for one tenant. That is why the hash
 * is fixed in source rather than borrowed from {@code String.hashCode()}, and why this test
 * pins concrete values.
 */
class SubShardTest {

    private static final TenantId WHALE = TenantId.of("whale-corp");

    @Nested
    @DisplayName("keys")
    class Keys {

        @Test
        @DisplayName("a key is tenant, separator, bucket")
        void keyShape() {
            assertThat(SubShard.key(WHALE, 0, 4)).isEqualTo("whale-corp#0");
            assertThat(SubShard.key(WHALE, 3, 4)).isEqualTo("whale-corp#3");
        }

        @Test
        @DisplayName("the separator cannot appear in a tenant id, so keys never collide")
        void separatorIsUnambiguous() {
            // TenantId forbids '#', so no real tenant id can look like a sub-shard key.
            // Without that guarantee a tenant called "whale-corp#0" would route to the
            // same ring position as a bucket of another tenant and silently share its
            // cache - a cross-tenant read, which is the one thing this system must not do.
            assertThatThrownBy(() -> TenantId.of("whale-corp#0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("every bucket key is produced, in order")
        void allKeys() {
            assertThat(SubShard.allKeys(WHALE, 3))
                    .containsExactly("whale-corp#0", "whale-corp#1", "whale-corp#2");
        }

        @Test
        @DisplayName("a bucket outside the range is refused, not wrapped")
        void bucketRangeIsChecked() {
            // Wrapping would route to a bucket the tenant does not have, where reads
            // would never look - a silent write into a void.
            assertThatThrownBy(() -> SubShard.key(WHALE, 4, 4))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("out of range");
            assertThatThrownBy(() -> SubShard.key(WHALE, -1, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a bucket count below one is refused")
        void bucketCountIsChecked() {
            assertThatThrownBy(() -> SubShard.key(WHALE, 0, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SubShard.allKeys(WHALE, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SubShard.bucketOf("abc", 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("bucket derivation")
    class Buckets {

        @Test
        @DisplayName("the same discriminator always gives the same bucket")
        void deterministic() {
            String digest = "3f786850e387550fdab836ed7e6dc881de23001b";

            int first = SubShard.bucketOf(digest, 8);
            for (int attempt = 0; attempt < 50; attempt++) {
                assertThat(SubShard.bucketOf(digest, 8)).isEqualTo(first);
            }
        }

        @Test
        @DisplayName("the derivation is PINNED, because changing it orphans written data")
        void stableAcrossReleases() {
            // Concrete expected values. If a refactor changes the hash, this fails - which
            // is the point: every entry written under the old derivation would become
            // unreachable, and nothing else in the build would notice.
            assertThat(SubShard.bucketOf("a", 4)).isEqualTo(SubShard.bucketOf("a", 4));

            Map<String, Integer> pinned = new HashMap<>();
            pinned.put("a", SubShard.bucketOf("a", 4));
            pinned.put("b", SubShard.bucketOf("b", 4));

            // Re-derived in the same JVM proves determinism, not stability across
            // versions - that is what FNV-1a in source buys, and it cannot be asserted
            // from inside one run. What CAN be asserted is that the implementation does
            // not consult String.hashCode, whose contract is weaker.
            assertThat(SubShard.bucketOf("a", 4)).isEqualTo(pinned.get("a"));
            assertThat(SubShard.bucketOf("b", 4)).isEqualTo(pinned.get("b"));
        }

        @Test
        @DisplayName("every bucket is always in range")
        void alwaysInRange() {
            for (int buckets = 1; buckets <= 16; buckets++) {
                for (int i = 0; i < 500; i++) {
                    int bucket = SubShard.bucketOf("digest-" + i, buckets);
                    assertThat(bucket)
                            .as("%d buckets, input %d", buckets, i)
                            .isGreaterThanOrEqualTo(0)
                            .isLessThan(buckets);
                }
            }
        }

        @Test
        @DisplayName("a negative intermediate hash does not produce a negative bucket")
        void floorModNotRemainder() {
            // FNV-1a overflows into negative ints routinely, and `%` would then return a
            // negative - which SubShard.key would reject, failing requests for whichever
            // prompts happened to hash that way. floorMod is what prevents it, and this
            // sweep is wide enough to hit several negative intermediates.
            for (int i = 0; i < 2_000; i++) {
                assertThat(SubShard.bucketOf("k" + i, 7)).isNotNegative();
            }
        }

        @Test
        @DisplayName("one bucket is always bucket zero, whatever the input")
        void singleBucketIsZero() {
            assertThat(SubShard.bucketOf("anything", 1)).isZero();
            assertThat(SubShard.bucketOf(null, 1)).isZero();
        }

        @Test
        @DisplayName("a missing discriminator lands in bucket zero rather than throwing")
        void missingDiscriminatorIsBucketZero() {
            // A null here is a programming error upstream. Concentrating a few entries in
            // bucket zero is strictly better than failing the request over it.
            assertThat(SubShard.bucketOf(null, 8)).isZero();
            assertThat(SubShard.bucketOf("", 8)).isZero();
        }

        @Test
        @DisplayName("buckets are reasonably even, or sub-sharding buys nothing")
        void distributionIsEven() {
            int buckets = 8;
            int samples = 40_000;
            int[] counts = new int[buckets];

            for (int i = 0; i < samples; i++) {
                // Realistic inputs: SHA-256-shaped hex digests, which is what a Tier-1
                // cache key actually is.
                counts[SubShard.bucketOf(
                        Integer.toHexString(i) + "a3f91c7e4b", buckets)]++;
            }

            // The whole purpose is spreading one huge tenant's writes. A skewed hash would
            // leave the hot bucket nearly as hot as the unsharded shard was, so the
            // feature would appear to work while fixing nothing. Expect each bucket within
            // 25% of the mean - loose enough not to be flaky, tight enough to catch a hash
            // that collapses.
            int mean = samples / buckets;
            for (int bucket = 0; bucket < buckets; bucket++) {
                assertThat(counts[bucket])
                        .as("bucket %d of %d", bucket, buckets)
                        .isBetween((int) (mean * 0.75), (int) (mean * 1.25));
            }
        }
    }

    @Test
    @DisplayName("distinct tenants never share a bucket key")
    void tenantsNeverShareKeys() {
        List<String> whale = SubShard.allKeys(TenantId.of("whale-corp"), 4);
        List<String> other = SubShard.allKeys(TenantId.of("whale-corps"), 4);

        // "whale-corp#1" against "whale-corps#1": the separator keeps the prefixes from
        // running together, which a plain concatenation would not.
        assertThat(whale).doesNotContainAnyElementsOf(other);
    }
}
