package com.swapp.exchange;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.common.PageResponse;
import com.swapp.exchange.dto.ExchangeDto;
import com.swapp.security.AuthUser;

@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {

    private final ExchangeService service;

    public ExchangeController(ExchangeService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ExchangeDto> list(@AuthenticationPrincipal AuthUser me,
                                          @RequestParam(required = false) ExchangeStatus status,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "10") int size) {
        return service.list(me.id(), status, page, size);
    }

    @GetMapping("/{id}")
    public ExchangeDto get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.get(id, me.id());
    }

    @PostMapping("/{id}/confirm")
    public ExchangeDto confirm(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.confirm(id, me.id());
    }

    @PostMapping("/{id}/cancel")
    public ExchangeDto cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.cancel(id, me.id());
    }
}
