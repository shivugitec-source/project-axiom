package io.projectaxiom.sugar;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CaneDeliveryWeighmentTest {
    private CaneDeliveryWeighment validRecord() {
        return new CaneDeliveryWeighment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "KA-23-AB-1234",
                new Weighment(new BigDecimal("18540.75"), new BigDecimal("6520.25")),
                Instant.parse("2026-11-28T10:15:30Z"));
    }

    @Test
    void acceptsACompleteWeighmentRecord() {
        assertDoesNotThrow(this::validRecord);
    }

    @Test
    void rejectsMissingIdentityAndContext() {
        CaneDeliveryWeighment valid = validRecord();
        assertThrows(NullPointerException.class, () -> new CaneDeliveryWeighment(
                null, valid.tenantId(), valid.millOrganizationId(), valid.supplierPartyId(),
                valid.weighbridgeId(), valid.vehicleRegistration(), valid.weighment(), valid.weighedAt()));
        assertThrows(NullPointerException.class, () -> new CaneDeliveryWeighment(
                valid.id(), valid.tenantId(), valid.millOrganizationId(), null,
                valid.weighbridgeId(), valid.vehicleRegistration(), valid.weighment(), valid.weighedAt()));
        assertThrows(NullPointerException.class, () -> new CaneDeliveryWeighment(
                valid.id(), valid.tenantId(), valid.millOrganizationId(), valid.supplierPartyId(),
                valid.weighbridgeId(), valid.vehicleRegistration(), valid.weighment(), null));
    }

    @Test
    void rejectsBlankVehicleRegistration() {
        CaneDeliveryWeighment valid = validRecord();
        assertThrows(IllegalArgumentException.class, () -> new CaneDeliveryWeighment(
                valid.id(), valid.tenantId(), valid.millOrganizationId(), valid.supplierPartyId(),
                valid.weighbridgeId(), "  ", valid.weighment(), valid.weighedAt()));
        assertThrows(IllegalArgumentException.class, () -> new CaneDeliveryWeighment(
                valid.id(), valid.tenantId(), valid.millOrganizationId(), valid.supplierPartyId(),
                valid.weighbridgeId(), null, valid.weighment(), valid.weighedAt()));
    }

    @Test
    void preservesExactWeighmentAndCaptureTime() {
        CaneDeliveryWeighment record = validRecord();
        assertDoesNotThrow(() -> new Weighment(
                record.weighment().grossKilograms(), record.weighment().tareKilograms()));
    }
}
