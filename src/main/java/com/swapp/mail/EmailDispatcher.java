package com.swapp.mail;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends {@link EmailMessage} events after the publishing transaction commits, on a background (virtual) thread, so a
 * slow or failing mail provider never delays or breaks the request. Transient failures are retried with backoff.
 *
 * <p>Trade-off: delivery is best-effort. If the process stops between commit and send, that email is lost; every flow
 * that depends on an email (verification, password reset) lets the user request a new one. A transactional outbox
 * table would make delivery durable if that ever becomes necessary.
 */
@Component
public class EmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    private static final Duration[] BACKOFF = {Duration.ofSeconds(2), Duration.ofSeconds(10)};

    private final EmailSender sender;

    public EmailDispatcher(EmailSender sender) {
        this.sender = sender;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void dispatch(EmailMessage message) {
        for (int attempt = 0; ; attempt++) {
            try {
                sender.send(message);
                log.info("Sent '{}' email", message.kind());
                return;
            } catch (RuntimeException e) {
                if (attempt >= BACKOFF.length) {
                    // The address is deliberately not logged (personal data).
                    log.error("Giving up on '{}' email after {} attempts: {}", message.kind(), attempt + 1,
                            e.getMessage());
                    return;
                }
                log.warn("Sending '{}' email failed (attempt {}), retrying: {}", message.kind(), attempt + 1,
                        e.getMessage());
                sleep(BACKOFF[attempt]);
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
