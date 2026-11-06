package io.vasanth.gateway.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vasanth.gateway.common.error.GatewayError;
import io.vasanth.gateway.common.error.GatewayException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * The contract that matters here is a threading one, so these tests assert on threads
 * rather than on values.
 */
class EmbeddingServiceTest {

    /** Wraps a model and records which thread each call ran on. */
    private static final class ThreadRecordingModel implements EmbeddingModel {
        private final EmbeddingModel delegate = new HashingEmbeddingModel();
        private final Set<String> threads = ConcurrentHashMap.newKeySet();
        private final CountDownLatch gate;
        private final int concurrency;

        ThreadRecordingModel(CountDownLatch gate) {
            this(gate, 2);
        }

        ThreadRecordingModel(CountDownLatch gate, int concurrency) {
            this.gate = gate;
            this.concurrency = concurrency;
        }

        @Override
        public Embedding embed(String text) {
            threads.add(Thread.currentThread().getName());
            if (gate != null) {
                try {
                    // Holds the worker so the queue behind it can be observed filling.
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return delegate.embed(text);
        }

        @Override
        public List<Embedding> embedBatch(List<String> texts) {
            threads.add(Thread.currentThread().getName());
            return delegate.embedBatch(texts);
        }

        @Override
        public int dimension() {
            return delegate.dimension();
        }

        @Override
        public String modelId() {
            return delegate.modelId();
        }

        @Override
        public int maxConcurrency() {
            return concurrency;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    @Test
    @DisplayName("inference never runs on the calling thread")
    void embedRunsOnTheEmbeddingScheduler() {
        // The whole reason this class exists. If inference ran on the caller's thread, a
        // Netty event loop would be blocked and every request sharing it would stall.
        ThreadRecordingModel model = new ThreadRecordingModel(null);
        try (EmbeddingService service = EmbeddingService.create(model, 64)) {

            String callerThread = Thread.currentThread().getName();
            AtomicReference<String> observed = new AtomicReference<>();

            service.embed("hello").doOnNext(e -> observed.set("done")).block(Duration.ofSeconds(5));

            assertThat(model.threads).isNotEmpty();
            assertThat(model.threads)
                    .as("inference must not run on the subscribing thread")
                    .doesNotContain(callerThread);
            assertThat(model.threads).allSatisfy(name ->
                    assertThat(name).startsWith("embedding-"));
            assertThat(observed.get()).isEqualTo("done");
        }
    }

    @Test
    @DisplayName("thread count is capped, so CPU work does not oversubscribe cores")
    void concurrencyIsBounded() {
        ThreadRecordingModel model = new ThreadRecordingModel(null);
        try (EmbeddingService service = EmbeddingService.create(model, 256)) {

            Flux.range(0, 200)
                    .flatMap(i -> service.embed("text " + i), 50)
                    .blockLast(Duration.ofSeconds(30));

            int cores = Runtime.getRuntime().availableProcessors();
            int expectedCap = Math.max(1, Math.min(2, cores));

            assertThat(model.threads)
                    .as("expected at most %d worker threads, saw %s", expectedCap, model.threads)
                    .hasSizeLessThanOrEqualTo(expectedCap);
        }
    }

    @Test
    @DisplayName("scheduler rejection becomes a retryable 503, not a 500")
    void rejectionSurfacesAsAnOverloadedGatewayError() {
        // Driven by a scheduler that always rejects, rather than by trying to fill a real
        // queue. Filling a queue would be testing Reactor's internals and would depend on
        // timing; this tests the thing that is actually ours - the mapping from a rejected
        // execution to a typed, retryable gateway error.
        Scheduler alwaysRejects = Schedulers.fromExecutor(task -> {
            throw new RejectedExecutionException("queue full");
        });

        EmbeddingService service =
                EmbeddingService.using(new HashingEmbeddingModel(), alwaysRejects);

        StepVerifier.create(service.embed("anything"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    GatewayError gatewayError = ((GatewayException) error).error();
                    assertThat(gatewayError).isInstanceOf(GatewayError.Overloaded.class);
                    assertThat(gatewayError.httpStatus()).isEqualTo(503);
                    assertThat(gatewayError.retryable())
                            .as("backpressure is transient, so the client should retry")
                            .isTrue();
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a model failure is not leaked to the caller verbatim")
    void modelFailureBecomesAGenericInternalError() {
        // Inference failures can carry native library paths and model file locations.
        // Those are reconnaissance, so the caller gets a fixed message.
        EmbeddingService service = EmbeddingService.using(new EmbeddingModel() {
            @Override
            public Embedding embed(String text) {
                throw new IllegalStateException("/opt/models/secret-path/model.onnx is corrupt");
            }

            @Override
            public List<Embedding> embedBatch(List<String> texts) {
                throw new IllegalStateException("boom");
            }

            @Override
            public int dimension() {
                return 384;
            }

            @Override
            public String modelId() {
                return "exploding";
            }

            @Override
            public int maxConcurrency() {
                return 1;
            }

            @Override
            public void close() {
                // nothing
            }
        }, Schedulers.immediate());

        StepVerifier.create(service.embed("anything"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GatewayException.class);
                    assertThat(error.getMessage())
                            .isEqualTo("embedding unavailable")
                            .doesNotContain("/opt/models");
                })
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void reportsModelMetadata() {
        try (EmbeddingService service =
                EmbeddingService.create(new HashingEmbeddingModel(), 16)) {

            assertThat(service.dimension()).isEqualTo(Embedding.MINILM_DIMENSION);
            assertThat(service.modelId()).isEqualTo("hashing-trigram-v1");
        }
    }

    @Test
    @DisplayName("the hashing fallback is flagged as not semantically meaningful")
    void hashingModelIsNotSemanticallyMeaningful() {
        // Tier 2 must refuse to serve from a lexical model: a surface-form near-match is
        // not an answer to the same question.
        try (EmbeddingService service =
                EmbeddingService.create(new HashingEmbeddingModel(), 16)) {

            assertThat(service.isSemanticallyMeaningful()).isFalse();
        }
    }

    @Test
    void emptyBatchShortCircuits() {
        try (EmbeddingService service =
                EmbeddingService.create(new HashingEmbeddingModel(), 16)) {

            StepVerifier.create(service.embedBatch(List.of()))
                    .assertNext(list -> assertThat(list).isEmpty())
                    .verifyComplete();
        }
    }
}
