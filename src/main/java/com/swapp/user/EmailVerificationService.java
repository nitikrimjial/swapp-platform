package com.swapp.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.config.MailSettings;
import com.swapp.mail.AccountEmails;
import com.swapp.user.token.TokenPurpose;
import com.swapp.user.token.UserTokenService;

/** Proves that a member controls their email address. */
@Service
@Transactional
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final UserRepository users;
    private final UserTokenService tokens;
    private final AccountEmails emails;
    private final MailSettings settings;
    private final ApplicationEventPublisher events;

    public EmailVerificationService(UserRepository users, UserTokenService tokens, AccountEmails emails,
                                    MailSettings settings, ApplicationEventPublisher events) {
        this.users = users;
        this.tokens = tokens;
        this.emails = emails;
        this.settings = settings;
        this.events = events;
    }

    /** Emails a fresh verification link (sent after the surrounding transaction commits). */
    public void sendVerification(User user) {
        String token = tokens.issue(user, TokenPurpose.EMAIL_VERIFICATION, settings.verificationTtl());
        events.publishEvent(emails.verifyEmail(user.getEmail(), user.getDisplayName(), token,
                settings.verificationTtl()));
    }

    public void resend(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (user.isEmailVerified()) {
            throw ApiException.conflict("Your email address is already verified");
        }
        sendVerification(user);
        log.info("Verification email re-sent to user {}", userId);
    }

    /** Works without a session, so the link can be opened on any device. */
    public void verify(String rawToken) {
        User user = tokens.consume(rawToken, TokenPurpose.EMAIL_VERIFICATION)
                .orElseThrow(() -> ApiException.badRequest(
                        "This verification link is invalid or has expired. Sign in and request a new one."));
        markVerified(user);
        log.info("User {} verified their email address", user.getId());
    }

    /** Also used by password reset: following an emailed link proves control of the address. */
    static void markVerified(User user) {
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(BaseEntity.now());
        }
    }
}
