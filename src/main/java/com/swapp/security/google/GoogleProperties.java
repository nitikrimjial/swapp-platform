package com.swapp.security.google;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * OAuth client credentials from Google Cloud Console (APIs and Services, Credentials, "OAuth client ID", type "Web
 * application"). Leave both blank to turn "Sign in with Google" off; the button is then hidden.
 */
@ConfigurationProperties(prefix = "app.google")
public record GoogleProperties(String clientId, String clientSecret) {

    public boolean enabled() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
