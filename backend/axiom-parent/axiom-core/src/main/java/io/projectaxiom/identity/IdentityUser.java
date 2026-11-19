package io.projectaxiom.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * A platform identity linked to an external OpenID Connect issuer and subject.
 * Credentials remain owned by the identity provider, never by Project Axiom.
 */
public record IdentityUser(
        UUID id,
        String issuer,
        String subject,
        String status,
        Instant createdAt) {
}
