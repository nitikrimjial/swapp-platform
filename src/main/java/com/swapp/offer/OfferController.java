package com.swapp.offer;

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
import com.swapp.offer.dto.OfferDtos.CreateOfferRequest;
import com.swapp.offer.dto.OfferDtos.OfferDto;
import com.swapp.security.AuthUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final OfferService service;

    public OfferController(OfferService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OfferDto create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CreateOfferRequest request) {
        return service.create(me.id(), request);
    }

    @GetMapping("/incoming")
    public PageResponse<OfferDto> incoming(@AuthenticationPrincipal AuthUser me,
                                           @RequestParam(required = false) OfferStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return service.incoming(me.id(), status, page, size);
    }

    @GetMapping("/outgoing")
    public PageResponse<OfferDto> outgoing(@AuthenticationPrincipal AuthUser me,
                                           @RequestParam(required = false) OfferStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return service.outgoing(me.id(), status, page, size);
    }

    @GetMapping("/{id}")
    public OfferDto get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.get(id, me.id());
    }

    @PostMapping("/{id}/accept")
    public OfferDto accept(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.accept(id, me.id());
    }

    @PostMapping("/{id}/reject")
    public OfferDto reject(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.reject(id, me.id());
    }

    @PostMapping("/{id}/cancel")
    public OfferDto cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.cancel(id, me.id());
    }
}
