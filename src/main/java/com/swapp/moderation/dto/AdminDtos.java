package com.swapp.moderation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.swapp.item.Item;
import com.swapp.item.ItemStatus;
import com.swapp.item.ListingType;
import com.swapp.moderation.audit.AuditAction;
import com.swapp.moderation.audit.AuditEntry;
import com.swapp.storage.StorageService;
import com.swapp.user.Role;
import com.swapp.user.User;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What staff see in the admin panel. Never returned to ordinary members. */
public final class AdminDtos {

    private AdminDtos() {
    }

    public enum MemberFilter {
        ALL,
        SUSPENDED,
        STAFF,
        UNVERIFIED
    }

    /** A member named in a list. Erased accounts keep their id but show the placeholder name. */
    public record MemberRef(Long id, String displayName, boolean deleted) {
        public static MemberRef from(User u) {
            return new MemberRef(u.getId(), u.getDisplayName(), u.isDeleted());
        }
    }

    public record AdminUserRow(Long id, String displayName, String avatarUrl, String email, Role role,
                               Instant createdAt, boolean emailVerified, boolean suspended) {
        public static AdminUserRow from(User u) {
            return new AdminUserRow(u.getId(), u.getDisplayName(), StorageService.publicUrl(u.getAvatarKey()),
                    u.getEmail(), u.getRole(), u.getCreatedAt(), u.isEmailVerified(), u.isSuspended());
        }
    }

    public record MemberActivity(long liveListings, long completedExchanges, long openReportsAbout,
                                 long totalReportsAbout, long reportsFiled) {
    }

    /** {@code email} is null for an erased account. {@code signInMethods} holds PASSWORD and/or provider names. */
    public record AdminUserDetail(Long id, String displayName, String avatarUrl, String email, Role role, String bio,
                                  String location, Instant createdAt, boolean emailVerified,
                                  List<String> signInMethods, Instant suspendedAt, String suspensionReason,
                                  boolean deleted, MemberActivity activity, List<AdminItemRow> recentListings,
                                  List<AuditEntryDto> recentActions) {
    }

    public record AdminItemRow(Long id, String title, ItemStatus status, ListingType listingType, String category,
                               Instant createdAt, String coverUrl, MemberRef owner) {
        public static AdminItemRow from(Item i) {
            return new AdminItemRow(i.getId(), i.getTitle(), i.getStatus(), i.getListingType(),
                    i.getCategory().getName(), i.getCreatedAt(), StorageService.publicUrl(i.coverImageKey()),
                    MemberRef.from(i.getOwner()));
        }
    }

    public record RemoveListingRequest(
            @NotBlank(message = "Give a reason; the owner will see it")
            @Size(max = 500, message = "Please keep the reason under 500 characters") String reason) {
    }

    public record AuditEntryDto(Long id, AuditAction action, Instant createdAt, MemberRef actor, MemberRef targetUser,
                                Long itemId, String itemTitle, Long reportId, String note) {
        public static AuditEntryDto from(AuditEntry e) {
            return new AuditEntryDto(e.getId(), e.getAction(), e.getCreatedAt(), MemberRef.from(e.getActor()),
                    MemberRef.from(e.getTargetUser()), e.getItem() == null ? null : e.getItem().getId(),
                    e.getItem() == null ? null : e.getItem().getTitle(), e.getReportId(), e.getNote());
        }
    }

    public record OverviewTotals(long members, long activeListings, long completedSwaps, long openReports,
                                 long suspendedMembers) {
    }

    /** One calendar day in the requested time zone. */
    public record DailyPoint(LocalDate date, long signUps, long listings, long completedSwaps, long reports) {
    }

    public record Overview(OverviewTotals totals, String timeZone, List<DailyPoint> days) {
    }
}
