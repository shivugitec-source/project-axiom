package io.projectaxiom.iam;

import java.time.Instant;
import java.util.UUID;

/** Provider-neutral reference to an authenticated external identity. Credentials remain with the identity provider. */
public record UserIdentity(UUID id, String issuer, String subject, String email,
        String displayName, String status, Instant createdAt) {
}
