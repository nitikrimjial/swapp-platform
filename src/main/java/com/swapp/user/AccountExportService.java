package com.swapp.user;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.swapp.common.ApiException;
import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.donation.dto.DonationDtos.DonationRequestDto;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.exchange.dto.ExchangeDto;
import com.swapp.item.ItemRepository;
import com.swapp.item.dto.ItemDtos.ItemDetailDto;
import com.swapp.item.dto.ItemDtos.ViewerState;
import com.swapp.messaging.ConversationRepository;
import com.swapp.messaging.MessageRepository;
import com.swapp.notification.NotificationDto;
import com.swapp.notification.NotificationRepository;
import com.swapp.offer.OfferRepository;
import com.swapp.offer.OfferStatus;
import com.swapp.offer.dto.OfferDtos.OfferDto;
import com.swapp.user.dto.UserDtos.IdentityDto;
import com.swapp.user.dto.UserDtos.UserDto;

/**
 * Builds a machine-readable copy of everything the member has on Swapp (right of access under India's DPDP Act 2023,
 * data portability under GDPR Art. 20). Read-only and scoped strictly to the requesting member.
 */
@Service
@Transactional(readOnly = true)
public class AccountExportService {

    private static final int MAX_NOTIFICATIONS = 1000;
    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final UserRepository users;
    private final ItemRepository items;
    private final OfferRepository offers;
    private final ExchangeRepository exchanges;
    private final DonationRequestRepository donationRequests;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final NotificationRepository notifications;
    private final ExternalSignInService externalSignIn;

    public AccountExportService(UserRepository users, ItemRepository items, OfferRepository offers,
                                ExchangeRepository exchanges, DonationRequestRepository donationRequests,
                                ConversationRepository conversations, MessageRepository messages,
                                NotificationRepository notifications, ExternalSignInService externalSignIn) {
        this.users = users;
        this.items = items;
        this.offers = offers;
        this.exchanges = exchanges;
        this.donationRequests = donationRequests;
        this.conversations = conversations;
        this.messages = messages;
        this.notifications = notifications;
        this.externalSignIn = externalSignIn;
    }

    public AccountExport export(Long userId) {
        User user = users.findById(userId).filter(u -> !u.isDeleted())
                .orElseThrow(() -> ApiException.notFound("User"));
        Pageable all = Pageable.unpaged(NEWEST);
        var ownerView = new ViewerState(true, true, null, null, null, 0, 0);
        var allOffers = EnumSet.allOf(OfferStatus.class);
        var allRequests = EnumSet.allOf(DonationRequestStatus.class);

        List<ExportConversation> chats = conversations.findForUser(userId, Pageable.unpaged()).stream()
                .map(c -> new ExportConversation(c.getId(), c.otherParticipant(userId).getDisplayName(),
                        messages.findByConversationIdOrderByIdAsc(c.getId()).stream()
                                .map(m -> new ExportMessage(m.getCreatedAt(), m.getSender().getId().equals(userId),
                                        m.getBody()))
                                .toList()))
                .toList();

        return new AccountExport(Instant.now(),
                "Everything Swapp holds about your account. Other members' names appear only where they are part "
                        + "of your own activity. Photos are linked by URL.",
                UserDto.from(user),
                externalSignIn.identities(userId),
                items.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                        .map(i -> ItemDetailDto.from(i, ownerView)).toList(),
                offers.findByOffererIdAndStatusIn(userId, allOffers, all).map(o -> OfferDto.from(o, null)).toList(),
                offers.findByRecipientIdAndStatusIn(userId, allOffers, all).map(o -> OfferDto.from(o, null)).toList(),
                exchanges.findForUser(userId, EnumSet.allOf(ExchangeStatus.class), all).map(ExchangeDto::from)
                        .toList(),
                donationRequests.findByRequesterIdAndStatusIn(userId, allRequests, all)
                        .map(DonationRequestDto::from).toList(),
                donationRequests.findByOwnerIdAndStatusIn(userId, allRequests, all)
                        .map(DonationRequestDto::from).toList(),
                chats,
                notifications.findByRecipientIdOrderByIdDesc(userId, PageRequest.of(0, MAX_NOTIFICATIONS)).stream()
                        .map(NotificationDto::from).toList());
    }

    public record AccountExport(Instant exportedAt, String notice, UserDto profile,
                                List<IdentityDto> connectedSignInAccounts, List<ItemDetailDto> listings,
                                List<OfferDto> offersMade, List<OfferDto> offersReceived,
                                List<ExchangeDto> exchanges, List<DonationRequestDto> donationRequestsMade,
                                List<DonationRequestDto> donationRequestsReceived,
                                List<ExportConversation> conversations, List<NotificationDto> notifications) {
    }

    public record ExportConversation(Long id, String with, List<ExportMessage> messages) {
    }

    public record ExportMessage(Instant sentAt, boolean fromMe, String body) {
    }
}
