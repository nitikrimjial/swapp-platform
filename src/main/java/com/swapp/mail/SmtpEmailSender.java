package com.swapp.mail;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Delivers through any SMTP relay (Brevo, Resend, Amazon SES, Postmark...) configured under {@code spring.mail}. */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final InternetAddress from;

    public SmtpEmailSender(JavaMailSender mailSender, String fromAddress, String fromName) {
        this.mailSender = mailSender;
        try {
            this.from = new InternetAddress(fromAddress, fromName, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Invalid sender address", e);
        }
    }

    @Override
    public void send(EmailMessage message) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
            mailSender.send(mime);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build email", e);
        }
    }
}
