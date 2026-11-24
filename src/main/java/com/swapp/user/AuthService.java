package com.swapp.user;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.config.SiteProperties;
import com.swapp.security.JwtService;
import com.swapp.security.JwtService.IssuedToken;
import com.swapp.security.LoginRateLimiter;
import com.swapp.user.dto.UserDtos.LoginRequest;
import com.swapp.user.dto.UserDtos.RegisterRequest;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;
    private final LoginRateLimiter rateLimiter;
    private final EmailVerificationService verification;
    private final SiteProperties site;
    /** Used to equalise timing when the email is unknown, so response time does not reveal registered emails. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwtService,
                       LoginRateLimiter rateLimiter, EmailVerificationService verification,
                       SiteProperties site) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.verification = verification;
        this.site = site;
        this.dummyHash = encoder.encode("swapp-timing-equaliser");
    }

    @Transactional
    public AuthResult register(RegisterRequest request) {
        String email = normalise(request.email());
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(request.password()));
        user.setDisplayName(request.displayName().trim());
        user.setLocation(StringUtils.hasText(request.location()) ? request.location().trim() : null);
        user.setTermsAcceptedAt(BaseEntity.now());
        user.setTermsVersion(site.termsVersion());
        users.saveAndFlush(user);
        log.info("New user registered: id={}", user.getId());
        verification.sendVerification(user);
        return new AuthResult(user, jwtService.issue(user));
    }

    @Transactional(readOnly = true)
    public AuthResult login(LoginRequest request, String clientAddress) {
        String email = normalise(request.email());
        rateLimiter.checkAllowed(clientAddress, email);
        User user = users.findByEmailIgnoreCase(email).orElse(null);
        boolean valid;
        if (user == null || !user.hasPassword()) {
            // Unknown email, or an account that signs in only with Google: same cost as a real check, same answer.
            encoder.matches(request.password(), dummyHash);
            valid = false;
        } else {
            valid = encoder.matches(request.password(), user.getPasswordHash());
        }
        if (!valid) {
            rateLimiter.recordFailure(clientAddress, email);
            throw ApiException.unauthorized("Incorrect email or password");
        }
        if (user.isSuspended()) {
            // Revealed only after a correct password, so suspension status cannot be probed.
            throw ApiException.forbidden("This account has been suspended. If you think this is a mistake, contact "
                    + site.contactEmail() + ".");
        }
        rateLimiter.reset(clientAddress, email);
        return new AuthResult(user, jwtService.issue(user));
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthResult(User user, IssuedToken token) {
    }
}
