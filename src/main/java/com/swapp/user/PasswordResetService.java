package com.swapp.user;

import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.config.AppProperties;
import com.swapp.config.MailSettings;
import com.swapp.mail.AccountEmails;
import com.swapp.realtime.SessionsRevokedEvent;
import com.swapp.security.RequestRateLimiter;
import com.swapp.user.token.TokenPurpose;
import com.swapp.user.token.UserTokenService;

/**
 * Forgotten-password flow, following the OWASP Forgot Password Cheat Sheet: the request endpoint answers identically
 * whether or not the email is registered, links are single-use and short-lived, a successful reset revokes every
 * session, and the member is told by email that their password changed.
 */
@Service
@Transactional
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository users;
    private final UserTokenService tokens;
    private final PasswordEncoder encoder;
    private final AccountEmails emails;
    private final MailSettings settings;
    private final RequestRateLimiter limiter;
    private final AppProperties.Limits limits;
    private final ApplicationEventPublisher events;

    public PasswordResetService(UserRepository users, UserTokenService tokens, PasswordEncoder encoder,
                                AccountEmails emails, MailSettings settings, RequestRateLimiter limiter,
                                AppProperties props, ApplicationEventPublisher events) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.emails = emails;
        this.settings = settings;
        this.limiter = limiter;
        this.limits = props.limits();
        this.events = events;
    }

    /**
     * Always succeeds from the caller's point of view, so it cannot be used to discover registered emails. Runs in the
     * background so the response time is the same whether or not the address belongs to an account.
     */
    @Async
    public void requestReset(String rawEmail) {
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        // Per-address cap stops anyone using us to flood a victim's inbox; excess requests are dropped silently.
        if (limits.rateLimitEnabled()
                && !limiter.acquire("reset-email:" + email, limits.emailRequestsPerHour(), Duration.ofHours(1))
                .isZero()) {
            log.info("Password reset email suppressed: per-address hourly limit reached");
            return;
        }
        users.findByEmailIgnoreCase(email).ifPresent(user -> {
            String token = tokens.issue(user, TokenPurpose.PASSWORD_RESET, settings.passwordResetTtl());
            events.publishEvent(emails.passwordReset(user.getEmail(), user.getDisplayName(), token,
                    settings.passwordResetTtl()));
            log.info("Password reset requested for user {}", user.getId());
        });
    }

    public void reset(String rawToken, String newPassword) {
        User user = tokens.consume(rawToken, TokenPurpose.PASSWORD_RESET)
                .orElseThrow(() -> ApiException.badRequest(
                        "This reset link is invalid or has expired. Please request a new one."));
        user.setPasswordHash(encoder.encode(newPassword));
        EmailVerificationService.markVerified(user);
        user.revokeTokens();
        users.saveAndFlush(user);
        events.publishEvent(new SessionsRevokedEvent(user.getId()));
        events.publishEvent(emails.passwordChanged(user.getEmail(), user.getDisplayName()));
        log.info("User {} reset their password; all sessions revoked", user.getId());
    }
}
