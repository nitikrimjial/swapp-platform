package com.swapp.donation.dto;

import java.time.Instant;

import com.swapp.donation.DonationRequest;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class DonationDtos {

    private DonationDtos() {
    }

    public record CreateDonationRequest(
            @NotNull(message = "Item is required") Long itemId,
            @Size(max = 1000, message = "Message must be at most 1000 characters") String message) {
    }

    public record DonationRequestDto(Long id, ItemSummaryDto item, UserSummaryDto requester, UserSummaryDto owner,
                                     String message, DonationRequestStatus status, String resolutionNote,
                                     Instant createdAt, Instant updatedAt) {
        public static DonationRequestDto from(DonationRequest r) {
            return new DonationRequestDto(r.getId(), ItemSummaryDto.from(r.getItem()),
                    UserSummaryDto.from(r.getRequester()), UserSummaryDto.from(r.getOwner()), r.getMessage(),
                    r.getStatus(), r.getResolutionNote(), r.getCreatedAt(), r.getUpdatedAt());
        }
    }
}
