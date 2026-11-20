package com.swapp.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cookie cookie, Cors cors, Security security, Storage storage) {

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
}
