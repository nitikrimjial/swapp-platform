package com.swapp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outgoing email. SMTP connection details live under the standard {@code spring.mail.*} keys.
 *
 * @param provider             {@code log} (development: emails are written to the log) or {@code smtp}
 * @param from                 sender address; must be a verified sender at the SMTP provider
 * @param fromName             sender display name
 * @param requireVerification  when true, unverified accounts cannot post listings, make offers, request items,
 *                             send messages or file reports
 * @param verificationTtl      lifetime of an email verification link
 * @param passwordResetTtl     lifetime of a password reset link
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailSettings(String provider, String from, String fromName, boolean requireVerification,
                           Duration verificationTtl, Duration passwordResetTtl) {

    public boolean smtp() {
        return "smtp".equalsIgnoreCase(provider);
    }
}
