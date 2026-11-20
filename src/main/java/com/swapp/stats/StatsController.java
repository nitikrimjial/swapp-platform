package com.swapp.stats;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.donation.DonationRequestRepository;
import com.swapp.donation.DonationRequestStatus;
import com.swapp.exchange.ExchangeRepository;
import com.swapp.exchange.ExchangeStatus;
import com.swapp.item.ItemRepository;
import com.swapp.item.ItemStatus;
import com.swapp.user.UserRepository;

/** Live community numbers for the landing page. */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final UserRepository users;
    private final ItemRepository items;
    private final ExchangeRepository exchanges;
    private final DonationRequestRepository donations;

    public StatsController(UserRepository users, ItemRepository items, ExchangeRepository exchanges,
                           DonationRequestRepository donations) {
        this.users = users;
        this.items = items;
        this.exchanges = exchanges;
        this.donations = donations;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<PublicStats> stats() {
        var body = new PublicStats(users.count(), items.countByStatus(ItemStatus.AVAILABLE),
                exchanges.countByStatus(ExchangeStatus.COMPLETED)
                        + donations.countByStatus(DonationRequestStatus.COMPLETED));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic()).body(body);
    }

    public record PublicStats(long members, long activeListings, long completedSwaps) {
    }
}
