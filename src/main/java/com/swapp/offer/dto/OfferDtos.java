package com.swapp.offer.dto;

import java.time.Instant;

import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.offer.Offer;
import com.swapp.offer.OfferStatus;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class OfferDtos {

    private OfferDtos() {
    }

    public record CreateOfferRequest(
            @NotNull(message = "Requested item is required") Long requestedItemId,
            @NotNull(message = "Choose one of your items to offer") Long offeredItemId,
            @Size(max = 1000, message = "Message must be at most 1000 characters") String message) {
    }

    public record OfferDto(Long id, ItemSummaryDto requestedItem, ItemSummaryDto offeredItem, UserSummaryDto offerer,
                           UserSummaryDto recipient, String message, OfferStatus status, String resolutionNote,
                           Long exchangeId, Instant createdAt, Instant updatedAt) {
        public static OfferDto from(Offer o, Long exchangeId) {
            return new OfferDto(o.getId(), ItemSummaryDto.from(o.getRequestedItem()),
                    ItemSummaryDto.from(o.getOfferedItem()), UserSummaryDto.from(o.getOfferer()),
                    UserSummaryDto.from(o.getRecipient()), o.getMessage(), o.getStatus(), o.getResolutionNote(),
                    exchangeId, o.getCreatedAt(), o.getUpdatedAt());
        }
    }
}
