package io.projectaxiom.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.UUID;

import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import io.projectaxiom.identity.TenantMembership;
import io.projectaxiom.identity.TenantRole;
import io.projectaxiom.organization.Organization;
import io.projectaxiom.organization.OrganizationService;
import io.projectaxiom.organization.Tenant;
import io.projectaxiom.party.Party;
import io.projectaxiom.party.PartyRole;
import io.projectaxiom.party.PartyService;
import io.projectaxiom.party.PartyType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class AxiomApplicationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private PartyService partyService;

    @Autowired
    private IdentityService identityService;

    @Test
    void startsWithPostgresAndAppliesFlywayMigration() {
        Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'axiom'",
                Integer.class);
        assertThat(schemaCount).isEqualTo(1);

        for (String version : Set.of("1", "2", "3", "4")) {
            Integer migrationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM axiom.flyway_schema_history WHERE version = ? AND success = true",
                    Integer.class, version);
            assertThat(migrationCount).as("Flyway migration V" + version).isEqualTo(1);
        }
    }

    @Test
    void exposesSystemAndHealthEndpoints() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest systemRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/system"))
                .GET().build();
        HttpResponse<String> systemResponse = client.send(systemRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(systemResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(systemResponse.body()).contains("\"name\":\"Project Axiom\"");
        assertThat(systemResponse.body()).contains("\"status\":\"RUNNING\"");
        assertThat(systemResponse.body()).contains("\"timestamp\"");

        HttpRequest healthRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .GET().build();
        HttpResponse<String> healthResponse = client.send(healthRequest, HttpResponse.BodyHandlers.ofString());

        assertThat(healthResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        assertThat(healthResponse.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void partyRowsAndRolesAreIsolatedByTenant() {
        Boolean partyRls = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom.party'::regclass",
                Boolean.class);
        Boolean roleRls = jdbcTemplate.queryForObject(
                "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom.party_role'::regclass",
                Boolean.class);
        assertThat(partyRls).isTrue();
        assertThat(roleRls).isTrue();

        Tenant tenantA = organizationService.createTenant("PARTY_TENANT_A", "Party Tenant A");
        Tenant tenantB = organizationService.createTenant("PARTY_TENANT_B", "Party Tenant B");
        Party created = partyService.createParty(tenantA.id(), PartyType.ORGANIZATION,
                "Acme Supplies", Set.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER));
        assertThat(created.roles()).containsExactlyInAnyOrder(PartyRole.CUSTOMER, PartyRole.SUPPLIER);
        assertThat(partyService.listParties(tenantA.id()))
                .extracting(Party::displayName).containsExactly("Acme Supplies");
        assertThat(partyService.listParties(tenantB.id())).isEmpty();
        assertThatThrownBy(() -> partyService.getParty(tenantB.id(), created.id()))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }

    @Test
    void identityCanBelongToMultipleTenantsButRolesAndPermissionsRemainTenantScoped() {
        Tenant tenantA = organizationService.createTenant("IAM_TENANT_A", "IAM Tenant A");
        Tenant tenantB = organizationService.createTenant("IAM_TENANT_B", "IAM Tenant B");

        for (String table : Set.of("tenant_membership", "tenant_role", "membership_role", "role_permission")) {
            Boolean forcedRls = jdbcTemplate.queryForObject(
                    "SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom."
                            + table + "'::regclass",
                    Boolean.class);
            assertThat(forcedRls).as(table + " must have forced RLS").isTrue();
        }

        IdentityUser identity = identityService.registerIdentity(
                "https://id.example.test/realms/axiom", "subject-123", "user@example.test", "Test User");
        IdentityUser duplicate = identityService.registerIdentity(
                "https://id.example.test/realms/axiom", "subject-123", "user@example.test", "Test User");
        IdentityUser otherIssuer = identityService.registerIdentity(
                "https://another-id.example.test/realms/axiom", "subject-123");

        assertThat(duplicate.id()).isEqualTo(identity.id());
        assertThat(otherIssuer.id()).isNotEqualTo(identity.id());

        Set<String> administratorPermissions = Set.of(
                "organization:read", "organization:manage",
                "party:read", "party:manage",
                "user:read", "user:manage",
                "role:read", "role:manage");
        TenantRole administratorA = identityService.createRole(
                tenantA.id(), "TENANT_ADMIN", "Tenant Administrator", "Full tenant administration",
                administratorPermissions);
        TenantRole administratorB = identityService.createRole(
                tenantB.id(), "TENANT_ADMIN", "Tenant Administrator", "Read-only party access",
                Set.of("party:read"));

        TenantMembership membershipA = identityService.createMembership(
                tenantA.id(), identity.id(), Set.of("TENANT_ADMIN"));
        TenantMembership membershipB = identityService.createMembership(
                tenantB.id(), identity.id(), Set.of("TENANT_ADMIN"));

        assertThat(membershipA.roles()).extracting(TenantRole::code).containsExactly("TENANT_ADMIN");
        assertThat(membershipB.roles()).extracting(TenantRole::code).containsExactly("TENANT_ADMIN");
        assertThat(administratorA.permissions()).extracting("code")
                .contains("user:manage", "party:manage");
        assertThat(administratorB.permissions()).extracting("code").containsExactly("party:read");

        assertThat(identityService.hasPermission(tenantA.id(), identity.id(), "user:manage")).isTrue();
        assertThat(identityService.hasPermission(tenantA.id(), identity.id(), "party:manage")).isTrue();
        assertThat(identityService.hasPermission(tenantB.id(), identity.id(), "user:manage")).isFalse();
        assertThat(identityService.hasPermission(tenantB.id(), identity.id(), "party:read")).isTrue();
        assertThat(identityService.hasPermission(tenantB.id(), otherIssuer.id(), "party:read")).isFalse();

        assertThatThrownBy(() -> identityService.getMembership(tenantB.id(), membershipA.id()))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(identityService.listPermissions())
                .extracting("code").contains("organization:read", "party:manage", "user:manage", "role:manage");
    }

    @Test
    void organizationRowsAreIsolatedByTenantAndHierarchyCannotCrossTenant() {
        Boolean applicationRoleIsSuperuser = jdbcTemplate.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
        assertThat(applicationRoleIsSuperuser).isFalse();

        Boolean rowSecurityEnabled = jdbcTemplate.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity
                FROM pg_class
                WHERE oid = 'axiom.organization'::regclass
                """, Boolean.class);
        assertThat(rowSecurityEnabled).isTrue();

        Tenant tenantA = organizationService.createTenant("TENANT_A", "Tenant A");
        Tenant tenantB = organizationService.createTenant("TENANT_B", "Tenant B");

        Organization root = organizationService.createOrganization(
                tenantA.id(), null, "ROOT", "Root Company", "LEGAL_ENTITY");
        organizationService.createOrganization(
                tenantA.id(), root.id(), "BRANCH_1", "Branch One", "BRANCH");

        assertThat(organizationService.listOrganizations(tenantA.id()))
                .extracting(Organization::code)
                .containsExactly("BRANCH_1", "ROOT");
        assertThat(organizationService.listOrganizations(tenantB.id())).isEmpty();

        assertThatThrownBy(() -> organizationService.createOrganization(
                tenantB.id(), root.id(), "FOREIGN_CHILD", "Invalid Cross-Tenant Child", "BRANCH"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
