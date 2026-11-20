package com.swapp.item;

public enum ItemStatus {
    /** Visible in discovery and open to offers / requests. */
    AVAILABLE,
    /** Locked by an accepted offer or accepted donation request. */
    RESERVED,
    /** Temporarily hidden by the owner. */
    UNAVAILABLE,
    /** Exchanged or handed over. Kept for history. */
    COMPLETED,
    /** Deleted by the owner. Soft-deleted so exchange history stays intact. */
    REMOVED
}
