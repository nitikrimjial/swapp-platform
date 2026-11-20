package com.swapp.user;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
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
    /** Used to equalise timing when the email is unknown, so response time does not reveal registered emails. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwtService,
                       LoginRateLimiter rateLimiter) {
        this.users = users;
        this.encoder = encoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
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
        users.saveAndFlush(user);
        log.info("New user registered: id={}", user.getId());
        return new AuthResult(user, jwtService.issue(user));
    }

    @Transactional(readOnly = true)
    public AuthResult login(LoginRequest request, String clientAddress) {
        String email = normalise(request.email());
        String limiterKey = clientAddress + "|" + email;
        rateLimiter.checkAllowed(limiterKey);
        User user = users.findByEmailIgnoreCase(email).orElse(null);
        boolean valid;
        if (user == null) {
            encoder.matches(request.password(), dummyHash); // same cost as a real check
            valid = false;
        } else {
            valid = encoder.matches(request.password(), user.getPasswordHash());
        }
        if (!valid) {
            rateLimiter.recordFailure(limiterKey);
            throw ApiException.unauthorized("Incorrect email or password");
        }
        rateLimiter.reset(limiterKey);
        return new AuthResult(user, jwtService.issue(user));
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public record AuthResult(User user, IssuedToken token) {
    }
}
