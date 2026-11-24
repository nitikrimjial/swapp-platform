package com.swapp.security.google;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.util.StringUtils;

/**
 * Registers Google as an OpenID Connect provider, only when credentials are configured. Spring Security then validates
 * each ID token's signature (against Google's published keys), issuer, audience, expiry and nonce.
 */
@Configuration
public class GoogleSignInConfig {

    public static final String REGISTRATION_ID = "google";

    @Bean
    @Conditional(GoogleConfigured.class)
    public ClientRegistrationRepository clientRegistrationRepository(GoogleProperties google) {
        return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder(REGISTRATION_ID)
                .clientId(google.clientId().trim())
                .clientSecret(google.clientSecret().trim())
                .scope("openid", "email", "profile")
                .build());
    }

    static class GoogleConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var env = context.getEnvironment();
            return StringUtils.hasText(env.getProperty("app.google.client-id"))
                    && StringUtils.hasText(env.getProperty("app.google.client-secret"));
        }
    }
}
