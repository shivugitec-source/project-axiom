package io.projectaxiom.iam;

import java.time.Instant;
import java.util.UUID;

/** A user's membership in one tenant; the same identity may belong to multiple tenants. */
public record TenantMembership(UUID id, UUID tenantId, UUID identityId,
        String status, Instant createdAt) {
}
