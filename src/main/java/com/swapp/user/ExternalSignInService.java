package com.swapp.user;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.config.SiteProperties;
import com.swapp.mail.AccountEmails;
import com.swapp.realtime.SessionsRevokedEvent;
import com.swapp.security.JwtService;
import com.swapp.security.JwtService.IssuedToken;
import com.swapp.user.AuthService.AuthResult;
import com.swapp.user.dto.UserDtos.CompleteSignupRequest;
import com.swapp.user.dto.UserDtos.IdentityDto;
import com.swapp.user.identity.ExternalProfile;
import com.swapp.user.identity.IdentityProvider;
import com.swapp.user.identity.UserIdentity;
import com.swapp.user.identity.UserIdentityRepository;

/**
 * Business rules for signing in with an external provider (Google). The provider has already authenticated the person
 * and its ID token has been validated; this class decides which Swapp account, if any, that person gets.
 *
 * <ul>
 * <li>A returning person is recognised by the provider's permanent subject id, never by email.</li>
 * <li>Only a provider-verified email may be matched to an existing account. If that account's own email was never
 * verified, its password is removed: whoever registered it may not own the address (pre-account-takeover defence).</li>
 * <li>A new person gets no account until they accept the Terms and confirm they are 18+ ({@link #completeSignup}).</li>
 * </ul>
 */
@Service
@Transactional
public class ExternalSignInService {

    private static final Logger log = LoggerFactory.getLogger(ExternalSignInService.class);

    private final UserRepository users;
    private final UserIdentityRepository identities;
    private final JwtService jwtService;
    private final AccountEmails emails;
    private final ApplicationEventPublisher events;
    private final SiteProperties site;

    public ExternalSignInService(UserRepository users, UserIdentityRepository identities, JwtService jwtService,
                                 AccountEmails emails, ApplicationEventPublisher events, SiteProperties site) {
        this.users = users;
        this.identities = identities;
        this.jwtService = jwtService;
        this.emails = emails;
        this.events = events;
        this.site = site;
    }

    /** Why a sign-in or link was refused; {@code code} goes into the redirect URL for the page to explain. */
    public enum Refusal {
        UNVERIFIED_EMAIL("unverified", "Your Google account's email address isn't verified. Verify it with Google, "
                + "or sign up with email and password."),
        SUSPENDED("suspended", null),
        IN_USE("in_use", "That Google account is already connected to a different Swapp account."),
        ALREADY_CONNECTED("already_connected", "Your Swapp account is already connected to a different Google "
                + "account. Disconnect it in Settings first.");

        private final String code;
        private final String message;

        Refusal(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String code() {
            return code;
        }
    }

    public sealed interface Outcome {
    }

    /** @param linked true if the provider account was connected to an existing Swapp account just now */
    public record SignedIn(User user, IssuedToken token, boolean linked) implements Outcome {
    }

    /** New person: they must accept the Terms first. The token goes into an HttpOnly cookie. */
    public record SignupRequired(String signupToken) implements Outcome {
    }

    public record Connected() implements Outcome {
    }

    public record Refused(Refusal refusal) implements Outcome {
    }

    /** Someone came back from the provider without being signed in to Swapp. */
    public Outcome signIn(ExternalProfile profile) {
        if (!profile.emailVerified() || !StringUtils.hasText(profile.email())) {
            return new Refused(Refusal.UNVERIFIED_EMAIL);
        }
        var known = identities.findByProviderAndSubject(profile.provider(), profile.subject());
        if (known.isPresent()) {
            UserIdentity identity = known.get();
            User user = identity.getUser();
            if (!user.isActive()) {
                return new Refused(Refusal.SUSPENDED); // deleted accounts have no identities left
            }
            identity.setEmail(normalise(profile.email()));
            identity.setLastUsedAt(BaseEntity.now());
            return new SignedIn(user, jwtService.issue(user), false);
        }

        User existing = users.findByEmailIgnoreCase(normalise(profile.email())).orElse(null);
        if (existing == null) {
            return new SignupRequired(jwtService.issueSignup(profile));
        }
        if (existing.isSuspended()) {
            return new Refused(Refusal.SUSPENDED);
        }
        if (identities.findByUserIdAndProvider(existing.getId(), profile.provider()).isPresent()) {
            return new Refused(Refusal.ALREADY_CONNECTED);
        }
        if (!existing.isEmailVerified()) {
            // The provider proves this person owns the address; the unverified registrant may not have. Drop the
            // password they chose and end their sessions. The real owner can set a new password by email at any time.
            boolean hadPassword = existing.hasPassword();
            existing.setPasswordHash(null);
            EmailVerificationService.markVerified(existing);
            existing.revokeTokens();
            events.publishEvent(new SessionsRevokedEvent(existing.getId()));
            log.info("User {} had an unverified email; verified by {} sign-in{}", existing.getId(),
                    profile.provider(), hadPassword ? ", password removed" : "");
        }
        addIdentity(existing, profile);
        users.saveAndFlush(existing);
        return new SignedIn(existing, jwtService.issue(existing), true);
    }

    /** A signed-in member connects a provider account from Settings. Its email may differ from theirs. */
    public Outcome connect(Long userId, ExternalProfile profile) {
        User user = users.findById(userId).filter(User::isActive).orElseThrow(() -> ApiException.notFound("User"));
        if (!profile.emailVerified()) {
            return new Refused(Refusal.UNVERIFIED_EMAIL);
        }
        var known = identities.findByProviderAndSubject(profile.provider(), profile.subject());
        if (known.isPresent()) {
            return known.get().getUser().getId().equals(userId) ? new Connected() : new Refused(Refusal.IN_USE);
        }
        if (identities.findByUserIdAndProvider(userId, profile.provider()).isPresent()) {
            return new Refused(Refusal.ALREADY_CONNECTED);
        }
        addIdentity(user, profile);
        return new Connected();
    }

    /** Creates the account once the person has accepted the Terms; signs them in if it now exists after all. */
    public AuthResult completeSignup(ExternalProfile profile, CompleteSignupRequest request) {
        // Between leaving Google and accepting the Terms, the account may have been created or linked elsewhere.
        Outcome outcome = signIn(profile);
        if (outcome instanceof SignedIn signedIn) {
            return new AuthResult(signedIn.user(), signedIn.token());
        }
        if (outcome instanceof Refused refused) {
            throw refusal(refused.refusal());
        }
        User user = new User();
        user.setEmail(normalise(profile.email()));
        user.setPasswordHash(null);
        user.setDisplayName(request.displayName().trim());
        user.setLocation(StringUtils.hasText(request.location()) ? request.location().trim() : null);
        user.setEmailVerifiedAt(BaseEntity.now()); // verified by the provider
        user.setTermsAcceptedAt(BaseEntity.now());
        user.setTermsVersion(site.termsVersion());
        users.saveAndFlush(user);
        saveIdentity(user, profile); // no "sign-in added" alert: it is how the account was created
        log.info("New user registered with {}: id={}", profile.provider(), user.getId());
        return new AuthResult(user, jwtService.issue(user));
    }

    @Transactional(readOnly = true)
    public List<IdentityDto> identities(Long userId) {
        return identities.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(i -> new IdentityDto(i.getProvider().name(), i.getProvider().label(), i.getEmail(),
                        i.getCreatedAt(), i.getLastUsedAt()))
                .toList();
    }

    /** Refused if it would leave the member with no way to sign in. */
    public void disconnect(Long userId, IdentityProvider provider) {
        User user = users.findById(userId).filter(User::isActive).orElseThrow(() -> ApiException.notFound("User"));
        UserIdentity identity = identities.findByUserIdAndProvider(userId, provider)
                .orElseThrow(() -> ApiException.notFound("Connected account"));
        if (!user.hasPassword()) {
            throw ApiException.conflict("Set a password first, so you can still sign in after disconnecting "
                    + provider.label() + ".");
        }
        identities.delete(identity);
        events.publishEvent(emails.signInMethodRemoved(user.getEmail(), user.getDisplayName(), provider.label()));
        log.info("User {} disconnected {}", userId, provider);
    }

    public ApiException refusal(Refusal refusal) {
        if (refusal == Refusal.SUSPENDED) {
            return ApiException.forbidden("This account has been suspended. If you think this is a mistake, contact "
                    + site.contactEmail() + ".");
        }
        return new ApiException(HttpStatus.CONFLICT, refusal.message);
    }

    /** Connects the provider account to an existing member and alerts them by email. */
    private void addIdentity(User user, ExternalProfile profile) {
        UserIdentity identity = saveIdentity(user, profile);
        events.publishEvent(emails.signInMethodAdded(user.getEmail(), user.getDisplayName(),
                profile.provider().label(), identity.getEmail()));
        log.info("User {} connected {}", user.getId(), profile.provider());
    }

    private UserIdentity saveIdentity(User user, ExternalProfile profile) {
        UserIdentity identity = new UserIdentity();
        identity.setUser(user);
        identity.setProvider(profile.provider());
        identity.setSubject(profile.subject());
        identity.setEmail(normalise(profile.email()));
        identity.setLastUsedAt(BaseEntity.now());
        return identities.save(identity);
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
