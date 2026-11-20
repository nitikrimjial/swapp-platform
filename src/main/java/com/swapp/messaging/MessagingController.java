package com.swapp.messaging;

import java.util.List;
import java.util.Map;

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
import com.swapp.messaging.dto.MessagingDtos.ConversationDto;
import com.swapp.messaging.dto.MessagingDtos.MessageDto;
import com.swapp.messaging.dto.MessagingDtos.SendMessageRequest;
import com.swapp.messaging.dto.MessagingDtos.StartConversationRequest;
import com.swapp.security.AuthUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/conversations")
public class MessagingController {

    private final MessagingService service;

    public MessagingController(MessagingService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ConversationDto> list(@AuthenticationPrincipal AuthUser me,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "30") int size) {
        return service.list(me.id(), page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationDto start(@AuthenticationPrincipal AuthUser me,
                                 @Valid @RequestBody StartConversationRequest request) {
        return service.start(me.id(), request);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthUser me) {
        return Map.of("count", service.unreadCount(me.id()));
    }

    @GetMapping("/{id}")
    public ConversationDto get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return service.get(me.id(), id);
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                     @RequestParam(required = false) Long before,
                                     @RequestParam(defaultValue = "50") int size) {
        return service.messages(me.id(), id, before, size);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto send(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                           @Valid @RequestBody SendMessageRequest request) {
        return service.send(me.id(), id, request.body(), request.itemId());
    }

    @PostMapping("/{id}/read")
    public Map<String, Integer> markRead(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return Map.of("updated", service.markRead(me.id(), id));
    }
}
