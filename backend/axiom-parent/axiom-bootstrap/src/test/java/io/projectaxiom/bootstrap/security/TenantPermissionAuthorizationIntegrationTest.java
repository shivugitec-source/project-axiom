package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import io.projectaxiom.organization.OrganizationController;
import io.projectaxiom.organization.OrganizationService;
import io.projectaxiom.organization.Tenant;
import io.projectaxiom.party.PartyContactPointController;
import io.projectaxiom.party.PartyController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "AXIOM_OIDC_ISSUER_URI=",
        "AXIOM_OIDC_AUDIENCE="
})
@Import(TenantPermissionAuthorizationIntegrationTest.SecurityTestConfiguration.class)
class TenantPermissionAuthorizationIntegrationTest {
    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private SecuredTenantOperations securedTenantOperations;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void tenantBusinessControllersAreNotRegisteredOutsideTheLocalProfile() {
        assertThat(applicationContext.getBeansOfType(OrganizationController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(PartyController.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(PartyContactPointController.class)).isEmpty();
    }

    @Test
    void deniesProtectedHttpRoutesWhenOidcIsNotConfiguredOutsideLocalProfile() throws Exception {
        assertThat(OidcSecurityConfiguration.resolveOidcSettings(
                applicationContext.getEnvironment()).configured()).isFalse();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", UUID.randomUUID()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/system"))
                .andExpect(status().isOk());
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
