package io.projectaxiom.common;

import java.util.UUID;

/**
 * Request-scoped tenant selected only after authentication and membership verification.
 * Services still establish PostgreSQL's transaction-local RLS setting themselves.
 */
public final class TenantContext {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Scope open(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A tenant context is already active on this thread");
        }
        CURRENT.set(tenantId);
        return new Scope();
    }

    public static UUID requireMatchingTenant(UUID requestedTenantId) {
        if (requestedTenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        UUID verifiedTenantId = CURRENT.get();
        if (verifiedTenantId != null && !verifiedTenantId.equals(requestedTenantId)) {
            throw new TenantContextMismatchException();
        }
        return verifiedTenantId == null ? requestedTenantId : verifiedTenantId;
    }

    public static UUID currentTenantId() {
        return CURRENT.get();
    }

    public static final class Scope implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private boolean closed;

        private Scope() {
        }

        @Override
        public void close() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("Tenant context scope must be closed by its owning thread");
            }
            if (!closed) {
                CURRENT.remove();
                closed = true;
            }
        }
    }

    public static final class TenantContextMismatchException extends RuntimeException {
        public TenantContextMismatchException() {
            super("Requested tenant does not match the authenticated tenant context");
        }
    }
}
