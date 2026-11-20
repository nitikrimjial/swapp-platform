package com.swapp.user;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.security.AuthCookies;
import com.swapp.security.AuthUser;
import com.swapp.security.JwtService;
import com.swapp.user.AuthService.AuthResult;
import com.swapp.user.dto.UserDtos.AuthResponse;
import com.swapp.user.dto.UserDtos.LoginRequest;
import com.swapp.user.dto.UserDtos.RegisterRequest;
import com.swapp.user.dto.UserDtos.UserDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final AuthCookies cookies;
    private final JwtService jwtService;

    public AuthController(AuthService authService, UserService userService, AuthCookies cookies,
                          JwtService jwtService) {
        this.authService = authService;
        this.userService = userService;
        this.cookies = cookies;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletResponse response) {
        AuthResult result = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(respond(result, response));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
                              HttpServletResponse response) {
        return respond(authService.login(request, httpRequest.getRemoteAddr()), response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser me) {
        return userService.me(me.id());
    }

    /** Public: lets pages discover the session state without a 401 for anonymous visitors. */
    @GetMapping("/session")
    public SessionResponse session(@AuthenticationPrincipal AuthUser me) {
        return new SessionResponse(me == null ? null : userService.me(me.id()));
    }

    public record SessionResponse(UserDto user) {
    }

    private AuthResponse respond(AuthResult result, HttpServletResponse response) {
        cookies.write(response, result.token().token(), jwtService.expiration());
        return new AuthResponse(UserDto.from(result.user()), result.token().token(), result.token().expiresAt());
    }
}
