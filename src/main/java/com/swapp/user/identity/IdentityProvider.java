package com.swapp.user.identity;

/** External sign-in providers a member can link to their Swapp account. */
public enum IdentityProvider {
    GOOGLE("Google");

    private final String label;

    IdentityProvider(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
