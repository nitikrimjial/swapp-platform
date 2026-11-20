package com.swapp.exchange;

import java.util.Collection;
import java.util.EnumSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.common.BaseEntity;
import com.swapp.common.PageResponse;
import com.swapp.exchange.dto.ExchangeDto;
import com.swapp.item.Item;
import com.swapp.item.ItemLocker;
import com.swapp.item.ItemStatus;
import com.swapp.notification.NotificationService;
import com.swapp.notification.NotificationType;
import com.swapp.user.User;

/**
 * Tracks accepted exchanges. Completion requires confirmation from <em>both</em> participants, so one party cannot
 * close an exchange alone (accidentally or otherwise). Either party may cancel an active exchange, which releases the
 * reserved items back to the marketplace.
 */
@Service
@Transactional
public class ExchangeService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);

    private final ExchangeRepository exchanges;
    private final ItemLocker locker;
    private final NotificationService notifications;

    public ExchangeService(ExchangeRepository exchanges, ItemLocker locker, NotificationService notifications) {
        this.exchanges = exchanges;
        this.locker = locker;
        this.notifications = notifications;
    }

    public ExchangeDto confirm(Long exchangeId, Long userId) {
        Exchange exchange = lockForParticipant(exchangeId, userId);
        boolean isOwner = exchange.getOwner().getId().equals(userId);
        if ((isOwner ? exchange.getOwnerConfirmedAt() : exchange.getRequesterConfirmedAt()) != null) {
            throw ApiException.conflict("You have already confirmed this exchange");
        }
        if (isOwner) {
            exchange.setOwnerConfirmedAt(BaseEntity.now());
        } else {
            exchange.setRequesterConfirmedAt(BaseEntity.now());
        }

        User me = isOwner ? exchange.getOwner() : exchange.getRequester();
        User other = exchange.otherParty(userId);
        String link = "/exchanges.html?id=" + exchange.getId();
        if (exchange.getOwnerConfirmedAt() != null && exchange.getRequesterConfirmedAt() != null) {
            exchange.setStatus(ExchangeStatus.COMPLETED);
            exchange.setCompletedAt(BaseEntity.now());
            exchange.getOwnerItem().setStatus(ItemStatus.COMPLETED);
            exchange.getRequesterItem().setStatus(ItemStatus.COMPLETED);
            notifications.notify(other, NotificationType.EXCHANGE_COMPLETED, "Exchange completed",
                    "Your exchange of \"" + exchange.getOwnerItem().getTitle() + "\" for \""
                            + exchange.getRequesterItem().getTitle() + "\" is complete. Enjoy!", link);
            log.info("Exchange {} completed", exchangeId);
        } else {
            notifications.notify(other, NotificationType.EXCHANGE_CONFIRMED, "Hand-over confirmed by " + me.getDisplayName(),
                    me.getDisplayName() + " marked the exchange as complete. Confirm on your side to finish it.", link);
            log.info("Exchange {} confirmed by user {}", exchangeId, userId);
        }
        return ExchangeDto.from(exchange);
    }

    public ExchangeDto cancel(Long exchangeId, Long userId) {
        Exchange exchange = lockForParticipant(exchangeId, userId);
        exchange.setStatus(ExchangeStatus.CANCELLED);
        exchange.setCancelledAt(BaseEntity.now());
        User me = exchange.getOwner().getId().equals(userId) ? exchange.getOwner() : exchange.getRequester();
        exchange.setCancelledBy(me);
        release(exchange.getOwnerItem());
        release(exchange.getRequesterItem());
        notifications.notify(exchange.otherParty(userId), NotificationType.EXCHANGE_CANCELLED, "Exchange cancelled",
                me.getDisplayName() + " cancelled the exchange of \"" + exchange.getOwnerItem().getTitle()
                        + "\" for \"" + exchange.getRequesterItem().getTitle()
                        + "\". Both items are available again.",
                "/exchanges.html?id=" + exchange.getId());
        log.info("Exchange {} cancelled by user {}", exchangeId, userId);
        return ExchangeDto.from(exchange);
    }

    @Transactional(readOnly = true)
    public PageResponse<ExchangeDto> list(Long userId, ExchangeStatus status, int page, int size) {
        Collection<ExchangeStatus> statuses = status == null ? EnumSet.allOf(ExchangeStatus.class) : EnumSet.of(status);
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return PageResponse.from(exchanges.findForUser(userId, statuses, pageable), ExchangeDto::from);
    }

    @Transactional(readOnly = true)
    public ExchangeDto get(Long exchangeId, Long userId) {
        Exchange exchange = exchanges.findWithDetailsById(exchangeId)
                .orElseThrow(() -> ApiException.notFound("Exchange"));
        if (!exchange.isParticipant(userId)) {
            throw ApiException.notFound("Exchange");
        }
        return ExchangeDto.from(exchange);
    }

    private Exchange lockForParticipant(Long exchangeId, Long userId) {
        var refs = exchanges.findRefs(exchangeId).orElseThrow(() -> ApiException.notFound("Exchange"));
        if (!refs.getOwnerId().equals(userId) && !refs.getRequesterId().equals(userId)) {
            throw ApiException.notFound("Exchange");
        }
        locker.lockAny(refs.getOwnerItemId(), refs.getRequesterItemId());
        Exchange exchange = exchanges.lockById(exchangeId).orElseThrow(() -> ApiException.notFound("Exchange"));
        if (exchange.getStatus() != ExchangeStatus.ACTIVE) {
            throw ApiException.conflict("This exchange is no longer active");
        }
        return exchange;
    }

    private static void release(Item item) {
        if (item.getStatus() == ItemStatus.RESERVED) {
            item.setStatus(ItemStatus.AVAILABLE);
        }
    }
}
