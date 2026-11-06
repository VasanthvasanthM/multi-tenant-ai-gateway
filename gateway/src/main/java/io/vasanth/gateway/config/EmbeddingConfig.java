package io.vasanth.gateway.config;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import io.vasanth.gateway.embedding.EmbeddingModel;
import io.vasanth.gateway.embedding.EmbeddingService;
import io.vasanth.gateway.embedding.HashingEmbeddingModel;
import io.vasanth.gateway.embedding.OnnxEmbeddingModel;

/** Chooses and wires the embedding model. */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    /**
     * Builds the embedding service, falling back to the lexical model when no ONNX model
     * is on disk.
     *
     * <p>The fallback exists so the service starts and the whole Tier-2 path stays
     * exercisable on a machine that has not downloaded 90 MB of weights - CI, a fresh
     * clone, a laptop with no network. It is <b>not</b> a production configuration, and
     * two things make that impossible to use by accident:
     *
     * <ul>
     *   <li>A warning is logged at startup, repeated and explicit.
     *   <li>{@code EmbeddingService.isSemanticallyMeaningful()} returns false, and
     *       {@code Tier2SemanticCache} refuses to serve from it however high a candidate
     *       scores. The cache degrades to Tier 1 only; it never serves a lexical match as
     *       though it were a semantic one.
     * </ul>
     */
    @Bean(destroyMethod = "close")
    public EmbeddingService embeddingService(EmbeddingProperties properties) {
        EmbeddingModel model = resolveModel(properties);
        return EmbeddingService.create(model, properties.queueCapacity());
    }

    private static EmbeddingModel resolveModel(EmbeddingProperties properties) {
        String configuredPath = properties.modelPath();

        if (configuredPath == null || configuredPath.isBlank()) {
            warnFallback("gateway.embedding.model-path is not set");
            return new HashingEmbeddingModel();
        }

        Path modelDirectory = Path.of(configuredPath);
        if (!Files.isDirectory(modelDirectory)) {
            warnFallback("no model directory at " + modelDirectory.toAbsolutePath());
            return new HashingEmbeddingModel();
        }

        int concurrency = properties.concurrency() > 0
                ? properties.concurrency()
                : Runtime.getRuntime().availableProcessors();

        try {
            return OnnxEmbeddingModel.load(modelDirectory, properties.modelId(), concurrency);
        } catch (OnnxEmbeddingModel.EmbeddingModelException e) {
            // Deliberately does not rethrow. A corrupt or half-downloaded model should
            // leave the gateway serving Tier-1 traffic, not take it down - Tier 2 is an
            // optimisation, and the same rule applies to its model as to its database.
            log.error("failed to load ONNX model, falling back to the lexical model", e);
            warnFallback("ONNX model failed to load");
            return new HashingEmbeddingModel();
        }
    }

    private static void warnFallback(String reason) {
        log.warn(
                "=================================================================\n"
                + " EMBEDDING FALLBACK ACTIVE - {}\n"
                + " Using the hashing (lexical) model. It matches surface form, NOT\n"
                + " meaning: 'car' and 'automobile' score as unrelated.\n"
                + " Tier 2 will NOT serve any result while this model is active.\n"
                + " Run scripts/fetch-model.sh to install all-MiniLM-L6-v2.\n"
                + "=================================================================",
                reason);
    }

    /**
     * @param modelPath directory containing {@code model.onnx} and tokenizer files; blank
     *     selects the lexical fallback
     * @param modelId recorded on every indexed row so vectors from different models are
     *     never compared
     * @param queueCapacity how many inference calls may wait before shedding
     * @param concurrency predictor pool size; 0 means one per core
     */
    @Validated
    @ConfigurationProperties(prefix = "gateway.embedding")
    public record EmbeddingProperties(
            String modelPath,
            @NotNull String modelId,
            @Min(1) int queueCapacity,
            @Min(0) int concurrency) {
    }
}
