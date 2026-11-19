package io.projectaxiom.bootstrap.security;

import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Evaluates tenant permissions only for the authenticated Axiom identity and
 * the tenant already verified by the request filter.
 */
@Component("tenantPermissionAuthorization")
public final class TenantPermissionAuthorization {
    private final IdentityService identityService;
    private final boolean localDevelopmentBypass;

    public TenantPermissionAuthorization(IdentityService identityService, Environment environment) {
        this.identityService = identityService;
        this.localDevelopmentBypass = environment.acceptsProfiles(Profiles.of("local"))
                && !OidcSecurityConfiguration.resolveOidcSettings(environment).configured();
    }

    public boolean hasPermission(Authentication authentication, UUID requestedTenantId, String permissionCode) {
        if (localDevelopmentBypass) {
            return true;
        }
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AxiomPrincipal principal)
                || requestedTenantId == null || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }

        UUID verifiedTenantId = TenantContext.currentTenantId();
        if (verifiedTenantId == null || !verifiedTenantId.equals(requestedTenantId)) {
            return false;
        }
        return identityService.hasPermission(verifiedTenantId, principal.identityId(), permissionCode);
    }

    /** Platform tenant provisioning is intentionally available only in local development. */
    public boolean isLocalDevelopmentBypass() {
        return localDevelopmentBypass;
    }
}
