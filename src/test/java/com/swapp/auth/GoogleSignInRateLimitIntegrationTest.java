package com.swapp.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import com.swapp.security.RequestRateLimiter;
import com.swapp.support.AbstractIntegrationTest;

/**
 * Each "Continue with Google" creates a short-lived server session, so starting the flow is rate limited per client
 * address. This also proves the rate-limit filter runs before Spring's OAuth redirect filter.
 */
@TestPropertySource(properties = {
        "app.google.client-id=test-client-id.apps.googleusercontent.com",
        "app.google.client-secret=test-client-secret",
        "app.limits.rate-limit-enabled=true",
        "app.limits.auth-attempts-per-minute=3"})
class GoogleSignInRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RequestRateLimiter limiter;

    @Test
    void startingGoogleSignInIsLimitedPerClientAddress() throws Exception {
        ((Map<?, ?>) ReflectionTestUtils.getField(limiter, "windows")).clear();
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/oauth2/authorization/google"))
                    .andExpect(redirectedUrlPattern("https://accounts.google.com/**"));
        }
        mvc.perform(get("/oauth2/authorization/google")).andExpect(redirectedUrl("/login.html?google=busy"));
    }
}
