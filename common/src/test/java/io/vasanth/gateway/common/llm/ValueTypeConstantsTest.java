package io.vasanth.gateway.common.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.model.ModelId;
import io.vasanth.gateway.common.shard.ShardId;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Guards against static initialiser ordering bugs in the value types.
 *
 * <p>These exist because of a real failure: {@code ProviderId.CACHE} was declared above the
 * {@code VALID} pattern its constructor validates against. Static initialisers run in
 * declaration order, so the constant was built while the pattern was still null and the
 * class failed to initialise - surfacing much later as an opaque bean-creation error with
 * no mention of the real cause.
 *
 * <p>Merely touching each constant is enough to force class initialisation and catch it.
 */
class ValueTypeConstantsTest {

    @Test
    @DisplayName("every public constant initialises without an ExceptionInInitializerError")
    void constantsInitialise() {
        assertThatCode(() -> {
            assertThat(ProviderId.CACHE.value()).isEqualTo("cache");
            assertThat(SamplingParams.DETERMINISTIC.temperature()).isZero();
            assertThat(TokenUsage.NONE.total()).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    void validationStillAppliesAfterClassInitialisation() {
        // Proves the pattern really was in place when CACHE was built, rather than the
        // constant having slipped past a null check.
        assertThatThrownBy(() -> ProviderId.of("INVALID UPPERCASE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantId.of("X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelId.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShardId.of("Bad_Shard"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validValuesAreAccepted() {
        assertThat(ProviderId.of("openai-compatible").value()).isEqualTo("openai-compatible");
        assertThat(TenantId.of("acme-corp").value()).isEqualTo("acme-corp");
        assertThat(ModelId.of("meta-llama/Llama-3-8b").value()).isEqualTo("meta-llama/Llama-3-8b");
        assertThat(ShardId.of("pg-shard-0").value()).isEqualTo("pg-shard-0");
    }

    @Test
    void tenantRedisHashTagIsClusterSafe() {
        assertThat(TenantId.of("acme").redisHashTag()).isEqualTo("{tenant:acme}");
    }
}
