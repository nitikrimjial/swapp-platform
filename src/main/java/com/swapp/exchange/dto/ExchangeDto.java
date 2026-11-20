package com.swapp.exchange.dto;

import java.time.Instant;

import com.swapp.exchange.Exchange;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.item.dto.ItemDtos.ItemSummaryDto;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

public record ExchangeDto(Long id, Long offerId, ExchangeStatus status, UserSummaryDto owner, UserSummaryDto requester,
                          ItemSummaryDto ownerItem, ItemSummaryDto requesterItem, Instant ownerConfirmedAt,
                          Instant requesterConfirmedAt, Instant completedAt, Instant cancelledAt,
                          UserSummaryDto cancelledBy, Instant createdAt, Instant updatedAt) {

    public static ExchangeDto from(Exchange e) {
        return new ExchangeDto(e.getId(), e.getOffer().getId(), e.getStatus(), UserSummaryDto.from(e.getOwner()),
                UserSummaryDto.from(e.getRequester()), ItemSummaryDto.from(e.getOwnerItem()),
                ItemSummaryDto.from(e.getRequesterItem()), e.getOwnerConfirmedAt(), e.getRequesterConfirmedAt(),
                e.getCompletedAt(), e.getCancelledAt(), UserSummaryDto.from(e.getCancelledBy()), e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
