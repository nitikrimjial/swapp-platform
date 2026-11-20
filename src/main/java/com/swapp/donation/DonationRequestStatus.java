package com.swapp.donation;

public enum DonationRequestStatus {
    /** Waiting for the owner's decision. */
    PENDING,
    /** Owner chose this requester; the item is reserved for them. */
    ACCEPTED,
    /** Owner declined, or chose another requester. */
    DECLINED,
    /** Withdrawn by the requester, or the hand-over was called off. */
    CANCELLED,
    /** Item handed over. */
    COMPLETED
}
