package io.projectaxiom.iam;

import java.util.UUID;

/** A role is defined within a tenant and cannot be assigned across tenant boundaries. */
public record TenantRole(UUID tenantId, String code, String name,
        String description, boolean system) {
}
