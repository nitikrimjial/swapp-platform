package com.swapp.moderation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.common.PageResponse;
import com.swapp.moderation.dto.ModerationDtos.ModerationSummary;
import com.swapp.moderation.dto.ModerationDtos.ReportDto;
import com.swapp.moderation.dto.ModerationDtos.ResolveReportRequest;
import com.swapp.moderation.dto.ModerationDtos.SuspendedUserDto;
import com.swapp.security.AuthUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Moderator endpoints. SecurityConfig limits {@code /api/admin/**} to the ADMIN role. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ModerationService moderation;

    public AdminController(ModerationService moderation) {
        this.moderation = moderation;
    }

    @GetMapping("/summary")
    public ModerationSummary summary() {
        return moderation.summary();
    }

    @GetMapping("/reports")
    public PageResponse<ReportDto> reports(@RequestParam(defaultValue = "OPEN") ReportStatus status,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return moderation.list(status, page, size);
    }

    @PostMapping("/reports/{id}/resolve")
    public ReportDto resolve(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                             @Valid @RequestBody ResolveReportRequest request) {
        return moderation.resolve(me.id(), id, request);
    }

    @GetMapping("/users/suspended")
    public PageResponse<SuspendedUserDto> suspended(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return moderation.suspended(page, size);
    }

    @PostMapping("/users/{id}/suspend")
    public SuspendedUserDto suspend(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                    @Valid @RequestBody SuspendRequest request) {
        return moderation.suspend(me.id(), id, request.reason());
    }

    @PostMapping("/users/{id}/reinstate")
    public ResponseEntity<Void> reinstate(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        moderation.reinstate(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    public record SuspendRequest(
            @NotBlank(message = "Give a reason; the member will see it")
            @Size(max = 500, message = "Please keep the reason under 500 characters") String reason) {
    }
}
