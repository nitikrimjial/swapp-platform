package com.swapp.mail;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.util.StringUtils;

import com.swapp.config.MailSettings;

/** Chooses the email sender and refuses to start with a configuration that would silently lose emails. */
@Configuration
@EnableAsync
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public EmailSender emailSender(MailSettings settings, ObjectProvider<JavaMailSender> javaMailSender,
                                   Environment env) {
        if (settings.smtp()) {
            JavaMailSender smtp = javaMailSender.getIfAvailable();
            if (smtp == null || !StringUtils.hasText(env.getProperty("spring.mail.host"))) {
                throw new IllegalStateException("MAIL_PROVIDER=smtp needs SMTP_HOST, SMTP_USERNAME and SMTP_PASSWORD");
            }
            if (!StringUtils.hasText(settings.from())) {
                throw new IllegalStateException("MAIL_PROVIDER=smtp needs MAIL_FROM (a sender verified at the provider)");
            }
            log.info("Email delivery: SMTP via {}", env.getProperty("spring.mail.host"));
            return new SmtpEmailSender(smtp, settings.from(), settings.fromName());
        }
        if (settings.requireVerification()) {
            throw new IllegalStateException(
                    "REQUIRE_EMAIL_VERIFICATION=true needs real email delivery (MAIL_PROVIDER=smtp), "
                            + "otherwise new members could never verify their address");
        }
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            log.warn("MAIL_PROVIDER=log in production: emails (password resets, verification) are NOT delivered");
        }
        return new LoggingEmailSender();
    }
}
