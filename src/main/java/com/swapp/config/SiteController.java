package com.swapp.config;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public deployment facts used by static pages (the contact address on the Privacy Policy, for example). */
@RestController
public class SiteController {

    private final SiteProperties site;
    private final MailSettings mail;

    public SiteController(SiteProperties site, MailSettings mail) {
        this.site = site;
        this.mail = mail;
    }

    @GetMapping("/api/site")
    public ResponseEntity<SiteInfo> site() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new SiteInfo(site.contactEmail(), site.termsVersion(), mail.requireVerification()));
    }

    public record SiteInfo(String contactEmail, String termsVersion, boolean emailVerificationRequired) {
    }
}
