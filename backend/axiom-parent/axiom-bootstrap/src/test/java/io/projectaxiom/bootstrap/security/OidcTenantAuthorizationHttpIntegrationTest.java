package io.projectaxiom.bootstrap.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import io.projectaxiom.organization.OrganizationService;
import io.projectaxiom.organization.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "AXIOM_OIDC_ISSUER_URI=https://identity.example.test/issuer",
        "AXIOM_OIDC_AUDIENCE=axiom-api"
})
@Import(OidcTenantAuthorizationHttpIntegrationTest.TestSecurityConfiguration.class)
class OidcTenantAuthorizationHttpIntegrationTest {
    private static final String ISSUER = "https://identity.example.test/issuer";
    private static final String AUDIENCE = "axiom-api";
    private static final String SUBJECT = "http-authorization-test-subject";
    private static final String TOKEN = "project-axiom-http-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantPermissionAuthorization permissionAuthorization;

    @Test
    void httpRequestsRequireValidatedIdentityMembershipTenantContextAndPermission() throws Exception {
        assertThat(permissionAuthorization.isLocalDevelopmentBypass()).isFalse();
        Boolean membershipRls = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom.tenant_membership'::regclass",
                Boolean.class);
        Boolean rolePermissionRls = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom.role_permission'::regclass",
                Boolean.class);
        assertThat(membershipRls).isTrue();
        assertThat(rolePermissionRls).isTrue();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Tenant readTenant = organizationService.createTenant("HTTP_READ_" + suffix, "HTTP Read Tenant");
        Tenant manageOnlyTenant = organizationService.createTenant("HTTP_MANAGE_" + suffix, "HTTP Manage Tenant");
        Tenant noMembershipTenant = organizationService.createTenant("HTTP_NONE_" + suffix, "HTTP No Membership Tenant");

        IdentityUser identity = identityService.registerIdentity(ISSUER, SUBJECT);
        identityService.createRole(readTenant.id(), "PARTY_READER", "Party Reader",
                "Can read parties", Set.of("party:read"));
        identityService.createMembership(readTenant.id(), identity.id(), Set.of("PARTY_READER"));
        identityService.createRole(manageOnlyTenant.id(), "PARTY_MANAGER", "Party Manager",
                "Can manage parties", Set.of("party:manage"));
        identityService.createMembership(manageOnlyTenant.id(), identity.id(), Set.of("PARTY_MANAGER"));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", readTenant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(TenantContextFilter.TENANT_HEADER, readTenant.id().toString()))
                .andExpect(status().isOk());
        assertThat(TenantContext.currentTenantId()).isNull();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", manageOnlyTenant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(TenantContextFilter.TENANT_HEADER, manageOnlyTenant.id().toString()))
                .andExpect(status().isForbidden());
        assertThat(TenantContext.currentTenantId()).isNull();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", noMembershipTenant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(TenantContextFilter.TENANT_HEADER, noMembershipTenant.id().toString()))
                .andExpect(status().isForbidden());
        assertThat(TenantContext.currentTenantId()).isNull();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", readTenant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isForbidden());
        assertThat(TenantContext.currentTenantId()).isNull();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", readTenant.id())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                        .header(TenantContextFilter.TENANT_HEADER, manageOnlyTenant.id().toString()))
                .andExpect(status().isForbidden());
        assertThat(TenantContext.currentTenantId()).isNull();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/parties", readTenant.id())
                        .header(TenantContextFilter.TENANT_HEADER, readTenant.id().toString()))
                .andExpect(status().isUnauthorized());
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestSecurityConfiguration {
        @Bean("axiomJwtDecoder")
        public JwtDecoder testJwtDecoder() {
            return token -> {
                if (!TOKEN.equals(token)) {
                    throw new BadJwtException("Unexpected test bearer token");
                }
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .issuer(ISSUER)
                        .subject(SUBJECT)
                        .audience(List.of(AUDIENCE))
                        .issuedAt(now.minusSeconds(1))
                        .expiresAt(now.plusSeconds(300))
                        .build();
            };
        }
    }
}
