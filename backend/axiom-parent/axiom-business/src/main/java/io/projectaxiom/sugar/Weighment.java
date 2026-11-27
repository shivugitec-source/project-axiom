package io.projectaxiom.sugar;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable, unit-explicit sugarcane weighment calculation.
 *
 * <p>This value object does not represent a confirmed delivery, calibrated scale
 * reading, or payable quantity. Those workflows require separate authorization
 * and business rules.
 */
public record Weighment(BigDecimal grossKilograms, BigDecimal tareKilograms) {
    public Weighment {
        Objects.requireNonNull(grossKilograms, "grossKilograms");
        Objects.requireNonNull(tareKilograms, "tareKilograms");
        if (grossKilograms.signum() < 0 || tareKilograms.signum() < 0) {
            throw new IllegalArgumentException("Weighment values cannot be negative");
        }
        if (tareKilograms.compareTo(grossKilograms) > 0) {
            throw new IllegalArgumentException("Tare weight cannot exceed gross weight");
        }
    }

    public BigDecimal netKilograms() {
        return grossKilograms.subtract(tareKilograms);
    }
}
