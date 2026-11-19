package io.projectaxiom.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal identity, tenant membership and role operations.
 *
 * Authentication is delegated to an external OIDC provider. These methods do not
 * validate tokens; only a trusted authentication adapter may call them after token
 * signature, issuer, audience and lifetime validation.
 */
@Service
public class IdentityService {
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final JdbcTemplate jdbc;

    public IdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public IdentityUser registerIdentity(String issuer, String subject) {
        return registerIdentity(issuer, subject, null, null);
    }

    /**
     * Resolves an external identity by the OIDC-stable (issuer, subject) pair.
     * Email and display name are optional profile claims, not identity keys.
     */
    @Transactional
    public IdentityUser registerIdentity(String issuer, String subject, String email, String displayName) {
        if (issuer == null || issuer.isBlank() || issuer.length() > 500) {
            throw new IllegalArgumentException("issuer must be nonblank and at most 500 characters");
        }
        if (subject == null || subject.isBlank() || subject.length() > 300) {
            throw new IllegalArgumentException("subject must be nonblank and at most 300 characters");
        }
        if (email != null && email.length() > 320) {
            throw new IllegalArgumentException("email must be at most 320 characters");
        }
        if (displayName != null && displayName.length() > 200) {
            throw new IllegalArgumentException("displayName must be at most 200 characters");
        }

        jdbc.update("""
                INSERT INTO axiom.user_identity (id, issuer, subject, email, display_name)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (issuer, subject) DO NOTHING
                """, UUID.randomUUID(), issuer, subject, email, displayName);

        return jdbc.queryForObject("""
                SELECT id, issuer, subject, email, display_name, status, created_at
                FROM axiom.user_identity
                WHERE issuer = ? AND subject = ?
                """, (rs, row) -> mapIdentityUser(rs), issuer, subject);
    }

    @Transactional
    public TenantRole createRole(UUID tenantId, String roleCode, String name,
                                 String description, Set<String> permissionCodes) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (roleCode == null || !ROLE_CODE.matcher(roleCode).matches() || roleCode.length() > 64) {
            throw new IllegalArgumentException("roleCode must match [A-Z][A-Z0-9_]* and be at most 64 characters");
        }
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("name must be nonblank and at most 120 characters");
        }
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("description must be at most 500 characters");
        }

        establishTenant(tenantId);
        jdbc.update("""
                INSERT INTO axiom.tenant_role (tenant_id, role_code, name, description)
                VALUES (?, ?, ?, ?)
                """, tenantId, roleCode, name, description);

        Set<String> grants = permissionCodes == null ? Set.of() : Set.copyOf(permissionCodes);
        for (String permissionCode : grants) {
            if (permissionCode == null || permissionCode.isBlank()) {
                throw new IllegalArgumentException("permission codes must be nonblank");
            }
            jdbc.update("""
                    INSERT INTO axiom.role_permission (tenant_id, role_code, permission_code)
                    VALUES (?, ?, ?)
                    """, tenantId, roleCode, permissionCode);
        }
        return findRole(tenantId, roleCode);
    }

    @Transactional
    public TenantMembership createMembership(UUID tenantId, UUID identityId, Set<String> roleCodes) {
        if (tenantId == null || identityId == null) {
            throw new IllegalArgumentException("tenantId and identityId are required");
        }
        establishTenant(tenantId);

        String identityStatus = jdbc.queryForObject(
                "SELECT status FROM axiom.user_identity WHERE id = ?",
                String.class, identityId);
        if (!"ACTIVE".equals(identityStatus)) {
            throw new IllegalStateException("Only active identities can be added to a tenant");
        }

        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO axiom.tenant_membership (id, tenant_id, identity_id)
                VALUES (?, ?, ?)
                """, membershipId, tenantId, identityId);

        Set<String> assignedRoles = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
        for (String roleCode : assignedRoles) {
            jdbc.update("""
                    INSERT INTO axiom.membership_role (tenant_id, membership_id, role_code)
                    VALUES (?, ?, ?)
                    """, tenantId, membershipId, roleCode);
        }
        return findMembership(tenantId, membershipId);
    }

    @Transactional(readOnly = true)
    public TenantMembership getMembership(UUID tenantId, UUID membershipId) {
        if (tenantId == null || membershipId == null) {
            throw new IllegalArgumentException("tenantId and membershipId are required");
        }
        establishTenant(tenantId);
        return findMembership(tenantId, membershipId);
    }

    @Transactional(readOnly = true)
    public List<IamPermission> listPermissions() {
        return jdbc.query("""
                SELECT code, description
                FROM axiom.permission
                ORDER BY code
                """, (rs, row) -> new IamPermission(rs.getString("code"), rs.getString("description")));
    }

    /**
     * Evaluates permissions only through an active identity, active tenant membership,
     * tenant-scoped role assignment and that tenant's role-permission grants.
     */
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID tenantId, UUID identityId, String permissionCode) {
        if (tenantId == null || identityId == null || permissionCode == null || permissionCode.isBlank()) {
            throw new IllegalArgumentException("tenantId, identityId and permissionCode are required");
        }
        establishTenant(tenantId);
        Boolean granted = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM axiom.tenant_membership m
                    JOIN axiom.user_identity i ON i.id = m.identity_id
                    JOIN axiom.membership_role mr
                      ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.id
                    JOIN axiom.role_permission rp
                      ON rp.tenant_id = mr.tenant_id AND rp.role_code = mr.role_code
                    JOIN axiom.permission p ON p.code = rp.permission_code
                    WHERE m.tenant_id = ?
                      AND m.identity_id = ?
                      AND m.status = 'ACTIVE'
                      AND i.status = 'ACTIVE'
                      AND p.code = ?
                )
                """, Boolean.class, tenantId, identityId, permissionCode);
        return Boolean.TRUE.equals(granted);
    }

    private TenantMembership findMembership(UUID tenantId, UUID membershipId) {
        return jdbc.queryForObject("""
                SELECT id, tenant_id, identity_id, status, created_at
                FROM axiom.tenant_membership
                WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID rowTenantId = rs.getObject("tenant_id", UUID.class);
            UUID identityId = rs.getObject("identity_id", UUID.class);
            String status = rs.getString("status");
            var createdAt = rs.getTimestamp("created_at").toInstant();
            Set<TenantRole> roles = jdbc.query("""
                    SELECT role_code
                    FROM axiom.membership_role
                    WHERE tenant_id = ? AND membership_id = ?
                    ORDER BY role_code
                    """, (roleRs, roleRow) -> roleRs.getString(1), tenantId, membershipId)
                    .stream()
                    .map(roleCode -> findRole(tenantId, roleCode))
                    .collect(Collectors.toUnmodifiableSet());
            return new TenantMembership(id, rowTenantId, identityId, status, roles, createdAt);
        }, tenantId, membershipId);
    }

    private TenantRole findRole(UUID tenantId, String roleCode) {
        return jdbc.queryForObject("""
                SELECT tenant_id, role_code, name, description, is_system, created_at
                FROM axiom.tenant_role
                WHERE tenant_id = ? AND role_code = ?
                """, (rs, row) -> {
            List<IamPermission> permissions = jdbc.query("""
                    SELECT p.code, p.description
                    FROM axiom.role_permission rp
                    JOIN axiom.permission p ON p.code = rp.permission_code
                    WHERE rp.tenant_id = ? AND rp.role_code = ?
                    ORDER BY p.code
                    """, (permissionRs, permissionRow) -> new IamPermission(
                    permissionRs.getString("code"), permissionRs.getString("description")),
                    tenantId, roleCode);
            return new TenantRole(
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("role_code"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getBoolean("is_system"),
                    Set.copyOf(permissions),
                    rs.getTimestamp("created_at").toInstant());
        }, tenantId, roleCode);
    }

    private void establishTenant(UUID tenantId) {
        jdbc.queryForObject("SELECT set_config('axiom.tenant_id', ?, true)",
                String.class, tenantId.toString());
    }

    private IdentityUser mapIdentityUser(ResultSet rs) throws SQLException {
        return new IdentityUser(
                rs.getObject("id", UUID.class),
                rs.getString("issuer"),
                rs.getString("subject"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
