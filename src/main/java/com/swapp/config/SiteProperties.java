package com.swapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public facts about this deployment.
 *
 * @param publicUrl    absolute base URL used in links inside emails (no trailing slash), e.g. https://swapp.example
 * @param contactEmail address shown on the Privacy Policy and in emails for privacy questions and grievances
 */
@ConfigurationProperties(prefix = "app.site")
public record SiteProperties(String publicUrl, String contactEmail) {

    public SiteProperties {
        publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    public String link(String path) {
        return publicUrl + path;
    }
}
