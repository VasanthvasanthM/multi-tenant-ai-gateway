package io.vasanth.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.cache.CacheTier;
import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.llm.CompletionResponse;
import io.vasanth.gateway.common.llm.ProviderId;
import io.vasanth.gateway.common.llm.StreamUsage;
import io.vasanth.gateway.common.llm.TokenUsage;
import io.vasanth.gateway.common.model.ModelId;
import reactor.util.context.Context;

/**
 * Contracts of the value types every module passes around.
 *
 * <h2>Why these were untested</h2>
 *
 * <p>{@code CompletionResponse}, {@code CacheTier}, {@code StreamUsage} and all twelve
 * {@code GatewayError} variants had ZERO line coverage, which is how a 442-line module
 * sat at 69% against an 80% gate. They are the easiest classes in the codebase to leave
 * untested, because nothing in them looks like logic - and three of them carry promises
 * that reach outside this process:
 *
 * <ul>
 *   <li>{@code CacheTier.label()} and {@code GatewayError.code()} are WIRE values. A
 *       rename is a breaking API change for every client parsing them, and the compiler
 *       cannot see it, so the strings are pinned here rather than derived.
 *   <li>{@code asCacheHit} rewrites the token usage and the provider. Getting that wrong
 *       bills a tenant for completion tokens the cache never generated.
 * </ul>
 *
 * <p>Where an exact value is not part of the documented contract - which status each
 * error maps to, which are retryable - this asserts the INVARIANT instead of the value,
 * so the tests do not have to be edited every time a status is tuned.
 */
class ValueTypeContractsTest {

    private static final Instant CREATED = Instant.parse("2026-11-11T10:15:30Z");

    private static CompletionResponse upstreamResponse() {
        return new CompletionResponse(
                "the answer",
                ModelId.of("gpt-4o-mini"),
                new TokenUsage(11L, 4L),
                CacheTier.UPSTREAM,
                ProviderId.of("openai"),
                CREATED,
                Duration.ofMillis(800));
    }

    @Nested
    @DisplayName("CompletionResponse")
    class Response {

        @Test
        @DisplayName("every component is required, and latency may not be negative")
        void guards() {
            assertThatCode(ValueTypeContractsTest::upstreamResponse).doesNotThrowAnyException();

            assertThatThrownBy(() -> new CompletionResponse(
                    null, ModelId.of("gpt-4o-mini"), new TokenUsage(1L, 1L), CacheTier.UPSTREAM,
                    ProviderId.of("mock"), CREATED, Duration.ZERO))
                    .isInstanceOf(NullPointerException.class);

            // Zero is legal - a cache hit can be faster than the clock resolves - but a
            // negative latency means the arithmetic that produced it was wrong, and a
            // negative duration silently flips every comparison that reads it.
            assertThatCode(() -> new CompletionResponse(
                    "c", ModelId.of("gpt-4o-mini"), new TokenUsage(1L, 1L), CacheTier.UPSTREAM,
                    ProviderId.of("mock"), CREATED, Duration.ZERO))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> new CompletionResponse(
                    "c", ModelId.of("gpt-4o-mini"), new TokenUsage(1L, 1L), CacheTier.UPSTREAM,
                    ProviderId.of("mock"), CREATED, Duration.ofMillis(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("latency");
        }

        @Test
        @DisplayName("asCacheHit zeroes completion tokens and reattributes the provider")
        void asCacheHit() {
            CompletionResponse hit =
                    upstreamResponse().asCacheHit(CacheTier.TIER1_EXACT, Duration.ofMillis(3));

            // The billing-relevant half. The cache generated no completion tokens, so
            // charging the original 4 would bill the tenant for work nobody did; the
            // prompt tokens stay because the prompt was still processed to hash it.
            assertThat(hit.usage().completionTokens()).isZero();
            assertThat(hit.usage().promptTokens()).isEqualTo(11L);

            // CACHE, not the original provider: the response did not come from openai on
            // this request, and attributing it there would overstate provider traffic in
            // every cost report.
            assertThat(hit.provider()).isEqualTo(ProviderId.CACHE);

            assertThat(hit.servedBy()).isEqualTo(CacheTier.TIER1_EXACT);
            assertThat(hit.latency()).isEqualTo(Duration.ofMillis(3));

            // createdAt is deliberately the ORIGINAL instant, not now: it is what lets a
            // caller see how stale the cached content is.
            assertThat(hit.createdAt()).isEqualTo(CREATED);
            assertThat(hit.content()).isEqualTo("the answer");
            assertThat(hit.model()).isEqualTo(ModelId.of("gpt-4o-mini"));
        }

        @Test
        @DisplayName("wasCacheHit follows the tier, not the provider")
        void wasCacheHit() {
            assertThat(upstreamResponse().wasCacheHit()).isFalse();
            assertThat(upstreamResponse().asCacheHit(CacheTier.TIER1_EXACT, Duration.ZERO)
                    .wasCacheHit()).isTrue();
            assertThat(upstreamResponse().asCacheHit(CacheTier.TIER2_SEMANTIC, Duration.ZERO)
                    .wasCacheHit()).isTrue();
        }
    }

    @Nested
    @DisplayName("CacheTier")
    class Tier {

        @Test
        @DisplayName("labels are the pinned wire values")
        void labels() {
            // Pinned as literals on purpose. These appear in the X-Cache-Tier header and
            // in the JSON body, so deriving them from name() would let a refactor of the
            // enum rename a public API field with nothing failing.
            assertThat(CacheTier.TIER1_EXACT.label()).isEqualTo("tier1_exact");
            assertThat(CacheTier.TIER2_SEMANTIC.label()).isEqualTo("tier2_semantic");
            assertThat(CacheTier.UPSTREAM.label()).isEqualTo("upstream");
        }

        @Test
        @DisplayName("only the two cache tiers count as hits")
        void hits() {
            assertThat(CacheTier.TIER1_EXACT.isHit()).isTrue();
            assertThat(CacheTier.TIER2_SEMANTIC.isHit()).isTrue();

            // The one that matters: if UPSTREAM ever reported a hit, the hit ratio - the
            // number this product is sold on - would read 100%.
            assertThat(CacheTier.UPSTREAM.isHit()).isFalse();
        }

        @Test
        @DisplayName("every tier has a distinct label")
        void labelsAreDistinct() {
            assertThat(List.of(CacheTier.values()).stream().map(CacheTier::label).toList())
                    .doesNotHaveDuplicates()
                    .allSatisfy(label -> assertThat(label).isNotBlank());
        }
    }

    @Nested
    @DisplayName("StreamUsage")
    class Stream {

        @Test
        @DisplayName("records the total into the cell the subscriber supplied")
        void records() {
            AtomicLong cell = new AtomicLong();
            Context context = StreamUsage.withCell(cell);

            StreamUsage.record(context, 42L);

            assertThat(cell.get()).isEqualTo(42L);
        }

        @Test
        @DisplayName("a context with no cell is a no-op, not a failure")
        void noCell() {
            // This is the whole point of the design: a provider calls record() without
            // knowing whether anyone upstream wanted the figure, so the empty case has to
            // be silent rather than throw.
            assertThatCode(() -> StreamUsage.record(Context.empty(), 42L))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a non-positive total is ignored, leaving any earlier value intact")
        void nonPositiveIgnored() {
            AtomicLong cell = new AtomicLong(7L);
            Context context = StreamUsage.withCell(cell);

            StreamUsage.record(context, 0L);
            assertThat(cell.get()).isEqualTo(7L);

            StreamUsage.record(context, -1L);
            assertThat(cell.get()).isEqualTo(7L);
        }
    }

    @Nested
    @DisplayName("GatewayError variants")
    class Errors {

        private static List<GatewayError> all() {
            return List.of(
                    new GatewayError.Unauthenticated("no key"),
                    new GatewayError.Forbidden("not yours"),
                    new GatewayError.InvalidRequest("messages must not be empty"),
                    new GatewayError.PayloadTooLarge(5000, 4000),
                    new GatewayError.RateLimited(Duration.ofSeconds(2)),
                    new GatewayError.BudgetExhausted(1000L, 1200L),
                    new GatewayError.UpstreamUnavailable("connect refused"),
                    new GatewayError.UpstreamTimeout(Duration.ofMillis(30_000)),
                    new GatewayError.CircuitOpen("openai"),
                    new GatewayError.Overloaded(256),
                    new GatewayError.Internal("boom"));
        }

        @Test
        @DisplayName("codes are unique across every variant")
        void codesAreUnique() {
            // The invariant that makes code() usable by a client: two errors sharing a
            // code cannot be told apart, and the contract says a code is never renamed -
            // which is only meaningful if it identifies exactly one condition.
            assertThat(all().stream().map(GatewayError::code).toList())
                    .doesNotHaveDuplicates()
                    .allSatisfy(code -> assertThat(code)
                            .isNotBlank()
                            .matches("[a-z][a-z0-9_]*"));
        }

        @Test
        @DisplayName("every variant has a message and a 4xx or 5xx status")
        void messageAndStatus() {
            assertThat(all()).allSatisfy(error -> {
                assertThat(error.message()).as("message of %s", error.code()).isNotBlank();
                assertThat(error.httpStatus()).as("status of %s", error.code())
                        .isBetween(400, 599);
            });
        }

        @Test
        @DisplayName("a message never leaks a secret-shaped value")
        void messagesStayClean() {
            // The contract above message() says it must never carry a secret. These
            // variants are all constructed from operational detail, so the check is that
            // nothing echoes a credential-shaped string back to the caller.
            assertThat(all()).allSatisfy(error ->
                    assertThat(error.message().toLowerCase())
                            .as("message of %s", error.code())
                            .doesNotContain("sk-")
                            .doesNotContain("password")
                            .doesNotContain("bearer "));
        }

        @Test
        @DisplayName("UpstreamTimeout reports the elapsed time it was given")
        void timeoutDetail() {
            GatewayError.UpstreamTimeout timeout =
                    new GatewayError.UpstreamTimeout(Duration.ofMillis(30_000));

            assertThat(timeout.code()).isEqualTo("upstream_timeout");
            assertThat(timeout.httpStatus()).isEqualTo(504);
            // The number is the diagnostic: "did not respond" without it cannot be told
            // apart from a 1 ms timeout misconfiguration.
            assertThat(timeout.message()).contains("30000");
        }

        @Test
        @DisplayName("required components are not null")
        void guards() {
            assertThatThrownBy(() -> new GatewayError.UpstreamTimeout(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("retryable is a boolean every variant answers")
        void retryable() {
            // Asserted as an invariant rather than per-variant values, which are a tuning
            // decision: what matters is that a client can branch on it for every error,
            // and that the two sides are not all the same answer.
            assertThat(all().stream().map(GatewayError::retryable).distinct().toList())
                    .as("some errors must be retryable and some must not")
                    .hasSize(2);
        }
    }
}
