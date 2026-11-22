package io.projectaxiom.bootstrap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

import io.projectaxiom.bootstrap.security.AxiomAuthenticationToken;
import io.projectaxiom.bootstrap.security.AxiomJwtAuthenticationConverter;
import io.projectaxiom.common.TenantContext;
import io.projectaxiom.identity.IdentityService;
import io.projectaxiom.identity.IdentityUser;
import io.projectaxiom.identity.TenantMembership;
import io.projectaxiom.identity.TenantRole;
import io.projectaxiom.organization.Organization;
import io.projectaxiom.organization.OrganizationService;
import io.projectaxiom.organization.Tenant;
import io.projectaxiom.party.ContactPointType;
import io.projectaxiom.party.Party;
import io.projectaxiom.party.PartyContactPoint;
import io.projectaxiom.party.PartyContactPointService;
import io.projectaxiom.party.PartyRole;
import io.projectaxiom.party.PartyService;
import io.projectaxiom.party.PartyType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private PartyService partyService;

    @Autowired
    private PartyContactPointService partyContactPointService;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private AxiomJwtAuthenticationConverter authenticationConverter;

    @Test
    void startsWithPostgresAndAppliesFlywayMigration() {
        Integer schemaCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'axiom'",
                Integer.class);
        assertThat(schemaCount).isEqualTo(1);

        Set<String> appliedVersions = Arrays.stream(flyway.info().applied())
                .map(migration -> migration.getVersion().getVersion())
                .collect(Collectors.toSet());
        assertThat(appliedVersions).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6");
    }

    @Test
    void applicationDatabaseRoleIsRestrictedAndCannotBypassRls() {
        Boolean restrictedRole = jdbcTemplate.queryForObject("""
                SELECT rolcanlogin
                    AND NOT rolsuper
                    AND NOT rolcreaterole
                    AND NOT rolcreatedb
                    AND NOT rolreplication
                    AND NOT rolbypassrls
                    AND NOT rolinherit
                FROM pg_roles
                WHERE rolname = current_user
                """, Boolean.class);

        assertThat(restrictedRole)
                .as("application database role must be a login without administrative or RLS-bypass attributes")
                .isTrue();

        Boolean hasRoleMembership = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_auth_members
                    WHERE member = (
                        SELECT oid FROM pg_roles WHERE rolname = current_user
                    )
                )
                """, Boolean.class);
        assertThat(hasRoleMembership)
                .as("application role must not be able to SET ROLE to another database role")
                .isFalse();

        Boolean requiredDatabasePrivileges = jdbcTemplate.queryForObject("""
                SELECT has_database_privilege(current_user, current_database(), 'CONNECT')
                    AND NOT has_database_privilege(current_user, current_database(), 'CREATE')
                """, Boolean.class);
        assertThat(requiredDatabasePrivileges)
                .as("runtime application role must connect but must not create database objects")
                .isTrue();
    }

    @Test
    void migrationRoleOwnsSchemaAndRuntimeRoleCannotOwnDatabaseObjects() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo("axiom_app");

        Boolean migrationRoleRestricted = jdbcTemplate.queryForObject("""
                SELECT rolcanlogin
                    AND NOT rolsuper
                    AND NOT rolcreaterole
                    AND NOT rolcreatedb
                    AND NOT rolreplication
                    AND NOT rolbypassrls
                    AND NOT rolinherit
                FROM pg_roles
                WHERE rolname = 'axiom_migrator'
                """, Boolean.class);
        assertThat(migrationRoleRestricted)
                .as("migration role must be a dedicated non-administrative login")
                .isTrue();

        Boolean schemaOwnedByMigrator = jdbcTemplate.queryForObject("""
                SELECT n.nspowner = r.oid
                    AND has_schema_privilege('axiom_migrator', n.oid, 'CREATE')
                    AND NOT has_schema_privilege(current_user, n.oid, 'CREATE')
                    AND has_schema_privilege(current_user, n.oid, 'USAGE')
                FROM pg_namespace n
                CROSS JOIN pg_roles r
                WHERE n.nspname = 'axiom' AND r.rolname = 'axiom_migrator'
                """, Boolean.class);
        assertThat(schemaOwnedByMigrator)
                .as("migration role must own the schema while runtime receives usage only")
                .isTrue();
        Boolean migrationRoleHasRequiredDdl = jdbcTemplate.queryForObject("""
                SELECT has_database_privilege('axiom_migrator', current_database(), 'CREATE')
                    AND has_schema_privilege('axiom_migrator', 'axiom', 'CREATE')
                """, Boolean.class);
        assertThat(migrationRoleHasRequiredDdl)
                .as("only the migration identity receives database/schema CREATE required by the existing V1 schema migration")
                .isTrue();

        Integer runtimeOwnedObjects = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'axiom'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
                  AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
                """, Integer.class);
        assertThat(runtimeOwnedObjects)
                .as("runtime role must not own tables, views or sequences in the application schema")
                .isZero();

        Integer objectsNotOwnedByMigrator = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'axiom'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
                  AND c.relowner <> (SELECT oid FROM pg_roles WHERE rolname = 'axiom_migrator')
                """, Integer.class);
        assertThat(objectsNotOwnedByMigrator)
                .as("all application schema relations must be owned by the migration identity")
                .isZero();

        Boolean runtimeHasNoDatabaseDdl = jdbcTemplate.queryForObject("""
                SELECT NOT has_database_privilege(current_user, current_database(), 'CREATE')
                    AND NOT has_table_privilege(current_user, 'axiom.flyway_schema_history', 'SELECT')
                    AND has_table_privilege(current_user, 'axiom.party', 'SELECT')
                    AND has_table_privilege(current_user, 'axiom.party', 'INSERT')
                    AND has_table_privilege(current_user, 'axiom.party', 'UPDATE')
                    AND has_table_privilege(current_user, 'axiom.party', 'DELETE')
                    AND has_table_privilege(current_user, 'axiom.permission', 'SELECT')
                    AND NOT has_table_privilege(current_user, 'axiom.permission', 'INSERT')
                    AND NOT has_table_privilege(current_user, 'axiom.permission', 'UPDATE')
                    AND NOT has_table_privilege(current_user, 'axiom.permission', 'DELETE')
                """, Boolean.class);
        assertThat(runtimeHasNoDatabaseDdl)
                .as("runtime receives business-table DML, read-only permission catalogue access and no Flyway history access")
                .isTrue();

        Boolean migrationRoleHasNoMemberships = jdbcTemplate.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM pg_auth_members
                    WHERE member = (SELECT oid FROM pg_roles WHERE rolname = 'axiom_migrator')
                )
                """, Boolean.class);
        assertThat(migrationRoleHasNoMemberships)
                .as("migration role must not inherit or SET ROLE to another role")
                .isTrue();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThatThrownBy(() -> jdbcTemplate.execute(
                    "ALTER TABLE axiom.party DISABLE ROW LEVEL SECURITY"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            transaction.setRollbackOnly();
        });
    }

    @Test
    void futureTablesDefaultToRuntimeReadOnlyPrivileges() {
        Boolean runtimeDefaultsAreReadOnly = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_default_acl d
                    CROSS JOIN LATERAL aclexplode(d.defaclacl) privilege
                    WHERE d.defaclrole = (SELECT oid FROM pg_roles WHERE rolname = 'axiom_migrator')
                      AND d.defaclnamespace = 'axiom'::regnamespace
                      AND d.defaclobjtype = 'r'
                      AND privilege.grantee = (SELECT oid FROM pg_roles WHERE rolname = 'axiom_app')
                      AND privilege.privilege_type = 'SELECT'
                      AND NOT privilege.is_grantable
                )
                AND NOT EXISTS (
                    SELECT 1
                    FROM pg_default_acl d
                    CROSS JOIN LATERAL aclexplode(d.defaclacl) privilege
                    WHERE d.defaclrole = (SELECT oid FROM pg_roles WHERE rolname = 'axiom_migrator')
                      AND d.defaclnamespace = 'axiom'::regnamespace
                      AND d.defaclobjtype = 'r'
                      AND privilege.grantee IN (
                          0, (SELECT oid FROM pg_roles WHERE rolname = 'axiom_app')
                      )
                      AND (privilege.privilege_type <> 'SELECT' OR privilege.is_grantable)
                )
                """, Boolean.class);

        assertThat(runtimeDefaultsAreReadOnly)
                .as("future migration-created tables must be readable but not writable by default")
                .isTrue();
    }

    @Test
    void everyTenantOwnedTableHasForcedTenantScopedRlsPolicies() {
        List<String> tenantTables = jdbcTemplate.queryForList("""
                SELECT DISTINCT table_name
                FROM information_schema.columns
                WHERE table_schema = 'axiom' AND column_name = 'tenant_id'
                ORDER BY table_name
                """, String.class);

        assertThat(tenantTables).containsExactlyInAnyOrder(
                "membership_role",
                "organization",
                "party",
                "party_contact_point",
                "party_role",
                "role_permission",
                "tenant_membership",
                "tenant_role");

        for (String table : tenantTables) {
            Boolean forcedRls = jdbcTemplate.queryForObject("""
                    SELECT c.relrowsecurity AND c.relforcerowsecurity
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = 'axiom' AND c.relname = ?
                    """, Boolean.class, table);
            assertThat(forcedRls).as(table + " must enable and force RLS").isTrue();

            Integer policyCount = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_policies
                    WHERE schemaname = 'axiom' AND tablename = ?
                    """, Integer.class, table);
            assertThat(policyCount).as(table + " must define at least one RLS policy")
                    .isGreaterThan(0);

            Integer unscopedPolicies = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_policies
                    WHERE schemaname = 'axiom'
                      AND tablename = ?
                      AND (
                          (cmd IN ('SELECT', 'UPDATE', 'DELETE', 'ALL')
                              AND COALESCE(qual, '') NOT LIKE '%axiom.tenant_id%')
                          OR
                          (cmd IN ('INSERT', 'UPDATE', 'ALL')
                              AND COALESCE(with_check, '') NOT LIKE '%axiom.tenant_id%')
                      )
                    """, Integer.class, table);
            assertThat(unscopedPolicies)
                    .as(table + " must not have an RLS policy that bypasses tenant context")
                    .isZero();
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
    void activeMembershipRequiresMatchingTenantAndActiveIdentityAndMembership() {
        Tenant tenantA = organizationService.createTenant("IAM_MEMBERSHIP_TENANT_A", "Membership Tenant A");
        Tenant tenantB = organizationService.createTenant("IAM_MEMBERSHIP_TENANT_B", "Membership Tenant B");
        String subject = "membership-" + UUID.randomUUID();
        IdentityUser identity = identityService.registerIdentity(
                "https://identity.example.test/realms/axiom", subject);
        TenantMembership membership = identityService.createMembership(tenantA.id(), identity.id(), Set.of());

        assertThat(identityService.hasActiveMembership(tenantA.id(), identity.id())).isTrue();
        assertThat(identityService.hasActiveMembership(tenantB.id(), identity.id())).isFalse();

        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            jdbcTemplate.queryForObject("SELECT set_config('axiom.tenant_id', ?, true)",
                    String.class, tenantA.id().toString());
            jdbcTemplate.update("""
                    UPDATE axiom.tenant_membership
                    SET status = 'SUSPENDED'
                    WHERE tenant_id = ? AND id = ?
                    """, tenantA.id(), membership.id());
        });
        assertThat(identityService.hasActiveMembership(tenantA.id(), identity.id())).isFalse();

        IdentityUser disabledIdentity = identityService.registerIdentity(
                "https://identity.example.test/realms/axiom", "disabled-" + UUID.randomUUID());
        identityService.createMembership(tenantB.id(), disabledIdentity.id(), Set.of());
        jdbcTemplate.update("UPDATE axiom.user_identity SET status = 'DISABLED' WHERE id = ?",
                disabledIdentity.id());
        assertThat(identityService.hasActiveMembership(tenantB.id(), disabledIdentity.id())).isFalse();
    }

    @Test
    void oidcSubjectMapsToStableGlobalIdentity() {
        String issuer = "https://identity.example.test/realms/axiom";
        String subject = "integration-" + UUID.randomUUID();
        Jwt token = Jwt.withTokenValue("validated-test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject(subject)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .build();

        AxiomAuthenticationToken authentication = authenticationConverter.convert(token);
        IdentityUser stored = identityService.registerIdentity(issuer, subject);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal().identityId()).isEqualTo(stored.id());
        assertThat(authentication.getPrincipal().issuer()).isEqualTo(issuer);
        assertThat(authentication.getPrincipal().subject()).isEqualTo(subject);
        assertThat(authentication.getAuthorities()).isEmpty();

        Jwt sameSubjectFromOtherIssuer = Jwt.withTokenValue("other-validated-test-token")
                .header("alg", "RS256")
                .issuer("https://other-identity.example.test/realms/axiom")
                .subject(subject)
                .issuedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .expiresAt(Instant.parse("2030-01-01T00:00:00Z"))
                .build();
        AxiomAuthenticationToken otherIssuerAuthentication =
                authenticationConverter.convert(sameSubjectFromOtherIssuer);

        assertThat(otherIssuerAuthentication.getPrincipal().identityId()).isNotEqualTo(stored.id());
    }

    @Test
    void verifiedRequestTenantCannotBeOverriddenByServiceTenantArgument() {
        Tenant verifiedTenant = organizationService.createTenant("CTX_TENANT_A", "Context Tenant A");
        Tenant otherTenant = organizationService.createTenant("CTX_TENANT_B", "Context Tenant B");

        try (TenantContext.Scope ignored = TenantContext.open(verifiedTenant.id())) {
            assertThatThrownBy(() -> organizationService.listOrganizations(otherTenant.id()))
                    .isInstanceOf(TenantContext.TenantContextMismatchException.class);
            assertThat(organizationService.listOrganizations(verifiedTenant.id())).isEmpty();
        }
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void partyContactPointsAreTenantIsolatedAndPartyBound() {
        Boolean forcedRls = jdbcTemplate.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity
                FROM pg_class
                WHERE oid = 'axiom.party_contact_point'::regclass
                """, Boolean.class);
        assertThat(forcedRls).isTrue();

        Tenant tenantA = organizationService.createTenant("CONTACT_TENANT_A", "Contact Tenant A");
        Tenant tenantB = organizationService.createTenant("CONTACT_TENANT_B", "Contact Tenant B");
        Party partyA = partyService.createParty(tenantA.id(), PartyType.ORGANIZATION,
                "Contact Party A", Set.of(PartyRole.CUSTOMER));
        Party partyB = partyService.createParty(tenantB.id(), PartyType.ORGANIZATION,
                "Contact Party B", Set.of(PartyRole.SUPPLIER));

        PartyContactPoint email = partyContactPointService.add(
                tenantA.id(), partyA.id(), ContactPointType.EMAIL, " sales@example.test ", "Sales");
        PartyContactPoint phone = partyContactPointService.add(
                tenantA.id(), partyA.id(), ContactPointType.PHONE, "+91 80000 00000", "Office");

        assertThat(email.value()).isEqualTo("sales@example.test");
        assertThat(email.label()).isEqualTo("Sales");
        assertThat(partyContactPointService.list(tenantA.id(), partyA.id()))
                .extracting(PartyContactPoint::type)
                .containsExactly(ContactPointType.EMAIL, ContactPointType.PHONE);
        assertThat(partyContactPointService.list(tenantB.id(), partyB.id())).isEmpty();
        assertThat(partyContactPointService.list(tenantB.id(), partyA.id())).isEmpty();

        assertThatThrownBy(() -> partyContactPointService.add(
                tenantB.id(), partyA.id(), ContactPointType.EMAIL, "cross-tenant@example.test", null))
                .isInstanceOf(DataIntegrityViolationException.class);
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
