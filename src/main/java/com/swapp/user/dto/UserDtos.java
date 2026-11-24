package com.swapp.user.dto;

import java.time.Instant;

import com.swapp.common.MaxUtf8Bytes;
import com.swapp.storage.StorageService;
import com.swapp.user.Role;
import com.swapp.user.User;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response payloads for authentication and user profiles. */
public final class UserDtos {

    private UserDtos() {
    }

    private static final String PASSWORD_RULE = "^(?=.*[A-Za-z])(?=.*\\d).+$";
    private static final String PASSWORD_MESSAGE = "Password must contain at least one letter and one number";
    private static final String PASSWORD_BYTES_MESSAGE =
            "Password is too long (non-English letters and emoji count as several characters)";

    public record RegisterRequest(
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address")
            @Size(max = 255) String email,
            @NotBlank(message = "Password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            @MaxUtf8Bytes(value = 72, message = PASSWORD_BYTES_MESSAGE)
            @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password,
            @NotBlank(message = "Name is required") @Size(min = 2, max = 80, message = "Name must be 2-80 characters")
            String displayName,
            @Size(max = 120, message = "Location must be at most 120 characters") String location,
            @AssertTrue(message = "Please confirm you are 18 or older and accept the Terms and Privacy Policy")
            boolean acceptTerms) {
    }

    public record LoginRequest(
            @NotBlank(message = "Email is required") @Size(max = 255) String email,
            @NotBlank(message = "Password is required") @Size(max = 72)
            @MaxUtf8Bytes(value = 72, message = PASSWORD_BYTES_MESSAGE) String password) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "Name is required") @Size(min = 2, max = 80, message = "Name must be 2-80 characters")
            String displayName,
            @Size(max = 500, message = "Bio must be at most 500 characters") String bio,
            @Size(max = 120, message = "Location must be at most 120 characters") String location,
            @DecimalMin(value = "-90", message = "Invalid latitude") @DecimalMax(value = "90", message = "Invalid latitude")
            Double latitude,
            @DecimalMin(value = "-180", message = "Invalid longitude") @DecimalMax(value = "180", message = "Invalid longitude")
            Double longitude) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Current password is required") @Size(max = 72)
            @MaxUtf8Bytes(value = 72, message = PASSWORD_BYTES_MESSAGE) String currentPassword,
            @NotBlank(message = "New password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            @MaxUtf8Bytes(value = 72, message = PASSWORD_BYTES_MESSAGE)
            @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record ForgotPasswordRequest(
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email address")
            @Size(max = 255) String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "This reset link is incomplete") @Size(max = 100) String token,
            @NotBlank(message = "New password is required")
            @Size(min = 8, max = 72, message = "Password must be 8-72 characters")
            @MaxUtf8Bytes(value = 72, message = PASSWORD_BYTES_MESSAGE)
            @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String newPassword) {
    }

    /** The password is required when the account has one; a Google-only account needs a recent sign-in instead. */
    public record DeleteAccountRequest(
            @Size(max = 72) @MaxUtf8Bytes(value = 72, message = PASSWORD_BYTES_MESSAGE) String password) {
    }

    /** Finishes creating an account for someone who came back from Google (see GoogleSignInController). */
    public record CompleteSignupRequest(
            @NotBlank(message = "Name is required") @Size(min = 2, max = 80, message = "Name must be 2-80 characters")
            String displayName,
            @Size(max = 120, message = "Location must be at most 120 characters") String location,
            @AssertTrue(message = "Please confirm you are 18 or older and accept the Terms and Privacy Policy")
            boolean acceptTerms) {
    }

    /** What the finish-sign-up page shows: the Google email (fixed) and a suggested name. */
    public record PendingSignupDto(String provider, String email, String suggestedName) {
    }

    /** A provider account linked to the member, shown in Settings. */
    public record IdentityDto(String provider, String label, String email, Instant linkedAt, Instant lastUsedAt) {
    }

    public record VerifyEmailRequest(
            @NotBlank(message = "This verification link is incomplete") @Size(max = 100) String token) {
    }

    /** Full profile, only ever returned to the user themself. */
    public record UserDto(Long id, String email, String displayName, String bio, String location, Double latitude,
                          Double longitude, String avatarUrl, Role role, boolean emailVerified, boolean hasPassword,
                          Instant createdAt) {
        public static UserDto from(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getDisplayName(), u.getBio(), u.getLocation(),
                    u.getLatitude(), u.getLongitude(), StorageService.publicUrl(u.getAvatarKey()), u.getRole(),
                    u.isEmailVerified(), u.hasPassword(), u.getCreatedAt());
        }
    }

    /** Minimal public identity embedded in other resources. Never includes email. */
    public record UserSummaryDto(Long id, String displayName, String avatarUrl, String location) {
        public static UserSummaryDto from(User u) {
            if (u == null) {
                return null;
            }
            return new UserSummaryDto(u.getId(), u.getDisplayName(), StorageService.publicUrl(u.getAvatarKey()),
                    u.getLocation());
        }
    }

    public record PublicProfileDto(Long id, String displayName, String bio, String location, String avatarUrl,
                                   Instant memberSince, long activeListings, long completedExchanges,
                                   long itemsGivenAway, Boolean blockedByMe) {
    }

    public record AuthResponse(UserDto user, String accessToken, Instant expiresAt) {
    }

    public record UserSummaryCounts(long unreadNotifications, long unreadMessages, long pendingIncomingOffers,
                                    long pendingOutgoingOffers, long activeExchanges, long pendingDonationRequests,
                                    long activeListings) {
    }
}
