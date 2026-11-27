package io.projectaxiom.sugar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WeighmentTest {
    @Test
    void calculatesNetWeightExactly() {
        Weighment weighment = new Weighment(new BigDecimal("18540.75"), new BigDecimal("6520.25"));
        assertEquals(0, new BigDecimal("12020.50").compareTo(weighment.netKilograms()));
    }

    @Test
    void allowsZeroNetWeight() {
        Weighment weighment = new Weighment(new BigDecimal("7500"), new BigDecimal("7500"));
        assertEquals(0, BigDecimal.ZERO.compareTo(weighment.netKilograms()));
    }

    @Test
    void rejectsNegativeWeights() {
        assertThrows(IllegalArgumentException.class,
                () -> new Weighment(new BigDecimal("-1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new Weighment(BigDecimal.ZERO, new BigDecimal("-1")));
    }

    @Test
    void rejectsTareAboveGross() {
        assertThrows(IllegalArgumentException.class,
                () -> new Weighment(new BigDecimal("100"), new BigDecimal("101")));
    }

    @Test
    void rejectsNullWeights() {
        assertThrows(NullPointerException.class,
                () -> new Weighment(null, BigDecimal.ZERO));
        assertThrows(NullPointerException.class,
                () -> new Weighment(BigDecimal.ZERO, null));
    }
}
