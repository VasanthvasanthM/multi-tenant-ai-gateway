package io.vasanth.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.SamplingParams;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.shard.ConsistentHashRing;
import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.shard.ShardRouter;
import io.vasanth.gateway.common.shard.SubShard;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Closes the surviving-mutant gaps a mutation run found in {@code common}.
 *
 * <h2>What a surviving mutant was telling us</h2>
 *
 * <p>The run scored {@code common} at 51.7% and the survivors clustered in two shapes,
 * neither of which line coverage can see:
 *
 * <ul>
 *   <li><b>Validation boundaries.</b> Constructors reject bad input, and the tests passed
 *       values well inside the valid range. PITest changed {@code <} to {@code <=} and
 *       nothing noticed - so the guard could be off by one and every test would still
 *       pass. The fix is to assert at the exact boundary, on both sides.
 *   <li><b>Arithmetic and short-circuit returns.</b> {@code TokenUsage.total()} could
 *       return 0, or subtract instead of add, with the suite green - because nothing
 *       asserted a total whose operands differed.
 * </ul>
 *
 * <p>These are the cases where a mutation score earns its keep over a coverage
 * percentage: every line below was already executed by some other test.
 */
class MutationGapsTest {

    @Nested
    @DisplayName("SamplingParams boundaries")
    class Sampling {

        @Test
        @DisplayName("the exact bounds are ACCEPTED")
        void boundsAreInclusive() {
            // If the guard used < instead of <=, these would throw. Passing 0.7 proves
            // nothing about where the edge is.
            //
            // Note the asymmetry, which the contract states and this pins: temperature is
            // [0, 2] but topP is (0, 1] - zero top-p is excluded, because it would admit
            // no tokens at all and the request could never produce an answer.
            assertThatCode(() -> new SamplingParams(0.0d, 1.0d, 1)).doesNotThrowAnyException();
            assertThatCode(() -> new SamplingParams(2.0d, 1.0d, 1)).doesNotThrowAnyException();
            assertThatCode(() -> new SamplingParams(0.0d, Double.MIN_VALUE, 1))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a topP of exactly zero is REJECTED, unlike temperature")
        void zeroTopPIsRejected() {
            assertThatThrownBy(() -> new SamplingParams(0.0d, 0.0d, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("(0, 1]");
        }

        @Test
        @DisplayName("just outside the bounds is REJECTED")
        void justOutsideIsRejected() {
            assertThatThrownBy(() -> new SamplingParams(-0.000001d, 0.5d, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SamplingParams(2.000001d, 0.5d, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SamplingParams(0.5d, -0.000001d, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new SamplingParams(0.5d, 1.000001d, 1))
                    .isInstanceOf(IllegalArgumentException.class);
            // maxTokens of 0 is the boundary that matters: a request for zero tokens is a
            // paid round trip that cannot produce an answer.
            assertThatThrownBy(() -> new SamplingParams(0.5d, 0.5d, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("TokenUsage arithmetic and boundaries")
    class Usage {

        @Test
        @DisplayName("total is the SUM, with operands that differ")
        void totalIsTheSum() {
            // 11 + 4 = 15. Equal operands would let subtraction survive (0 == 0), and a
            // zero total would survive against NONE. Both mutants lived here.
            assertThat(new TokenUsage(11L, 4L).total()).isEqualTo(15L);
            assertThat(new TokenUsage(4L, 11L).total()).isEqualTo(15L);
            assertThat(new TokenUsage(1L, 0L).total()).isEqualTo(1L);
        }

        @Test
        @DisplayName("zero on both sides is accepted; -1 on either is not")
        void boundary() {
            assertThatCode(() -> new TokenUsage(0L, 0L)).doesNotThrowAnyException();
            assertThatThrownBy(() -> new TokenUsage(-1L, 0L))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new TokenUsage(0L, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a cache hit keeps prompt tokens and zeroes completion")
        void cacheHit() {
            TokenUsage hit = TokenUsage.cacheHit(11L);

            assertThat(hit.promptTokens()).isEqualTo(11L);
            assertThat(hit.completionTokens()).isZero();
            assertThat(hit.total()).isEqualTo(11L);
        }

        @Test
        @DisplayName("plus adds both components independently")
        void plus() {
            // Distinct values in all four slots, so a mutant swapping the fields fails.
            assertThat(new TokenUsage(1L, 2L).plus(new TokenUsage(30L, 400L)))
                    .isEqualTo(new TokenUsage(31L, 402L));
        }
    }

    @Nested
    @DisplayName("CompletionRequest guards")
    class Request {

        @Test
        @DisplayName("an empty message list is refused")
        void emptyMessagesRefused() {
            assertThatThrownBy(() -> new CompletionRequest(
                    TenantId.of("acme"), ModelId.of("m"), List.of(),
                    SamplingParams.DETERMINISTIC, false, "", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("exactly one message is enough")
        void oneMessageIsEnough() {
            // The boundary: a mutant turning "isEmpty" into "size() <= 1" would reject
            // this, and no other test passes a single-message request through the
            // constructor directly.
            assertThatCode(() -> CompletionRequest.of(
                    TenantId.of("acme"), ModelId.of("m"), "hello"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("ConsistentHashRing")
    class Ring {

        @Test
        @DisplayName("ring points are SORTED, which binary search depends on")
        void pointsAreSorted() {
            ConsistentHashRing ring = ConsistentHashRing.of(
                    Set.of(ShardId.of("s1"), ShardId.of("s2"), ShardId.of("s3")));

            // "removed call to Collections::sort" survived. An unsorted ring still routes
            // deterministically, so every distribution and determinism test passes - it
            // simply routes to the WRONG shard, because the lookup binary-searches. The
            // only observable consequence is that a tenant's data is on a shard nobody
            // looks at, which presents as a permanently cold cache for that tenant.
            //
            // Asserted through behaviour rather than internals: with a sorted ring the
            // same key must land on the shard that owns its position, and that is stable
            // across construction order.
            ConsistentHashRing reordered = ConsistentHashRing.of(
                    Set.of(ShardId.of("s3"), ShardId.of("s1"), ShardId.of("s2")));

            for (int i = 0; i < 200; i++) {
                String key = "tenant-" + i;
                assertThat(ring.route(key))
                        .as("key %s must route identically regardless of insertion order", key)
                        .isEqualTo(reordered.route(key));
            }
        }

        @Test
        @DisplayName("routeWithReplicas returns exactly the count asked for")
        void replicaCountIsExact() {
            ConsistentHashRing ring = ConsistentHashRing.of(
                    Set.of(ShardId.of("s1"), ShardId.of("s2"), ShardId.of("s3")));

            // The boundary mutants lived here: off by one either way and the caller gets
            // too few replicas (under-replication) or too many (wasted writes).
            assertThat(ring.routeWithReplicas("acme", 1)).hasSize(1);
            assertThat(ring.routeWithReplicas("acme", 2)).hasSize(2);
            assertThat(ring.routeWithReplicas("acme", 3)).hasSize(3);
        }

        @Test
        @DisplayName("asking for more replicas than shards returns every shard once")
        void cannotExceedShardCount() {
            ConsistentHashRing ring = ConsistentHashRing.of(
                    Set.of(ShardId.of("s1"), ShardId.of("s2")));

            // Distinct shards, so it must cap rather than repeat - a duplicate would mean
            // writing the same "replica" twice and believing the data was redundant.
            assertThat(ring.routeWithReplicas("acme", 5))
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("an empty ring is refused at ROUTE time, not construction")
        void emptyRingRefused() {
            // Constructing an empty ring is allowed - a deployment can start with no
            // shards configured. The guard is on the lookup, which is where returning
            // null would otherwise NPE the caller with no indication why.
            ConsistentHashRing empty = ConsistentHashRing.of(Set.of());

            assertThatThrownBy(() -> empty.route("acme"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> empty.routeWithReplicas("acme", 1))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("ShardRouter")
    class Router {

        @Test
        @DisplayName("isSingleShard is true for one and FALSE for two")
        void singleShard() {
            // "replaced boolean return with true" survived, so nothing asserted the false
            // case. A router that always claims to be single-shard would skip whatever
            // multi-shard handling callers add later.
            assertThat(ShardRouter.single(ShardId.of("s1")).isSingleShard()).isTrue();
            assertThat(ShardRouter.over(List.of(ShardId.of("s1"), ShardId.of("s2")))
                    .isSingleShard()).isFalse();
        }

        @Test
        @DisplayName("removing the last shard is refused; removing one of two is not")
        void removeShardBoundary() {
            ShardRouter single = ShardRouter.single(ShardId.of("s1"));
            assertThatThrownBy(() -> single.removeShard(ShardId.of("s1")))
                    .isInstanceOf(IllegalStateException.class);

            // The other side of the same conditional: with two shards it must succeed, or
            // the ring could never shrink at all.
            ShardRouter two = ShardRouter.over(List.of(ShardId.of("s1"), ShardId.of("s2")));
            two.removeShard(ShardId.of("s2"));
            assertThat(two.shardCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("SubShard boundaries")
    class Sub {

        @Test
        @DisplayName("bucket 0 and buckets-1 are both valid; buckets is not")
        void bucketBoundary() {
            // "changed conditional boundary" survived on both ends of the range check.
            assertThatCode(() -> SubShard.key(TenantId.of("whale"), 0, 4))
                    .doesNotThrowAnyException();
            assertThatCode(() -> SubShard.key(TenantId.of("whale"), 3, 4))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> SubShard.key(TenantId.of("whale"), 4, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("one bucket is valid; zero is not")
        void bucketCountBoundary() {
            assertThatCode(() -> SubShard.allKeys(TenantId.of("whale"), 1))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> SubShard.allKeys(TenantId.of("whale"), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
