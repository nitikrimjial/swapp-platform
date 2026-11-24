package com.swapp.user.identity;

/**
 * What a provider told us about the person signing in, taken from a validated OpenID Connect ID token.
 *
 * @param subject        the provider's permanent account id
 * @param email          the email on the provider account
 * @param emailVerified  whether the provider has verified that email
 * @param name           the person's name on the provider account (may be null)
 */
public record ExternalProfile(IdentityProvider provider, String subject, String email, boolean emailVerified,
                              String name) {
}
