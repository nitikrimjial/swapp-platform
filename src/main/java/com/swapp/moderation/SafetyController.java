package com.swapp.moderation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.swapp.moderation.dto.ModerationDtos.CreateReportRequest;
import com.swapp.moderation.dto.ModerationDtos.ReportReceipt;
import com.swapp.security.AuthUser;
import com.swapp.user.dto.UserDtos.UserSummaryDto;

import jakarta.validation.Valid;

/** Member-facing safety tools: reporting and blocking. */
@RestController
public class SafetyController {

    private final ReportService reports;
    private final BlockService blocks;

    public SafetyController(ReportService reports, BlockService blocks) {
        this.reports = reports;
        this.blocks = blocks;
    }

    @PostMapping("/api/reports")
    public ResponseEntity<ReportReceipt> report(@AuthenticationPrincipal AuthUser me,
                                                @Valid @RequestBody CreateReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reports.create(me.id(), request));
    }

    @PostMapping("/api/users/{id}/block")
    public ResponseEntity<Void> block(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        blocks.block(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/users/{id}/block")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        blocks.unblock(me.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/users/me/blocks")
    public List<UserSummaryDto> blocked(@AuthenticationPrincipal AuthUser me) {
        return blocks.blockedBy(me.id());
    }
}
