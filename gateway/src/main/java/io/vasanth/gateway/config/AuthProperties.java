package io.vasanth.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import io.vasanth.gateway.auth.RedisApiKeyRegistry;
import io.vasanth.gateway.common.tenant.TenantId;

/**
 * Authentication configuration.
 *
 * @param headerName header carrying the API key
 * @param bootstrapKeys raw key to tenant id, for local development only. Production leaves
 *     this empty and lets the control plane populate Redis
 */
@Validated
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthProperties(@NotEmpty String headerName, Map<String, String> bootstrapKeys) {

    public AuthProperties {
        bootstrapKeys = bootstrapKeys == null ? Map.of() : Map.copyOf(bootstrapKeys);
    }

    /**
     * Bootstrap keys, pre-hashed at startup.
     *
     * <p>Hashing once here rather than per request keeps the authentication path free of a
     * digest over configuration data, and means the raw values exist only in the config
     * object rather than being re-read on every call.
     */
    public Map<String, TenantId> hashedBootstrapKeys() {
        Map<String, TenantId> hashed = new LinkedHashMap<>(bootstrapKeys.size());
        bootstrapKeys.forEach((rawKey, tenant) ->
                hashed.put(RedisApiKeyRegistry.sha256Hex(rawKey), TenantId.of(tenant)));
        return Map.copyOf(hashed);
    }
}
