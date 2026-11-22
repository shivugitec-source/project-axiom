package io.projectaxiom.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class TenantContextTest {
    @Test
    void returnsRequestedTenantWhenNoVerifiedRequestContextExists() {
        UUID tenantId = UUID.randomUUID();
        assertEquals(tenantId, TenantContext.requireMatchingTenant(tenantId));
    }

    @Test
    void enforcesVerifiedTenantAndClearsItOnScopeClose() {
        UUID verified = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (TenantContext.Scope ignored = TenantContext.open(verified)) {
            assertEquals(verified, TenantContext.currentTenantId());
            assertEquals(verified, TenantContext.requireMatchingTenant(verified));
            assertThrows(TenantContext.TenantContextMismatchException.class,
                    () -> TenantContext.requireMatchingTenant(other));
        }
        assertNull(TenantContext.currentTenantId());
    }

    @Test
    void rejectsNestedTenantScopes() {
        try (TenantContext.Scope ignored = TenantContext.open(UUID.randomUUID())) {
            assertThrows(IllegalStateException.class, () -> TenantContext.open(UUID.randomUUID()));
        }
    }
    @Test
    void rejectsClosingScopeFromDifferentThreadAndLeavesOwnerAbleToClose() throws InterruptedException {
        UUID tenantId = UUID.randomUUID();
        TenantContext.Scope scope = TenantContext.open(tenantId);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread wrongThread = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        });
        wrongThread.start();
        wrongThread.join();

        assertTrue(closeFailure.get() instanceof IllegalStateException);
        assertEquals(tenantId, TenantContext.currentTenantId());

        scope.close();
        assertNull(TenantContext.currentTenantId());
    }

}
