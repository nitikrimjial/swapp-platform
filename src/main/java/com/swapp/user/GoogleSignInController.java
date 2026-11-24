package com.swapp.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.common.ApiException;
import com.swapp.security.AuthCookies;
import com.swapp.security.JwtService;
import com.swapp.user.AuthService.AuthResult;
import com.swapp.user.dto.UserDtos.AuthResponse;
import com.swapp.user.dto.UserDtos.CompleteSignupRequest;
import com.swapp.user.dto.UserDtos.PendingSignupDto;
import com.swapp.user.dto.UserDtos.UserDto;
import com.swapp.user.identity.ExternalProfile;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Second half of signing up with Google: the person has come back from Google, and the HttpOnly {@code swapp_signup}
 * cookie proves which Google account they used. No account exists until they accept the Terms here.
 */
@RestController
@RequestMapping("/api/auth/google")
public class GoogleSignInController {

    private final ExternalSignInService signIn;
    private final AuthCookies cookies;
    private final JwtService jwtService;

    public GoogleSignInController(ExternalSignInService signIn, AuthCookies cookies, JwtService jwtService) {
        this.signIn = signIn;
        this.cookies = cookies;
        this.jwtService = jwtService;
    }

    /**
     * What the finish-sign-up page shows. 204 when there is no valid pending sign-up (expired or never started): an
     * expected state for that page, so it is not reported as an error (no console noise), like {@code /auth/session}.
     */
    @GetMapping("/pending")
    public ResponseEntity<PendingSignupDto> pending(HttpServletRequest request) {
        return jwtService.verifySignup(cookies.readSignup(request))
                .map(p -> ResponseEntity.ok(new PendingSignupDto(p.provider().label(), p.email(), p.name())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/complete")
    public ResponseEntity<AuthResponse> complete(@Valid @RequestBody CompleteSignupRequest body,
                                                 HttpServletRequest request, HttpServletResponse response) {
        AuthResult result = signIn.completeSignup(pendingProfile(request), body);
        cookies.clearSignup(response);
        cookies.write(response, result.token().token(), jwtService.expiration());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new AuthResponse(UserDto.from(result.user()), result.token().token(), result.token().expiresAt()));
    }

    private ExternalProfile pendingProfile(HttpServletRequest request) {
        return jwtService.verifySignup(cookies.readSignup(request))
                .orElseThrow(() -> ApiException.notFound("Pending sign-up"));
    }
}
