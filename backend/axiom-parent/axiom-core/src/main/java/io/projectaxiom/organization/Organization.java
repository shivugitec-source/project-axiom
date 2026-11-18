package io.projectaxiom.organization;

import java.time.Instant;
import java.util.UUID;

/** An organizational unit inside a tenant; it is not itself a tenant boundary. */
public record Organization(
        UUID id,
        UUID tenantId,
        UUID parentId,
        String code,
        String name,
        String type,
        String status,
        Instant createdAt) {
}
