package io.projectaxiom.sugar;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record associating a captured cane weighment with its tenant,
 * mill organization, supplier, weighbridge and vehicle.
 *
 * <p>This is a domain record only. It does not assert scale calibration,
 * delivery acceptance, quality, payable quantity or settlement eligibility.
 */
public record CaneDeliveryWeighment(
        UUID id,
        UUID tenantId,
        UUID millOrganizationId,
        UUID supplierPartyId,
        UUID weighbridgeId,
        String vehicleRegistration,
        Weighment weighment,
        Instant weighedAt) {

    public CaneDeliveryWeighment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(millOrganizationId, "millOrganizationId");
        Objects.requireNonNull(supplierPartyId, "supplierPartyId");
        Objects.requireNonNull(weighbridgeId, "weighbridgeId");
        Objects.requireNonNull(weighment, "weighment");
        Objects.requireNonNull(weighedAt, "weighedAt");
        if (vehicleRegistration == null || vehicleRegistration.isBlank()) {
            throw new IllegalArgumentException("Vehicle registration is required");
        }
    }
}
