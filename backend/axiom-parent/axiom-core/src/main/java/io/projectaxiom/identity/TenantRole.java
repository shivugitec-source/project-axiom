package io.projectaxiom.identity;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A tenant-owned role with an explicit set of platform permission grants. */
public record TenantRole(
        UUID tenantId,
        String code,
        String name,
        String description,
        boolean system,
        Set<IamPermission> permissions,
        Instant createdAt) {
    public TenantRole {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
