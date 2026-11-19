package io.projectaxiom.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.projectaxiom.organization.Organization;
import io.projectaxiom.organization.OrganizationService;
import io.projectaxiom.organization.Tenant;
import io.projectaxiom.party.Party;
import io.projectaxiom.party.PartyRole;
import io.projectaxiom.party.PartyService;
import io.projectaxiom.party.PartyType;
import java.util.Set;
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

    @Test
    void startsWithPostgresAndAppliesFlywayMigration() {
        Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'axiom'",
                Integer.class);
        assertThat(schemaCount).isEqualTo(1);

        Integer platformMigrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axiom.flyway_schema_history WHERE version = '1' AND success = true",
                Integer.class);
        assertThat(platformMigrationCount).isEqualTo(1);

        Integer organizationMigrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axiom.flyway_schema_history WHERE version = '2' AND success = true",
                Integer.class);
        assertThat(organizationMigrationCount).isEqualTo(1);

        Integer partyMigrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM axiom.flyway_schema_history WHERE version = '3' AND success = true",
                Integer.class);
        assertThat(partyMigrationCount).isEqualTo(1);
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
        Boolean partyRls = jdbcTemplate.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom.party'::regclass", Boolean.class);
        Boolean roleRls = jdbcTemplate.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE oid = 'axiom.party_role'::regclass", Boolean.class);
        assertThat(partyRls).isTrue();
        assertThat(roleRls).isTrue();

        Tenant tenantA = organizationService.createTenant("PARTY_TENANT_A", "Party Tenant A");
        Tenant tenantB = organizationService.createTenant("PARTY_TENANT_B", "Party Tenant B");
        Party created = partyService.createParty(tenantA.id(), PartyType.ORGANIZATION,
                "Acme Supplies", Set.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER));
        assertThat(created.roles()).containsExactlyInAnyOrder(PartyRole.CUSTOMER, PartyRole.SUPPLIER);
        assertThat(partyService.listParties(tenantA.id())).extracting(Party::displayName).containsExactly("Acme Supplies");
        assertThat(partyService.listParties(tenantB.id())).isEmpty();
        assertThatThrownBy(() -> partyService.getParty(tenantB.id(), created.id()))
                .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
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
