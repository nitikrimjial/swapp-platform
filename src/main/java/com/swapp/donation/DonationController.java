package com.swapp.donation;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.common.PageResponse;
import com.swapp.donation.dto.DonationDtos.CreateDonationRequest;
import com.swapp.donation.dto.DonationDtos.DonationRequestDto;
import com.swapp.security.AuthUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/donation-requests")
public class DonationController {

    private final DonationService service;

    public DonationController(DonationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DonationRequestDto create(@AuthenticationPrincipal AuthUser me,
                                     @Valid @RequestBody CreateDonationRequest body) {
        return service.create(me.id(), body);
    }

    @GetMapping("/incoming")
    public PageResponse<DonationRequestDto> incoming(@AuthenticationPrincipal AuthUser me,
                                                     @RequestParam(required = false) DonationRequestStatus status,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return service.incoming(me.id(), status, page, size);
    }

    @GetMapping("/outgoing")
    public PageResponse<DonationRequestDto> outgoing(@AuthenticationPrincipal AuthUser me,
                                                     @RequestParam(required = false) DonationRequestStatus status,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "10") int size) {
        return service.outgoing(me.id(), status, page, size);
    }

    @PostMapping("/{id}/accept")
    public DonationRequestDto accept(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.accept(id, me.id());
    }

    @PostMapping("/{id}/decline")
    public DonationRequestDto decline(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.decline(id, me.id());
    }

    @PostMapping("/{id}/cancel")
    public DonationRequestDto cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.cancel(id, me.id());
    }

    @PostMapping("/{id}/complete")
    public DonationRequestDto complete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.complete(id, me.id());
    }
}
