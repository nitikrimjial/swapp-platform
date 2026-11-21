package com.swapp.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cookie cookie, Cors cors, Security security, Storage storage, Limits limits) {

    public record Jwt(String secret, Duration expiration) {
    }

    public record Cookie(String name, boolean secure) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Security(int bcryptStrength, int loginMaxAttempts, Duration loginWindow) {
    }

    public record Storage(String type, String localDir, long maxImageBytes, int maxImagesPerItem) {
    }

    /**
     * Abuse limits for a public deployment. Request rates are per client address (anonymous auth endpoints) or per
     * user (writes); quotas stop one account, or the whole site, from exhausting the database.
     */
    public record Limits(boolean rateLimitEnabled, int registrationsPerHour, int authAttemptsPerMinute,
                         int writesPerMinute, int emailRequestsPerHour, int exportsPerHour, int maxActiveListingsPerUser,
                         DataSize storagePerUser, DataSize storageTotal, int realtimeConnectionsPerUser) {
    }
}
