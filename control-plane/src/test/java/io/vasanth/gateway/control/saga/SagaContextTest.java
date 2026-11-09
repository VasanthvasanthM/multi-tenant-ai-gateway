package io.vasanth.gateway.control.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The state carried between saga steps.
 *
 * <p>Small, and worth testing for one reason above the others: {@link SagaContext#toString()}
 * deliberately prints keys without values, because a provisioning saga holds a freshly
 * generated API key and a context routinely ends up in a log line or an exception message.
 * A change that made it print values would be a credential leak with no other symptom, and
 * nothing else in the build would notice.
 */
class SagaContextTest {

    private static final UUID SAGA_ID = UUID.fromString("00000000-0000-0000-0000-00000000beef");

    private static SagaContext context() {
        return new SagaContext(SAGA_ID, "acme");
    }

    @Test
    @DisplayName("identity is what it was constructed with")
    void identity() {
        SagaContext context = context();

        assertThat(context.sagaId()).isEqualTo(SAGA_ID);
        assertThat(context.aggregateId()).isEqualTo("acme");
    }

    @Test
    @DisplayName("a value round-trips with its type")
    void putAndGet() {
        SagaContext context = context();
        context.put("apiKey", "sk-secret");
        context.put("step", 3);

        assertThat(context.get("apiKey", String.class)).isEqualTo("sk-secret");
        assertThat(context.get("step", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("an absent key is null, not an exception")
    void absentKeyIsNull() {
        // The orchestrator reads "__step" before any step has run, so this is a live path
        // rather than defensive: compensate() relies on null meaning "nothing completed".
        assertThat(context().get("__step", Integer.class)).isNull();
        assertThat(context().has("__step")).isFalse();
    }

    @Test
    @DisplayName("has() distinguishes absent from present")
    void has() {
        SagaContext context = context();
        assertThat(context.has("k")).isFalse();

        context.put("k", "v");
        assertThat(context.has("k")).isTrue();
    }

    @Test
    @DisplayName("a wrong type fails loudly rather than returning null")
    void wrongTypeThrows() {
        SagaContext context = context();
        context.put("step", 3);

        // type.cast rather than an unchecked cast, so the failure happens here with the
        // actual types named instead of as a ClassCastException somewhere downstream.
        assertThatThrownBy(() -> context.get("step", String.class))
                .isInstanceOf(ClassCastException.class);
    }

    @Test
    @DisplayName("a later put overwrites")
    void putOverwrites() {
        SagaContext context = context();
        context.put("k", "first");
        context.put("k", "second");

        assertThat(context.get("k", String.class)).isEqualTo("second");
    }

    @Test
    @DisplayName("toString prints keys but NEVER values")
    void toStringRedactsValues() {
        SagaContext context = context();
        context.put("apiKey", "sk-live-do-not-log-this");
        context.put("redisKey", "{tenant:acme}:apikey");

        String rendered = context.toString();

        // The whole point. A provisioning saga holds a freshly generated API key, and this
        // object reaches logs and exception messages.
        assertThat(rendered).doesNotContain("sk-live-do-not-log-this");
        assertThat(rendered).doesNotContain("{tenant:acme}:apikey");
        // Keys are still there, because knowing WHICH values exist is what makes a saga
        // log useful during an incident.
        assertThat(rendered).contains("apiKey").contains("redisKey");
        assertThat(rendered).contains(SAGA_ID.toString()).contains("acme");
    }

    @Test
    @DisplayName("writes from one thread are visible to another")
    void crossThreadVisibility() throws Exception {
        // Steps run sequentially so contention is not the concern - but the orchestrator
        // may read the context from a different thread than the one that wrote it, and a
        // plain HashMap gives no happens-before guarantee across that handoff. This is a
        // smoke test of that property, not a proof of it.
        SagaContext context = context();
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch done = new CountDownLatch(writers);

        try {
            IntStream.range(0, writers).forEach(i -> pool.execute(() -> {
                context.put("key-" + i, i);
                done.countDown();
            }));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

            for (int i = 0; i < writers; i++) {
                assertThat(context.get("key-" + i, Integer.class)).isEqualTo(i);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
