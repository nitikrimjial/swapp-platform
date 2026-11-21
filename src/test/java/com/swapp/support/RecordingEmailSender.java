package com.swapp.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.swapp.mail.EmailMessage;
import com.swapp.mail.EmailSender;

/** Captures outgoing emails instead of delivering them. Emails are sent asynchronously, so lookups wait briefly. */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]{43})");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
    }

    public void clear() {
        sent.clear();
    }

    /** Waits for an email of the given kind to the given address and returns the latest one. */
    public EmailMessage await(String to, String kind) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            Optional<EmailMessage> found = latest(to, kind);
            if (found.isPresent()) {
                return found.get();
            }
            pause(25);
        }
        throw new AssertionError("No '" + kind + "' email to " + to + " within " + TIMEOUT + "; sent: " + sent);
    }

    /** Asserts that no such email arrives within a short grace period (sending is asynchronous). */
    public void assertNone(String to, String kind) {
        pause(400);
        assertThat(latest(to, kind)).as("'%s' email to %s", kind, to).isEmpty();
    }

    public long count(String to, String kind) {
        return sent.stream().filter(m -> m.to().equals(to) && m.kind().equals(kind)).count();
    }

    /** Waits for the email and extracts the token from its link. */
    public String token(String to, String kind) {
        Matcher m = TOKEN.matcher(await(to, kind).text());
        assertThat(m.find()).as("link with token").isTrue();
        return m.group(1);
    }

    private Optional<EmailMessage> latest(String to, String kind) {
        return sent.reversed().stream().filter(m -> m.to().equals(to) && m.kind().equals(kind)).findFirst();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }
}
