package io.projectaxiom.organization;

import java.time.Instant;
import java.util.UUID;

/** Customer account and hard data-isolation boundary for Project Axiom. */
public record Tenant(
        UUID id,
        String code,
        String name,
        String status,
        Instant createdAt) {
}
