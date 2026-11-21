package com.swapp.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development sender: writes each email, links included, to the application log instead of delivering it. Never use
 * it in production, because the log would then contain working verification and password reset links.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(EmailMessage message) {
        log.info("""

                ---------------- email not sent (MAIL_PROVIDER=log) ----------------
                To:      {}
                Subject: {}

                {}
                --------------------------------------------------------------------""",
                message.to(), message.subject(), message.text());
    }
}
