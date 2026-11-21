package com.swapp.mail;

/**
 * A transactional email. Publish it as an application event inside the business transaction; {@link EmailDispatcher}
 * sends it only after the transaction commits, so nobody is emailed about a change that was rolled back.
 *
 * @param to      recipient address
 * @param subject subject line
 * @param text    plain-text body (always sent, for clients that do not render HTML)
 * @param html    HTML body
 * @param kind    short label for logs and metrics; never contains personal data
 */
public record EmailMessage(String to, String subject, String text, String html, String kind) {
}
