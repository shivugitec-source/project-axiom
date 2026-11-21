package io.projectaxiom.party;

import java.time.Instant;
import java.util.UUID;

/** A tenant-owned email address or telephone number associated with a party. */
public record PartyContactPoint(
        UUID id,
        UUID tenantId,
        UUID partyId,
        ContactPointType type,
        String value,
        String label,
        Instant createdAt) {
}
