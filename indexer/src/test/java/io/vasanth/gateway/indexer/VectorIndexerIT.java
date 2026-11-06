package io.vasanth.gateway.indexer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.vasanth.gateway.common.event.GatewayEvents;

/**
 * Vector-store writes against real pgvector.
 *
 * <p>The schema lives in the gateway module - see the note in {@code VectorIndexer} about
 * ownership - so this test applies it directly rather than relying on Flyway, which is
 * disabled in this service.
 */
@Testcontainers
@SpringBootTest(properties = {
        "gateway.kafka.bootstrap-servers=localhost:1",
        "gateway.indexer.eviction-enabled=false"
})
class VectorIndexerIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg17-trixie")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("indexer")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> String.format(
                "r2dbc:postgresql://%s:%d/%s",
                POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), POSTGRES.getDatabaseName()));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    }

    @Autowired private VectorIndexer indexer;
    @Autowired private DatabaseClient db;

    private static String vector(float first) {
        StringBuilder builder = new StringBuilder("[").append(first);
        for (int i = 1; i < 384; i++) {
            builder.append(",0");
        }
        return builder.append(']').toString();
    }

    private static GatewayEvents.CacheWriteEvent event(String key, String vec, Instant expires) {
        return new GatewayEvents.CacheWriteEvent(
                GatewayEvents.SCHEMA_VERSION, "acme", key, "llama-3.1-8b",
                "all-MiniLM-L6-v2", "a prompt", vec, "an answer",
                10L, 20L, "mock", Instant.parse("2026-11-02T10:00:00Z"), expires);
    }

    private long count(String sql) {
        return db.sql(sql).map((row, meta) -> row.get(0, Long.class)).one()
                .block(Duration.ofSeconds(10));
    }

    @BeforeEach
    void schema() {
        db.sql("CREATE EXTENSION IF NOT EXISTS vector").fetch().rowsUpdated()
                .block(Duration.ofSeconds(20));
        db.sql("DROP TABLE IF EXISTS prompt_cache CASCADE").fetch().rowsUpdated()
                .block(Duration.ofSeconds(20));
        db.sql("""
                CREATE TABLE prompt_cache (
                    id BIGSERIAL, tenant_id TEXT NOT NULL, cache_key TEXT NOT NULL,
                    model_id TEXT NOT NULL, embedding_model TEXT NOT NULL,
                    prompt_text TEXT NOT NULL, embedding vector(384) NOT NULL,
                    response TEXT NOT NULL, prompt_tokens BIGINT NOT NULL,
                    completion_tokens BIGINT NOT NULL, provider TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    expires_at TIMESTAMPTZ NOT NULL,
                    hit_count BIGINT NOT NULL DEFAULT 0, last_hit_at TIMESTAMPTZ,
                    PRIMARY KEY (tenant_id, id))
                """).fetch().rowsUpdated().block(Duration.ofSeconds(20));
    }

    @Test
    void writesAVector() {
        indexer.index(event("key-1", vector(1.0f), Instant.parse("2027-01-01T00:00:00Z")))
                .block(Duration.ofSeconds(10));

        assertThat(count("SELECT count(*) FROM prompt_cache")).isEqualTo(1L);
        assertThat(count("SELECT vector_dims(embedding) FROM prompt_cache LIMIT 1"))
                .isEqualTo(384L);
    }

    @Test
    @DisplayName("the stored vector is searchable with the inner-product operator")
    void storedVectorIsUsableForSimilaritySearch() {
        indexer.index(event("key-1", vector(1.0f), Instant.parse("2027-01-01T00:00:00Z")))
                .block(Duration.ofSeconds(10));

        // Confirms the sign convention: <#> returns the NEGATIVE inner product, so
        // similarity is -(a <#> b). Getting this backwards silently returns the LEAST
        // similar rows.
        Double similarity = db.sql(
                        "SELECT -(embedding <#> CAST(:q AS vector)) FROM prompt_cache LIMIT 1")
                .bind("q", vector(1.0f))
                .map((row, meta) -> row.get(0, Double.class)).one()
                .block(Duration.ofSeconds(10));

        assertThat(similarity).isCloseTo(1.0d, org.assertj.core.data.Offset.offset(1e-5d));
    }

    @Test
    void recordsTheEmbeddingModelSoVectorsAreNeverComparedAcrossModels() {
        indexer.index(event("key-1", vector(1.0f), Instant.parse("2027-01-01T00:00:00Z")))
                .block(Duration.ofSeconds(10));

        String model = db.sql("SELECT embedding_model FROM prompt_cache LIMIT 1")
                .map((row, meta) -> row.get(0, String.class)).one()
                .block(Duration.ofSeconds(10));

        assertThat(model).isEqualTo("all-MiniLM-L6-v2");
    }

    @Test
    void storesExpiryForTheTtlSweep() {
        Instant expires = Instant.parse("2027-01-01T00:00:00Z");
        indexer.index(event("key-1", vector(1.0f), expires)).block(Duration.ofSeconds(10));

        assertThat(count(
                "SELECT count(*) FROM prompt_cache WHERE expires_at > now()")).isEqualTo(1L);
    }

    @Test
    void expiredRowsAreVisibleToTheSweepButNotToAReader() {
        indexer.index(event("stale", vector(1.0f), Instant.parse("2020-01-01T00:00:00Z")))
                .block(Duration.ofSeconds(10));

        assertThat(count("SELECT count(*) FROM prompt_cache")).isEqualTo(1L);
        assertThat(count(
                "SELECT count(*) FROM prompt_cache WHERE expires_at > now()")).isZero();
    }
}
