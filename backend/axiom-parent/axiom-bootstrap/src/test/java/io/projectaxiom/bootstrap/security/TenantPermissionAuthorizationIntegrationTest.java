package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import io.projectaxiom.organization.OrganizationService;
import io.projectaxiom.organization.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TenantPermissionAuthorizationIntegrationTest.SecurityTestConfiguration.class)
class TenantPermissionAuthorizationIntegrationTest {
    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private SecuredTenantOperations securedTenantOperations;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void methodSecurityRequiresTenantRoleGrantAndVerifiedTenantContext() {
        Tenant tenantA = organizationService.createTenant("AUTHZ_TENANT_A", "Authorization Tenant A");
        Tenant tenantB = organizationService.createTenant("AUTHZ_TENANT_B", "Authorization Tenant B");
        IdentityUser identity = identityService.registerIdentity(
                "https://identity.example.test/realms/axiom", "authz-" + UUID.randomUUID());
        identityService.createRole(tenantA.id(), "PARTY_READER", "Party Reader",
                "Can read parties", Set.of("party:read"));
        identityService.createMembership(tenantA.id(), identity.id(), Set.of("PARTY_READER"));

        SecurityContextHolder.getContext().setAuthentication(new AxiomAuthenticationToken(
                null, new AxiomPrincipal(identity.id(), identity.issuer(), identity.subject())));

        try (TenantContext.Scope ignored = TenantContext.open(tenantA.id())) {
            assertThatCode(() -> securedTenantOperations.readParties(tenantA.id())).doesNotThrowAnyException();
            assertThatThrownBy(() -> securedTenantOperations.manageParties(tenantA.id()))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> securedTenantOperations.readParties(tenantB.id()))
                    .isInstanceOf(AccessDeniedException.class);
        }

        assertThatThrownBy(() -> securedTenantOperations.readParties(tenantA.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class SecurityTestConfiguration {
        @Bean
        public SecuredTenantOperations securedTenantOperations() {
            return new SecuredTenantOperations();
        }
    }

    public static class SecuredTenantOperations {
        @PreAuthorize("@tenantPermissionAuthorization.hasPermission(authentication, #p0, 'party:read')")
        public void readParties(UUID tenantId) {
        }

        @PreAuthorize("@tenantPermissionAuthorization.hasPermission(authentication, #p0, 'party:manage')")
        public void manageParties(UUID tenantId) {
        }
    }
}
