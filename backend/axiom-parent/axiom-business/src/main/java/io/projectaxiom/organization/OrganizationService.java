package io.projectaxiom.organization;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
    private final JdbcTemplate jdbc;

    public OrganizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Platform provisioning operation. Authorization is intentionally deferred to the identity capability. */
    @Transactional
    public Tenant createTenant(String code, String name) {
        return jdbc.queryForObject("""
                INSERT INTO axiom.tenant (id, code, name)
                VALUES (?, ?, ?)
                RETURNING id, code, name, status, created_at
                """, (rs, row) -> new Tenant(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                UUID.randomUUID(), code, name);
    }

    @Transactional
    public Organization createOrganization(UUID tenantId, UUID parentId, String code, String name, String type) {
        establishTenant(tenantId);
        return jdbc.queryForObject("""
                INSERT INTO axiom.organization
                    (id, tenant_id, parent_id, code, name, organization_type)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, tenant_id, parent_id, code, name,
                          organization_type, status, created_at
                """, (rs, row) -> mapOrganization(rs),
                UUID.randomUUID(), tenantId, parentId, code, name, type);
    }

    @Transactional(readOnly = true)
    public List<Organization> listOrganizations(UUID tenantId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT id, tenant_id, parent_id, code, name,
                       organization_type, status, created_at
                FROM axiom.organization
                ORDER BY code
                """, (rs, row) -> mapOrganization(rs));
    }

    private void establishTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('axiom.tenant_id', ?, true)", String.class, tenantId.toString());
    }

    private Organization mapOrganization(ResultSet rs) throws SQLException {
        return new Organization(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("parent_id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("organization_type"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
