package com.swapp.user;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.swapp.common.PageResponse;
import com.swapp.item.ItemService;
import com.swapp.item.ItemStatus;
import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.security.AuthCookies;
import com.swapp.security.AuthUser;
import com.swapp.security.JwtService;
import com.swapp.user.dto.UserDtos.ChangePasswordRequest;
import com.swapp.user.dto.UserDtos.PublicProfileDto;
import com.swapp.user.dto.UserDtos.UpdateProfileRequest;
import com.swapp.user.dto.UserDtos.UserDto;
import com.swapp.user.dto.UserDtos.UserSummaryCounts;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final ItemService itemService;
    private final AuthCookies cookies;
    private final JwtService jwtService;
    private final EmailVerificationService verification;

    public UserController(UserService userService, ItemService itemService, AuthCookies cookies,
                          JwtService jwtService, EmailVerificationService verification) {
        this.userService = userService;
        this.itemService = itemService;
        this.cookies = cookies;
        this.jwtService = jwtService;
        this.verification = verification;
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser me) {
        return userService.me(me.id());
    }

    @PutMapping("/me")
    public UserDto updateProfile(@AuthenticationPrincipal AuthUser me,
                                 @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(me.id(), request);
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserDto uploadAvatar(@AuthenticationPrincipal AuthUser me, @RequestPart("file") MultipartFile file) {
        return userService.updateAvatar(me.id(), file);
    }

    @DeleteMapping("/me/avatar")
    public UserDto removeAvatar(@AuthenticationPrincipal AuthUser me) {
        return userService.removeAvatar(me.id());
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser me,
                                               @Valid @RequestBody ChangePasswordRequest request,
                                               HttpServletResponse response) {
        var token = userService.changePassword(me.id(), request);
        cookies.write(response, token.token(), jwtService.expiration());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/me/verification-email")
    public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal AuthUser me) {
        verification.resend(me.id());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/me/sign-out-everywhere")
    public ResponseEntity<Void> signOutEverywhere(@AuthenticationPrincipal AuthUser me, HttpServletResponse response) {
        userService.signOutEverywhere(me.id());
        cookies.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/summary")
    public UserSummaryCounts summary(@AuthenticationPrincipal AuthUser me) {
        return userService.summary(me.id());
    }

    @GetMapping("/me/items")
    public PageResponse<ItemSummaryDto> myItems(@AuthenticationPrincipal AuthUser me,
                                                @RequestParam(required = false) ItemStatus status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "12") int size) {
        return itemService.myItems(me.id(), status, page, size);
    }

    @GetMapping("/me/offerable-items")
    public List<ItemSummaryDto> offerableItems(@AuthenticationPrincipal AuthUser me) {
        return itemService.offerableItems(me.id());
    }

    @GetMapping("/{id}")
    public PublicProfileDto publicProfile(@PathVariable Long id) {
        return userService.publicProfile(id);
    }
}
