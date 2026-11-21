package com.swapp.moderation.dto;

import java.time.Instant;

import com.swapp.item.ItemStatus;
import com.swapp.moderation.Report;
import com.swapp.moderation.ReportReason;
import com.swapp.moderation.ReportResolution;
import com.swapp.moderation.ReportStatus;
import com.swapp.moderation.ReportTargetType;
import com.swapp.storage.StorageService;
import com.swapp.user.User;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ModerationDtos {

    private ModerationDtos() {
    }

    public record CreateReportRequest(
            @NotNull(message = "Choose what you are reporting") ReportTargetType targetType,
            @NotNull(message = "Choose what you are reporting") Long targetId,
            @NotNull(message = "Choose a reason") ReportReason reason,
            @Size(max = 1000, message = "Please keep it under 1000 characters") String details) {
    }

    /** What the reporter gets back: confirmation only, never the moderation outcome details. */
    public record ReportReceipt(Long id, ReportStatus status, Instant createdAt) {
    }

    public enum ModerationAction {
        DISMISS,
        REMOVE_ITEM,
        SUSPEND_USER
    }

    public record ResolveReportRequest(
            @NotNull(message = "Choose an action") ModerationAction action,
            @Size(max = 500, message = "Please keep the note under 500 characters") String note) {
    }

    /** The reported member as moderators see them. */
    public record ModeratedUserDto(Long id, String displayName, String avatarUrl, boolean suspended, boolean deleted,
                                   long openReports) {
        public static ModeratedUserDto from(User u, long openReports) {
            return new ModeratedUserDto(u.getId(), u.getDisplayName(), StorageService.publicUrl(u.getAvatarKey()),
                    u.isSuspended(), u.isDeleted(), openReports);
        }
    }

    public record ReportDto(Long id, ReportTargetType targetType, ReportReason reason, String details,
                            String evidence, ReportStatus status, ReportResolution resolution, String resolutionNote,
                            Instant createdAt, Instant resolvedAt, UserSummaryDto reporter,
                            ModeratedUserDto reportedUser, Long itemId, String itemTitle, ItemStatus itemStatus,
                            Long messageId, String resolvedBy) {
        public static ReportDto from(Report r, long reportedUserOpenReports) {
            return new ReportDto(r.getId(), r.getTargetType(), r.getReason(), r.getDetails(), r.getEvidence(),
                    r.getStatus(), r.getResolution(), r.getResolutionNote(), r.getCreatedAt(), r.getResolvedAt(),
                    UserSummaryDto.from(r.getReporter()),
                    ModeratedUserDto.from(r.getReportedUser(), reportedUserOpenReports),
                    r.getItem() == null ? null : r.getItem().getId(),
                    r.getItem() == null ? null : r.getItem().getTitle(),
                    r.getItem() == null ? null : r.getItem().getStatus(),
                    r.getMessage() == null ? null : r.getMessage().getId(),
                    r.getResolvedBy() == null ? null : r.getResolvedBy().getDisplayName());
        }
    }

    public record SuspendedUserDto(Long id, String displayName, String avatarUrl, Instant suspendedAt,
                                   String reason) {
        public static SuspendedUserDto from(User u) {
            return new SuspendedUserDto(u.getId(), u.getDisplayName(), StorageService.publicUrl(u.getAvatarKey()),
                    u.getSuspendedAt(), u.getSuspensionReason());
        }
    }

    public record ModerationSummary(long openReports, long suspendedMembers) {
    }
}
