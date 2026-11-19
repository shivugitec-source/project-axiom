package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.organization.OrganizationController;
import io.projectaxiom.party.PartyController;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.TestingAuthenticationToken;

class TenantPermissionAuthorizationTest {
    private final IdentityService identityService = mock(IdentityService.class);

    @Test
    void requiresMatchingVerifiedTenantAndDelegatesPermissionEvaluation() {
        MockEnvironment environment = new MockEnvironment();
        TenantPermissionAuthorization authorization =
                new TenantPermissionAuthorization(identityService, environment);
        UUID tenantId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        AxiomAuthenticationToken authentication =
                new AxiomAuthenticationToken(null, new AxiomPrincipal(identityId, "issuer", "subject"));
        when(identityService.hasPermission(tenantId, identityId, "party:read")).thenReturn(true);

        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            assertThat(authorization.hasPermission(authentication, tenantId, "party:read")).isTrue();
        }

        verify(identityService).hasPermission(tenantId, identityId, "party:read");
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void deniesMissingOrMismatchedTenantContextWithoutQueryingPermissions() {
        MockEnvironment environment = new MockEnvironment();
        TenantPermissionAuthorization authorization =
                new TenantPermissionAuthorization(identityService, environment);
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        AxiomAuthenticationToken authentication =
                new AxiomAuthenticationToken(null, new AxiomPrincipal(UUID.randomUUID(), "issuer", "subject"));

        assertThat(authorization.hasPermission(authentication, tenantA, "party:read")).isFalse();
        try (TenantContext.Scope ignored = TenantContext.open(tenantA)) {
            assertThat(authorization.hasPermission(authentication, tenantB, "party:read")).isFalse();
        }

        verifyNoInteractions(identityService);
    }

    @Test
    void deniesNonAxiomAuthenticationAndMissingPermission() {
        MockEnvironment environment = new MockEnvironment();
        TenantPermissionAuthorization authorization =
                new TenantPermissionAuthorization(identityService, environment);
        UUID tenantId = UUID.randomUUID();

        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            assertThat(authorization.hasPermission(
                    new TestingAuthenticationToken("user", "credentials", "ROLE_USER"),
                    tenantId, "party:read")).isFalse();
            assertThat(authorization.hasPermission(null, tenantId, "party:read")).isFalse();
            assertThat(authorization.hasPermission(
                    new AxiomAuthenticationToken(null, new AxiomPrincipal(UUID.randomUUID(), "issuer", "subject")),
                    tenantId, " ")).isFalse();
        }

        verify(identityService, never()).hasPermission(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }


    @Test
    void tenantControllerMethodsDeclareExplicitPermissions() throws Exception {
        assertThat(OidcSecurityConfiguration.class.isAnnotationPresent(EnableMethodSecurity.class)).isTrue();
        assertThat(OrganizationController.class
                .getMethod("createTenant", OrganizationController.TenantRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@tenantPermissionAuthorization.isLocalDevelopmentBypass()");
        assertThat(OrganizationController.class
                .getMethod("createOrganization", UUID.class, OrganizationController.OrganizationRequest.class)
                .getAnnotation(PreAuthorize.class).value())
                .contains("organization:manage");
        assertThat(OrganizationController.class.getMethod("listOrganizations", UUID.class)
                .getAnnotation(PreAuthorize.class).value()).contains("organization:read");
        assertThat(PartyController.class.getMethod("createParty", UUID.class, PartyController.PartyRequest.class)
                .getAnnotation(PreAuthorize.class).value()).contains("party:manage");
        assertThat(PartyController.class.getMethod("listParties", UUID.class)
                .getAnnotation(PreAuthorize.class).value()).contains("party:read");
        assertThat(PartyController.class.getMethod("getParty", UUID.class, UUID.class)
                .getAnnotation(PreAuthorize.class).value()).contains("party:read");
    }

    @Test
    void localProfileBypassRequiresOidcToBeUnconfigured() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        TenantPermissionAuthorization localAuthorization =
                new TenantPermissionAuthorization(identityService, local);

        assertThat(localAuthorization.isLocalDevelopmentBypass()).isTrue();
        assertThat(localAuthorization.hasPermission(null, UUID.randomUUID(), "party:read")).isTrue();

        MockEnvironment localWithOidc = new MockEnvironment()
                .withProperty("AXIOM_OIDC_ISSUER_URI", "https://identity.example.test/issuer")
                .withProperty("AXIOM_OIDC_AUDIENCE", "axiom-api");
        localWithOidc.setActiveProfiles("local");
        TenantPermissionAuthorization oidcAuthorization =
                new TenantPermissionAuthorization(identityService, localWithOidc);

        assertThat(oidcAuthorization.isLocalDevelopmentBypass()).isFalse();
        assertThat(oidcAuthorization.hasPermission(null, UUID.randomUUID(), "party:read")).isFalse();
        verifyNoInteractions(identityService);
    }
}
