package com.swapp.mail;

/** Delivers one email synchronously. Implementations throw on failure so the dispatcher can retry. */
public interface EmailSender {

    void send(EmailMessage message);
}
