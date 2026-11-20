package com.swapp.item;

public enum ListingType {
    /** Owner wants to trade the item for another item (exchange-offer workflow). */
    EXCHANGE,
    /** Owner donates the item (request-and-handover workflow). */
    DONATION,
    /** Owner gives the item away for free (request-and-handover workflow). */
    GIVEAWAY,
    /** Owner is looking for an item; others respond via messaging. */
    REQUEST;

    public boolean isFreeTransfer() {
        return this == DONATION || this == GIVEAWAY;
    }
}
