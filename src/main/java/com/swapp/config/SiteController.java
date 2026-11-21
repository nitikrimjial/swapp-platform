package com.swapp.config;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.security.google.GoogleProperties;

/** Public deployment facts used by static pages (the contact address on the Privacy Policy, for example). */
@RestController
public class SiteController {

    private final SiteProperties site;
    private final MailSettings mail;
    private final GoogleProperties google;

    public SiteController(SiteProperties site, MailSettings mail, GoogleProperties google) {
        this.site = site;
        this.mail = mail;
        this.google = google;
    }

    @GetMapping("/api/site")
    public ResponseEntity<SiteInfo> site() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new SiteInfo(site.contactEmail(), site.termsVersion(), mail.requireVerification(),
                        google.enabled()));
    }

    public record SiteInfo(String contactEmail, String termsVersion, boolean emailVerificationRequired,
                           boolean googleSignIn) {
    }
}
