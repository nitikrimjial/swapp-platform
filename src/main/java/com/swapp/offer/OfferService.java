package com.swapp.offer;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.swapp.common.ApiException;
import com.swapp.common.PageResponse;
import com.swapp.exchange.Exchange;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.item.Item;
import com.swapp.item.ItemLocker;
import com.swapp.item.ItemStatus;
import com.swapp.item.ListingType;
import com.swapp.notification.NotificationService;
import com.swapp.notification.NotificationType;
import com.swapp.offer.dto.OfferDtos.CreateOfferRequest;
import com.swapp.offer.dto.OfferDtos.OfferDto;

/**
 * Exchange-offer workflow. Every state transition is transactional and acquires row locks (items first, then the
 * offer) so concurrent accepts, cancels and listing changes are serialised and conflicting exchanges are impossible.
 */
@Service
@Transactional
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final OfferRepository offers;
    private final ExchangeRepository exchanges;
    private final ItemLocker locker;
    private final NotificationService notifications;

    public OfferService(OfferRepository offers, ExchangeRepository exchanges, ItemLocker locker,
                        NotificationService notifications) {
        this.offers = offers;
        this.exchanges = exchanges;
        this.locker = locker;
        this.notifications = notifications;
    }

    public OfferDto create(Long userId, CreateOfferRequest request) {
        if (request.requestedItemId().equals(request.offeredItemId())) {
            throw ApiException.badRequest("An item cannot be offered in exchange for itself");
        }
        Map<Long, Item> locked = locker.lockActive(request.requestedItemId(), request.offeredItemId());
        Item requested = locked.get(request.requestedItemId());
        Item offered = locked.get(request.offeredItemId());

        if (requested.isOwnedBy(userId)) {
            throw ApiException.badRequest("You cannot make an offer on your own listing");
        }
        if (requested.getListingType() != ListingType.EXCHANGE) {
            throw ApiException.badRequest("This listing does not accept exchange offers");
        }
        if (!requested.isAvailable()) {
            throw ApiException.conflict("This item is no longer available");
        }
        if (!offered.isOwnedBy(userId)) {
            throw ApiException.forbidden("You can only offer items that you own");
        }
        if (offered.getListingType() != ListingType.EXCHANGE) {
            throw ApiException.badRequest("Only items listed for exchange can be offered");
        }
        if (!offered.isAvailable()) {
            throw ApiException.conflict("The item you offered is not available");
        }
        if (offers.existsByRequestedItemIdAndOfferedItemIdAndStatus(requested.getId(), offered.getId(),
                OfferStatus.PENDING)) {
            throw ApiException.conflict("You already have a pending offer with this item for this listing");
        }

        Offer offer = new Offer();
        offer.setRequestedItem(requested);
        offer.setOfferedItem(offered);
        offer.setOfferer(offered.getOwner());
        offer.setRecipient(requested.getOwner());
        offer.setMessage(StringUtils.hasText(request.message()) ? request.message().trim() : null);
        offers.save(offer);

        notifications.notify(requested.getOwner(), NotificationType.OFFER_RECEIVED,
                "New offer for \"" + requested.getTitle() + "\"",
                offered.getOwner().getDisplayName() + " offered their \"" + offered.getTitle() + "\" in exchange.",
                "/offers-incoming.html");
        log.info("Offer {} created: item {} offered for item {} by user {}", offer.getId(), offered.getId(),
                requested.getId(), userId);
        return OfferDto.from(offer, null);
    }

    public OfferDto accept(Long offerId, Long userId) {
        var refs = offers.findRefs(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (!refs.getRecipientId().equals(userId)) {
            throw ApiException.forbidden("Only the owner of the requested item can accept this offer");
        }
        Map<Long, Item> locked = locker.lockAny(refs.getRequestedItemId(), refs.getOfferedItemId());
        Offer offer = offers.lockById(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (!offer.isPending()) {
            throw ApiException.conflict("This offer is no longer pending");
        }
        Item requested = locked.get(refs.getRequestedItemId());
        Item offered = locked.get(refs.getOfferedItemId());
        if (!requested.isAvailable()) {
            throw ApiException.conflict("Your item is no longer available for exchange");
        }
        if (!offered.isAvailable()) {
            throw ApiException.conflict("The offered item is no longer available");
        }

        offer.setStatus(OfferStatus.ACCEPTED);
        offer.setResolutionNote("Accepted by the owner");
        requested.setStatus(ItemStatus.RESERVED);
        offered.setStatus(ItemStatus.RESERVED);

        Exchange exchange = new Exchange();
        exchange.setOffer(offer);
        exchange.setOwner(offer.getRecipient());
        exchange.setRequester(offer.getOfferer());
        exchange.setOwnerItem(requested);
        exchange.setRequesterItem(offered);
        exchanges.save(exchange);

        closePendingOffersInvolving(List.of(requested, offered), offer.getId());

        notifications.notify(offer.getOfferer(), NotificationType.OFFER_ACCEPTED,
                "Offer accepted: \"" + requested.getTitle() + "\"",
                offer.getRecipient().getDisplayName() + " accepted your \"" + offered.getTitle()
                        + "\". Coordinate the hand-over in Exchanges.",
                "/exchanges.html?id=" + exchange.getId());
        log.info("Offer {} accepted; exchange {} created", offerId, exchange.getId());
        return OfferDto.from(offer, exchange.getId());
    }

    public OfferDto reject(Long offerId, Long userId) {
        Offer offer = offers.lockById(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (!offer.getRecipient().getId().equals(userId)) {
            throw ApiException.forbidden("Only the owner of the requested item can reject this offer");
        }
        if (!offer.isPending()) {
            throw ApiException.conflict("This offer is no longer pending");
        }
        offer.setStatus(OfferStatus.REJECTED);
        offer.setResolutionNote("Declined by the owner");
        notifications.notify(offer.getOfferer(), NotificationType.OFFER_REJECTED,
                "Offer declined: \"" + offer.getRequestedItem().getTitle() + "\"",
                offer.getRecipient().getDisplayName() + " declined your offer of \""
                        + offer.getOfferedItem().getTitle() + "\".",
                "/offers-outgoing.html");
        log.info("Offer {} rejected by user {}", offerId, userId);
        return OfferDto.from(offer, null);
    }

    public OfferDto cancel(Long offerId, Long userId) {
        Offer offer = offers.lockById(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (!offer.getOfferer().getId().equals(userId)) {
            throw ApiException.forbidden("Only the user who made this offer can cancel it");
        }
        if (!offer.isPending()) {
            throw ApiException.conflict("Only pending offers can be cancelled");
        }
        offer.setStatus(OfferStatus.CANCELLED);
        offer.setResolutionNote("Withdrawn by the offerer");
        notifications.notify(offer.getRecipient(), NotificationType.OFFER_CANCELLED,
                "Offer withdrawn: \"" + offer.getRequestedItem().getTitle() + "\"",
                offer.getOfferer().getDisplayName() + " withdrew their offer of \""
                        + offer.getOfferedItem().getTitle() + "\".",
                "/offers-incoming.html");
        log.info("Offer {} cancelled by user {}", offerId, userId);
        return OfferDto.from(offer, null);
    }

    /**
     * Closes all other pending offers that involve items which just became unavailable. Offers <em>for</em> those
     * items are rejected; offers that <em>use</em> them as the offered item are cancelled. Callers must already hold
     * the item locks.
     */
    public void closePendingOffersInvolving(Collection<Item> items, Long excludeOfferId) {
        Set<Long> ids = items.stream().map(Item::getId).collect(Collectors.toSet());
        for (Offer other : offers.lockPendingInvolving(ids)) {
            if (other.getId().equals(excludeOfferId)) {
                continue;
            }
            if (ids.contains(other.getRequestedItem().getId())) {
                other.setStatus(OfferStatus.REJECTED);
                other.setResolutionNote("The requested item is no longer available");
                notifications.notify(other.getOfferer(), NotificationType.OFFER_REJECTED,
                        "Offer closed: \"" + other.getRequestedItem().getTitle() + "\"",
                        "\"" + other.getRequestedItem().getTitle()
                                + "\" is no longer available, so your offer was closed automatically.",
                        "/offers-outgoing.html");
            } else {
                other.setStatus(OfferStatus.CANCELLED);
                other.setResolutionNote("The offered item is no longer available");
                notifications.notify(other.getRecipient(), NotificationType.OFFER_CANCELLED,
                        "Offer withdrawn: \"" + other.getRequestedItem().getTitle() + "\"",
                        "The offer of \"" + other.getOfferedItem().getTitle()
                                + "\" was withdrawn because that item is no longer available.",
                        "/offers-incoming.html");
            }
            log.info("Offer {} auto-closed as {}", other.getId(), other.getStatus());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<OfferDto> incoming(Long userId, OfferStatus status, int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return toDtos(offers.findByRecipientIdAndStatusIn(userId, statuses(status), pageable));
    }

    @Transactional(readOnly = true)
    public PageResponse<OfferDto> outgoing(Long userId, OfferStatus status, int page, int size) {
        var pageable = PageResponse.request(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        return toDtos(offers.findByOffererIdAndStatusIn(userId, statuses(status), pageable));
    }

    @Transactional(readOnly = true)
    public OfferDto get(Long offerId, Long userId) {
        Offer offer = offers.findWithDetailsById(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        if (!offer.getOfferer().getId().equals(userId) && !offer.getRecipient().getId().equals(userId)) {
            throw ApiException.notFound("Offer");
        }
        Long exchangeId = exchanges.findByOfferId(offerId).map(Exchange::getId).orElse(null);
        return OfferDto.from(offer, exchangeId);
    }

    private PageResponse<OfferDto> toDtos(Page<Offer> page) {
        List<Long> acceptedIds = page.getContent().stream()
                .filter(o -> o.getStatus() == OfferStatus.ACCEPTED).map(Offer::getId).toList();
        Map<Long, Long> exchangeByOffer = acceptedIds.isEmpty() ? Map.of()
                : exchanges.findByOfferIdIn(acceptedIds).stream()
                        .collect(Collectors.toMap(e -> e.getOffer().getId(), Exchange::getId, (a, b) -> a));
        Function<Offer, OfferDto> mapper = o -> OfferDto.from(o, exchangeByOffer.get(o.getId()));
        return PageResponse.from(page, mapper);
    }

    private static Collection<OfferStatus> statuses(OfferStatus status) {
        return status == null ? EnumSet.allOf(OfferStatus.class) : EnumSet.of(status);
    }
}
