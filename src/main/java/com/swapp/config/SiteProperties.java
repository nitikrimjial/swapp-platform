package com.swapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public facts about this deployment.
 *
 * @param publicUrl    absolute base URL used in links inside emails (no trailing slash), e.g. https://swapp.example
 * @param contactEmail address shown on the Privacy Policy and in emails for privacy questions and grievances
 * @param termsVersion version (date) of the Terms and Privacy Policy that new members accept; bump it when they
 *                     change materially, so each member's acceptance record shows which text they agreed to
 */
@ConfigurationProperties(prefix = "app.site")
public record SiteProperties(String publicUrl, String contactEmail, String termsVersion) {

    public SiteProperties {
        publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
    }

    public String link(String path) {
        return publicUrl + path;
    }
}
