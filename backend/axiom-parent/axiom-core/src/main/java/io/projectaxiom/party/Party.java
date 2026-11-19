package io.projectaxiom.party;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** A person or organization that participates in business relationships with a tenant. */
public record Party(
        UUID id,
        UUID tenantId,
        PartyType partyType,
        String displayName,
        String status,
        Set<PartyRole> roles,
        Instant createdAt) {
}
