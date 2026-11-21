package com.swapp.moderation;

/** The moderator's decision on a report. */
public enum ReportResolution {
    /** No violation found. */
    DISMISSED,
    /** The reported listing was taken down. */
    ITEM_REMOVED,
    /** The reported member was suspended. */
    USER_SUSPENDED
}
