package com.swapp.offer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.swapp.category.Category;
import com.swapp.common.ApiException;
import com.swapp.exchange.Exchange;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.item.Item;
import com.swapp.item.ItemLocker;
import com.swapp.item.ItemStatus;
import com.swapp.item.ListingType;
import com.swapp.notification.NotificationService;
import com.swapp.notification.NotificationType;
import com.swapp.offer.dto.OfferDtos.CreateOfferRequest;
import com.swapp.user.User;

/** Pure unit tests of the offer rules with mocked collaborators (no Spring context, no database). */
@ExtendWith(MockitoExtension.class)
class OfferServiceTest {

    @Mock
    OfferRepository offers;
    @Mock
    ExchangeRepository exchanges;
    @Mock
    ItemLocker locker;
    @Mock
    NotificationService notifications;
    @InjectMocks
    OfferService service;

    User alice;
    User bob;
    Item bicycle;
    Item monitor;

    @BeforeEach
    void setUp() {
        alice = user(1L, "Alice");
        bob = user(2L, "Bob");
        bicycle = item(10L, alice, ListingType.EXCHANGE, ItemStatus.AVAILABLE);
        monitor = item(20L, bob, ListingType.EXCHANGE, ItemStatus.AVAILABLE);
    }

    @Test
    void createsPendingOfferAndNotifiesOwner() {
        when(locker.lockActive(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        var dto = service.create(2L, new CreateOfferRequest(10L, 20L, "  Swap?  "));
        assertThat(dto.status()).isEqualTo(OfferStatus.PENDING);
        assertThat(dto.message()).isEqualTo("Swap?");
        verify(offers).save(any(Offer.class));
        verify(notifications).notify(eq(alice), eq(NotificationType.OFFER_RECEIVED), any(), any(), any());
    }

    @Test
    void rejectsOfferOnOwnItem() {
        Item helmet = item(30L, alice, ListingType.EXCHANGE, ItemStatus.AVAILABLE);
        when(locker.lockActive(10L, 30L)).thenReturn(Map.of(10L, bicycle, 30L, helmet));
        assertStatus(() -> service.create(1L, new CreateOfferRequest(10L, 30L, null)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsSameItemOnBothSides() {
        assertStatus(() -> service.create(2L, new CreateOfferRequest(10L, 10L, null)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnavailableRequestedItem() {
        bicycle.setStatus(ItemStatus.RESERVED);
        when(locker.lockActive(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        assertStatus(() -> service.create(2L, new CreateOfferRequest(10L, 20L, null)), HttpStatus.CONFLICT);
    }

    @Test
    void rejectsUnavailableOfferedItem() {
        monitor.setStatus(ItemStatus.UNAVAILABLE);
        when(locker.lockActive(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        assertStatus(() -> service.create(2L, new CreateOfferRequest(10L, 20L, null)), HttpStatus.CONFLICT);
    }

    @Test
    void rejectsOfferingSomeoneElsesItem() {
        Item carolsLamp = item(40L, user(3L, "Carol"), ListingType.EXCHANGE, ItemStatus.AVAILABLE);
        when(locker.lockActive(10L, 40L)).thenReturn(Map.of(10L, bicycle, 40L, carolsLamp));
        assertStatus(() -> service.create(2L, new CreateOfferRequest(10L, 40L, null)), HttpStatus.FORBIDDEN);
    }

    @Test
    void rejectsOffersOnNonExchangeListings() {
        bicycle.setListingType(ListingType.GIVEAWAY);
        when(locker.lockActive(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        assertStatus(() -> service.create(2L, new CreateOfferRequest(10L, 20L, null)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsDuplicatePendingOffer() {
        when(locker.lockActive(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        when(offers.existsByRequestedItemIdAndOfferedItemIdAndStatus(10L, 20L, OfferStatus.PENDING)).thenReturn(true);
        assertStatus(() -> service.create(2L, new CreateOfferRequest(10L, 20L, null)), HttpStatus.CONFLICT);
        verify(offers, never()).save(any());
    }

    @Test
    void acceptReservesItemsCreatesExchangeAndClosesCompetingOffers() {
        Offer offer = offer(100L, bicycle, monitor);
        Offer competing = offer(101L, bicycle, item(50L, user(3L, "Carol"), ListingType.EXCHANGE, ItemStatus.AVAILABLE));
        when(offers.findRefs(100L)).thenReturn(Optional.of(refs(10L, 20L, 2L, 1L)));
        when(locker.lockAny(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        when(offers.lockById(100L)).thenReturn(Optional.of(offer));
        when(offers.lockPendingInvolving(any())).thenReturn(List.of(offer, competing));
        when(exchanges.save(any(Exchange.class))).thenAnswer(inv -> {
            Exchange saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 500L);
            return saved;
        });

        var dto = service.accept(100L, 1L);

        assertThat(dto.status()).isEqualTo(OfferStatus.ACCEPTED);
        assertThat(dto.exchangeId()).isEqualTo(500L);
        assertThat(bicycle.getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(monitor.getStatus()).isEqualTo(ItemStatus.RESERVED);
        assertThat(competing.getStatus()).isEqualTo(OfferStatus.REJECTED);
        assertThat(offer.getStatus()).isEqualTo(OfferStatus.ACCEPTED);
    }

    @Test
    void onlyRecipientCanAccept() {
        when(offers.findRefs(100L)).thenReturn(Optional.of(refs(10L, 20L, 2L, 1L)));
        assertStatus(() -> service.accept(100L, 2L), HttpStatus.FORBIDDEN);
        verify(locker, never()).lockAny(anyLong(), anyLong());
    }

    @Test
    void cannotAcceptWhenItemAlreadyReserved() {
        bicycle.setStatus(ItemStatus.RESERVED);
        when(offers.findRefs(100L)).thenReturn(Optional.of(refs(10L, 20L, 2L, 1L)));
        when(locker.lockAny(10L, 20L)).thenReturn(Map.of(10L, bicycle, 20L, monitor));
        when(offers.lockById(100L)).thenReturn(Optional.of(offer(100L, bicycle, monitor)));
        assertStatus(() -> service.accept(100L, 1L), HttpStatus.CONFLICT);
        verify(exchanges, never()).save(any());
    }

    // ------------------------------------------------------------- fixtures

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(status));
    }

    private static User user(Long id, String name) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", id);
        u.setDisplayName(name);
        return u;
    }

    private static Item item(Long id, User owner, ListingType type, ItemStatus status) {
        Item i = new Item();
        ReflectionTestUtils.setField(i, "id", id);
        i.setOwner(owner);
        i.setTitle("Item " + id);
        i.setListingType(type);
        i.setStatus(status);
        i.setCategory(new Category());
        return i;
    }

    private static Offer offer(Long id, Item requested, Item offered) {
        Offer o = new Offer();
        ReflectionTestUtils.setField(o, "id", id);
        o.setRequestedItem(requested);
        o.setOfferedItem(offered);
        o.setOfferer(offered.getOwner());
        o.setRecipient(requested.getOwner());
        return o;
    }

    private static OfferRepository.OfferRefs refs(Long requested, Long offered, Long offerer, Long recipient) {
        return new OfferRepository.OfferRefs() {
            public Long getRequestedItemId() {
                return requested;
            }

            public Long getOfferedItemId() {
                return offered;
            }

            public Long getOffererId() {
                return offerer;
            }

            public Long getRecipientId() {
                return recipient;
            }
        };
    }
}
