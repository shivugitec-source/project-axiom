package io.projectaxiom.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {
    @AfterEach
    void clearContext() {
        UUID current = TenantContext.currentTenantId();
        if (current != null) {
            try (TenantContext.Scope ignored = TenantContext.open(current)) {
                // Scope closure clears the thread-local value.
            }
        }
    }

    @Test
    void returnsRequestedTenantWhenNoVerifiedRequestContextExists() {
        UUID tenantId = UUID.randomUUID();
        assertThat(TenantContext.requireMatchingTenant(tenantId)).isEqualTo(tenantId);
    }

    @Test
    void enforcesVerifiedTenantAndClearsItOnScopeClose() {
        UUID verified = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (TenantContext.Scope ignored = TenantContext.open(verified)) {
            assertThat(TenantContext.currentTenantId()).isEqualTo(verified);
            assertThat(TenantContext.requireMatchingTenant(verified)).isEqualTo(verified);
            assertThatThrownBy(() -> TenantContext.requireMatchingTenant(other))
                    .isInstanceOf(TenantContext.TenantContextMismatchException.class);
        }
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void rejectsNestedTenantScopes() {
        try (TenantContext.Scope ignored = TenantContext.open(UUID.randomUUID())) {
            assertThatThrownBy(() -> TenantContext.open(UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
