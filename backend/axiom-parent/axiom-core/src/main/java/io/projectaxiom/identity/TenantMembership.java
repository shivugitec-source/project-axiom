package io.projectaxiom.identity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A user's membership and tenant-scoped roles within one tenant. */
public record TenantMembership(
        UUID id,
        UUID tenantId,
        UUID identityUserId,
        String status,
        Set<TenantRole> roles,
        Instant createdAt) {
    public TenantMembership {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
