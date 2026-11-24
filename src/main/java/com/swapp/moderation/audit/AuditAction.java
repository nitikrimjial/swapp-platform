package com.swapp.moderation.audit;

/** What a staff member did. Mirrored by the CHECK constraint {@code ck_admin_audit_action}. */
public enum AuditAction {
    REPORT_DISMISSED,
    LISTING_REMOVED,
    MEMBER_SUSPENDED,
    MEMBER_REINSTATED
}
