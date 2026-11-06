package io.vasanth.gateway.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Indexer service: owns every write to the Tier-2 vector store.
 *
 * <p>The sharpest service boundary in the system. HNSW insertion is expensive and
 * write-amplifying; performing it inline would make every cache miss pay index
 * maintenance on the user's critical path. Here, the read path never pays for the write
 * path, and the two scale on independent axes - reads with request volume, writes with
 * miss rate.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class IndexerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexerApplication.class, args);
    }
}
