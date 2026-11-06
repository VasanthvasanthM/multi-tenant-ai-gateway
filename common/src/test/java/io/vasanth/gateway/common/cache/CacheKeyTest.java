package io.vasanth.gateway.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.llm.CompletionRequest;
import io.vasanth.gateway.common.llm.Message;
import io.vasanth.gateway.common.llm.SamplingParams;
import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.tenant.TenantId;

class CacheKeyTest {

    private static final TenantId ACME = TenantId.of("acme");
    private static final ModelId MODEL = ModelId.of("llama-3.1-8b");

    private static CompletionRequest request(
            TenantId tenant, ModelId model, SamplingParams sampling, String... prompts) {
        List<Message> messages = List.of(prompts).stream().map(Message::user).toList();
        return new CompletionRequest(tenant, model, messages, sampling, false, "", "");
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        void identicalRequestsProduceIdenticalKeys() {
            CompletionRequest a = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello");
            CompletionRequest b = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello");

            assertThat(CacheKey.derive(a)).isEqualTo(CacheKey.derive(b));
        }

        @Test
        void keyIsStableAcrossManyDerivations() {
            CompletionRequest req = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello");
            CacheKey first = CacheKey.derive(req);

            for (int i = 0; i < 1_000; i++) {
                assertThat(CacheKey.derive(req)).isEqualTo(first);
            }
        }

        @Test
        void digestIsSha256Shaped() {
            CacheKey key = CacheKey.derive(
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello"));

            assertThat(key.hex()).hasSize(64).matches("[0-9a-f]{64}");
        }
    }

    @Nested
    @DisplayName("every output-affecting field changes the key")
    class FieldSensitivity {

        private final CompletionRequest base =
                request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello");

        @Test
        void tenantChangesKey() {
            CompletionRequest other =
                    request(TenantId.of("globex"), MODEL, SamplingParams.DETERMINISTIC, "hello");

            assertThat(CacheKey.derive(other)).isNotEqualTo(CacheKey.derive(base));
        }

        @Test
        void modelChangesKey() {
            CompletionRequest other = request(
                    ACME, ModelId.of("llama-3.1-70b"), SamplingParams.DETERMINISTIC, "hello");

            assertThat(CacheKey.derive(other)).isNotEqualTo(CacheKey.derive(base));
        }

        @Test
        void temperatureChangesKey() {
            CompletionRequest other =
                    request(ACME, MODEL, new SamplingParams(0.7d, 1.0d, 1024), "hello");

            assertThat(CacheKey.derive(other)).isNotEqualTo(CacheKey.derive(base));
        }

        @Test
        void topPChangesKey() {
            CompletionRequest other =
                    request(ACME, MODEL, new SamplingParams(0.0d, 0.9d, 1024), "hello");

            assertThat(CacheKey.derive(other)).isNotEqualTo(CacheKey.derive(base));
        }

        @Test
        void maxTokensChangesKey() {
            CompletionRequest other =
                    request(ACME, MODEL, new SamplingParams(0.0d, 1.0d, 512), "hello");

            assertThat(CacheKey.derive(other)).isNotEqualTo(CacheKey.derive(base));
        }

        @Test
        void toolDefinitionsChangeKey() {
            CompletionRequest withTools = new CompletionRequest(
                    ACME,
                    MODEL,
                    List.of(Message.user("hello")),
                    SamplingParams.DETERMINISTIC,
                    false,
                    "[{\"name\":\"get_weather\"}]",
                    "");

            assertThat(CacheKey.derive(withTools)).isNotEqualTo(CacheKey.derive(base));
        }

        @Test
        void roleChangesKey() {
            CompletionRequest asSystem = new CompletionRequest(
                    ACME,
                    MODEL,
                    List.of(Message.system("hello")),
                    SamplingParams.DETERMINISTIC,
                    false,
                    "",
                    "");

            assertThat(CacheKey.derive(asSystem)).isNotEqualTo(CacheKey.derive(base));
        }
    }

    @Nested
    @DisplayName("fields deliberately excluded from the key")
    class Exclusions {

        @Test
        void streamFlagDoesNotChangeKey() {
            // A streamed and a non-streamed request want the same answer delivered
            // differently, so they must be able to share a cache entry.
            CompletionRequest buffered = new CompletionRequest(
                    ACME, MODEL, List.of(Message.user("hi")),
                    SamplingParams.DETERMINISTIC, false, "", "");
            CompletionRequest streamed = new CompletionRequest(
                    ACME, MODEL, List.of(Message.user("hi")),
                    SamplingParams.DETERMINISTIC, true, "", "");

            assertThat(CacheKey.derive(streamed)).isEqualTo(CacheKey.derive(buffered));
        }

        @Test
        void idempotencyKeyDoesNotChangeKey() {
            // Including it would give every retry a unique key and defeat the cache.
            CompletionRequest first = new CompletionRequest(
                    ACME, MODEL, List.of(Message.user("hi")),
                    SamplingParams.DETERMINISTIC, false, "", "req-1");
            CompletionRequest retry = new CompletionRequest(
                    ACME, MODEL, List.of(Message.user("hi")),
                    SamplingParams.DETERMINISTIC, false, "", "req-2");

            assertThat(CacheKey.derive(retry)).isEqualTo(CacheKey.derive(first));
        }
    }

    @Nested
    @DisplayName("unambiguous encoding - cross-tenant isolation")
    class EncodingIsAmbiguityFree {

        /**
         * The attack this defends against. Without length-prefixed fields, concatenating
         * tenant and model produces "acmegpt-4" for both of these, so a tenant who can
         * choose their own id could read another tenant's cached completions.
         */
        @Test
        void fieldBoundariesCannotBeShifted() {
            CompletionRequest victim = request(
                    TenantId.of("acme"), ModelId.of("gpt-4"),
                    SamplingParams.DETERMINISTIC, "secret");
            CompletionRequest attacker = request(
                    TenantId.of("acmeg"), ModelId.of("pt-4"),
                    SamplingParams.DETERMINISTIC, "secret");

            assertThat(CacheKey.derive(attacker).hex())
                    .as("shifting a character across a field boundary must not collide")
                    .isNotEqualTo(CacheKey.derive(victim).hex());
        }

        @Test
        void messageSplitCannotBeShifted() {
            // One message "ab" versus two messages "a" and "b": the concatenated text is
            // identical, but these are different conversations.
            CompletionRequest single = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "ab");
            CompletionRequest split = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "a", "b");

            assertThat(CacheKey.derive(split)).isNotEqualTo(CacheKey.derive(single));
        }

        @Test
        void messageOrderMatters() {
            CompletionRequest forward =
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "first", "second");
            CompletionRequest reversed =
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "second", "first");

            assertThat(CacheKey.derive(reversed)).isNotEqualTo(CacheKey.derive(forward));
        }

        @Test
        void manyTenantsProduceDistinctKeysForTheSamePrompt() {
            long distinct = java.util.stream.IntStream.range(0, 2_000)
                    .mapToObj(i -> request(
                            TenantId.of("tenant-" + i), MODEL,
                            SamplingParams.DETERMINISTIC, "same prompt"))
                    .map(CacheKey::derive)
                    .map(CacheKey::hex)
                    .distinct()
                    .count();

            assertThat(distinct).isEqualTo(2_000L);
        }
    }

    @Nested
    @DisplayName("normalisation is applied")
    class Normalisation {

        @Test
        void lineEndingStyleDoesNotChangeKey() {
            CompletionRequest unix = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "a\nb");
            CompletionRequest windows = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "a\r\nb");

            assertThat(CacheKey.derive(windows)).isEqualTo(CacheKey.derive(unix));
        }

        @Test
        void surroundingWhitespaceDoesNotChangeKey() {
            CompletionRequest bare = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello");
            CompletionRequest padded =
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "  hello\n\n");

            assertThat(CacheKey.derive(padded)).isEqualTo(CacheKey.derive(bare));
        }

        @Test
        void internalIndentationDoesChangeKey() {
            // The two Python snippets below are different programs; one raises
            // IndentationError. Collapsing internal whitespace would merge them.
            CompletionRequest indented =
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "def f():\n    return 1");
            CompletionRequest flat =
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "def f():\nreturn 1");

            assertThat(CacheKey.derive(flat)).isNotEqualTo(CacheKey.derive(indented));
        }

        @Test
        void caseIsSignificant() {
            CompletionRequest lower = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello");
            CompletionRequest upper = request(ACME, MODEL, SamplingParams.DETERMINISTIC, "Hello");

            assertThat(CacheKey.derive(upper)).isNotEqualTo(CacheKey.derive(lower));
        }
    }

    @Nested
    @DisplayName("redis key")
    class RedisKey {

        @Test
        void isNamespacedAndHashTagged() {
            CacheKey key = CacheKey.derive(
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello"));

            assertThat(key.redisKey())
                    .startsWith("{tenant:acme}:c1:")
                    .endsWith(key.hex());
        }

        @Test
        void shortFormIsPrefixOfFullDigest() {
            CacheKey key = CacheKey.derive(
                    request(ACME, MODEL, SamplingParams.DETERMINISTIC, "hello"));

            assertThat(key.shortForm()).hasSize(16);
            assertThat(key.hex()).startsWith(key.shortForm());
        }
    }

    @Test
    void rejectsMalformedDigest() {
        assertThatThrownBy(() -> new CacheKey(ACME, "tooshort"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("64-character");
    }
}
