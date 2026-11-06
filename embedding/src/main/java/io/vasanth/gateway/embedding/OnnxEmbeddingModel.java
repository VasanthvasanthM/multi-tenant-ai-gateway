package io.vasanth.gateway.embedding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

/**
 * Real sentence embeddings via ONNX Runtime, running in this process.
 *
 * <h2>Why in-process</h2>
 *
 * <p>A hosted embedding API costs 50-300 ms. The entire Tier-2 budget is 15 ms, so a
 * network hop would make the cache slower than the miss it exists to avoid. Local CPU
 * inference on all-MiniLM-L6-v2 costs roughly 6 ms for a short prompt, which fits.
 *
 * <h2>Predictor pooling</h2>
 *
 * <p>DJL's {@link Predictor} is <b>not thread-safe</b>. The two obvious responses are both
 * wrong: a shared predictor behind a lock serialises the most CPU-intensive step in the
 * system, and creating one per call pays allocation and native setup on every request.
 *
 * <p>Instead a fixed pool of predictors is borrowed and returned, sized to the core count.
 * There is no point exceeding that - the work is CPU-saturated, so more predictors would
 * add context switching and inflate p99 without raising throughput. Borrowing blocks with a
 * timeout, and a timeout surfaces as a failure rather than an unbounded wait, which is what
 * keeps this from becoming a hidden queue.
 */
public final class OnnxEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingModel.class);

    /**
     * How long a caller waits for a free predictor.
     *
     * <p>Comfortably above the ~6 ms an inference takes, so transient contention is
     * absorbed, yet short enough that a saturated pool sheds load instead of letting
     * callers pile up behind it.
     */
    private static final long BORROW_TIMEOUT_MILLIS = 250L;

    private final ZooModel<String, float[]> model;
    private final BlockingQueue<Predictor<String, float[]>> pool;
    private final int concurrency;
    private final int dimension;
    private final String modelId;

    private OnnxEmbeddingModel(
            ZooModel<String, float[]> model,
            BlockingQueue<Predictor<String, float[]>> pool,
            int concurrency,
            int dimension,
            String modelId) {
        this.model = model;
        this.pool = pool;
        this.concurrency = concurrency;
        this.dimension = dimension;
        this.modelId = modelId;
    }

    /**
     * Loads a model from disk.
     *
     * @param modelDirectory directory holding {@code model.onnx} and its tokenizer files
     * @param modelId identifier recorded on every indexed row
     * @param concurrency predictor pool size; normally the available core count
     * @throws EmbeddingModelException if the model cannot be loaded
     */
    public static OnnxEmbeddingModel load(Path modelDirectory, String modelId, int concurrency) {
        if (!Files.isDirectory(modelDirectory)) {
            throw new EmbeddingModelException(
                    "model directory does not exist: " + modelDirectory
                            + " (run scripts/fetch-model.sh)");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }

        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(modelDirectory)
                .optEngine("OnnxRuntime")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                // Mean pooling over token embeddings is what sentence-transformers does for
                // this model. Using the CLS token instead - the other common choice - gives
                // markedly worse similarity for all-MiniLM, because it was not trained with
                // a CLS objective.
                .optArgument("pooling", "mean")
                // Normalising inside the translator rather than afterwards keeps the work in
                // native code and guarantees the invariant Embedding.of() checks for.
                .optArgument("normalize", "true")
                // BERT-family graphs declare three inputs: input_ids, attention_mask and
                // token_type_ids. DJL omits the third unless asked, and ONNX Runtime then
                // rejects the call with "Input mismatch, looking for: [...]" at the first
                // inference rather than at load time - which is why load() runs a probe.
                .optArgument("includeTokenTypes", "true")
                .build();

        ZooModel<String, float[]> loaded;
        try {
            loaded = criteria.loadModel();
        } catch (ModelNotFoundException | MalformedModelException | java.io.IOException e) {
            throw new EmbeddingModelException("failed to load ONNX model from " + modelDirectory, e);
        }

        BlockingQueue<Predictor<String, float[]>> pool = new ArrayBlockingQueue<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            pool.add(loaded.newPredictor());
        }

        int dimension = probeDimension(pool);
        log.info(
                "loaded ONNX embedding model '{}' from {} (dimension={}, predictors={})",
                modelId, modelDirectory, dimension, concurrency);

        return new OnnxEmbeddingModel(loaded, pool, concurrency, dimension, modelId);
    }

    @Override
    public Embedding embed(String text) {
        Predictor<String, float[]> predictor = borrow();
        try {
            return Embedding.normalizing(predictor.predict(text));
        } catch (TranslateException e) {
            throw new EmbeddingModelException("inference failed", e);
        } finally {
            pool.add(predictor);
        }
    }

    @Override
    public List<Embedding> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        Predictor<String, float[]> predictor = borrow();
        try {
            List<float[]> raw = predictor.batchPredict(texts);
            List<Embedding> results = new ArrayList<>(raw.size());
            for (float[] vector : raw) {
                results.add(Embedding.normalizing(vector));
            }
            return List.copyOf(results);
        } catch (TranslateException e) {
            throw new EmbeddingModelException("batch inference failed", e);
        } finally {
            pool.add(predictor);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int maxConcurrency() {
        return concurrency;
    }

    @Override
    public void close() {
        Predictor<String, float[]> predictor;
        while ((predictor = pool.poll()) != null) {
            predictor.close();
        }
        model.close();
    }

    private Predictor<String, float[]> borrow() {
        try {
            Predictor<String, float[]> predictor =
                    pool.poll(BORROW_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (predictor == null) {
                throw new EmbeddingModelException(
                        "no embedding predictor available within " + BORROW_TIMEOUT_MILLIS
                                + "ms; the pool of " + concurrency + " is saturated");
            }
            return predictor;
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it: the scheduler thread needs to
            // observe the interrupt to shut down cleanly.
            Thread.currentThread().interrupt();
            throw new EmbeddingModelException("interrupted while waiting for a predictor", e);
        }
    }

    /**
     * Determines the output width by running one inference at load time.
     *
     * <p>Hard-coding 384 would be a latent bug the moment someone points this at a
     * different model: the vector would silently mismatch the {@code vector(n)} column and
     * every insert would fail at runtime instead of at startup.
     */
    private static int probeDimension(BlockingQueue<Predictor<String, float[]>> pool) {
        Predictor<String, float[]> predictor = pool.poll();
        if (predictor == null) {
            throw new EmbeddingModelException("predictor pool was empty during initialisation");
        }
        try {
            return predictor.predict("dimension probe").length;
        } catch (TranslateException e) {
            throw new EmbeddingModelException("model failed its startup probe", e);
        } finally {
            pool.add(predictor);
        }
    }

    /** Raised when the model cannot be loaded or inference fails. */
    public static final class EmbeddingModelException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EmbeddingModelException(String message) {
            super(message);
        }

        public EmbeddingModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
