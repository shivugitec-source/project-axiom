package io.projectaxiom.bootstrap.security;

import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates an optional X-Tenant-ID request selector against the authenticated
 * Axiom identity. It never treats the header itself as authorization.
 */
public final class TenantContextFilter extends OncePerRequestFilter {
    public static final String TENANT_HEADER = "X-Tenant-ID";

    private final IdentityService identityService;

    public TenantContextFilter(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Enumeration<String> tenantHeaders = request.getHeaders(TENANT_HEADER);
        if (tenantHeaders == null || !tenantHeaders.hasMoreElements()) {
            filterChain.doFilter(request, response);
            return;
        }

        String requestedTenant = tenantHeaders.nextElement();
        if (tenantHeaders.hasMoreElements() || requestedTenant == null || requestedTenant.isBlank()) {
            response.sendError(HttpStatus.BAD_REQUEST.value(),
                    "X-Tenant-ID must be supplied exactly once as a nonblank UUID");
            return;
        }

        final UUID tenantId;
        try {
            tenantId = UUID.fromString(requestedTenant);
        } catch (IllegalArgumentException exception) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), "X-Tenant-ID must be a UUID");
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AxiomPrincipal principal)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "A validated Axiom identity is required for tenant selection");
            return;
        }

        if (!identityService.hasActiveMembership(tenantId, principal.identityId())) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "No active membership exists for the selected tenant");
            return;
        }

        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            filterChain.doFilter(request, response);
        }
    }
}
