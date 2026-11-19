package io.projectaxiom.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal identity and tenant-membership operations.
 *
 * Authentication is delegated to an external OIDC provider. These methods do not
 * authenticate callers; tenant-facing HTTP exposure must wait for a verified principal.
 */
@Service
public class IdentityService {
    private final JdbcTemplate jdbc;

    public IdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Registers or resolves the stable (issuer, subject) identity pair from a verified
     * identity-provider assertion. This method itself does not validate a token.
     */
    @Transactional
    public IdentityUser registerIdentity(String issuer, String subject) {
        if (issuer == null || issuer.isBlank() || issuer.length() > 512) {
            throw new IllegalArgumentException("issuer must be nonblank and at most 512 characters");
        }
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw new IllegalArgumentException("subject must be nonblank and at most 255 characters");
        }

        jdbc.update("""
                INSERT INTO axiom.identity_user (id, issuer, subject)
                VALUES (?, ?, ?)
                ON CONFLICT (issuer, subject) DO NOTHING
                """, UUID.randomUUID(), issuer, subject);

        return jdbc.queryForObject("""
                SELECT id, issuer, subject, status, created_at
                FROM axiom.identity_user
                WHERE issuer = ? AND subject = ?
                """, (rs, row) -> mapIdentityUser(rs), issuer, subject);
    }

    @Transactional
    public TenantMembership createMembership(UUID tenantId, UUID identityUserId, Set<TenantRole> roles) {
        if (tenantId == null || identityUserId == null) {
            throw new IllegalArgumentException("tenantId and identityUserId are required");
        }
        establishTenant(tenantId);

        String identityStatus = jdbc.queryForObject(
                "SELECT status FROM axiom.identity_user WHERE id = ?",
                String.class, identityUserId);
        if (!"ACTIVE".equals(identityStatus)) {
            throw new IllegalStateException("Only active identities can be added to a tenant");
        }

        UUID membershipId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO axiom.tenant_membership (id, tenant_id, identity_user_id)
                VALUES (?, ?, ?)
                """, membershipId, tenantId, identityUserId);

        Set<TenantRole> assignedRoles = roles == null ? Set.of() : Set.copyOf(roles);
        for (TenantRole role : assignedRoles) {
            jdbc.update("""
                    INSERT INTO axiom.tenant_membership_role (tenant_id, membership_id, role_code)
                    VALUES (?, ?, ?)
                    """, tenantId, membershipId, role.name());
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

    /**
     * Evaluates a permission for an identity in one tenant. The tenant context is
     * transaction-local and both membership and role rows remain protected by RLS.
     */
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID tenantId, UUID identityUserId, IamPermission permission) {
        if (tenantId == null || identityUserId == null || permission == null) {
            throw new IllegalArgumentException("tenantId, identityUserId and permission are required");
        }
        establishTenant(tenantId);
        Boolean granted = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM axiom.tenant_membership m
                    JOIN axiom.identity_user iu ON iu.id = m.identity_user_id
                    JOIN axiom.tenant_membership_role mr
                      ON mr.tenant_id = m.tenant_id AND mr.membership_id = m.id
                    JOIN axiom.iam_role_permission rp ON rp.role_code = mr.role_code
                    JOIN axiom.iam_permission p ON p.code = rp.permission_code
                    WHERE m.tenant_id = ?
                      AND m.identity_user_id = ?
                      AND m.status = 'ACTIVE'
                      AND iu.status = 'ACTIVE'
                      AND p.code = ?
                )
                """, Boolean.class, tenantId, identityUserId, permission.code());
        return Boolean.TRUE.equals(granted);
    }

    private TenantMembership findMembership(UUID tenantId, UUID membershipId) {
        return jdbc.queryForObject("""
                SELECT id, tenant_id, identity_user_id, status, created_at
                FROM axiom.tenant_membership
                WHERE tenant_id = ? AND id = ?
                """, (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID rowTenantId = rs.getObject("tenant_id", UUID.class);
            UUID identityUserId = rs.getObject("identity_user_id", UUID.class);
            String status = rs.getString("status");
            var createdAt = rs.getTimestamp("created_at").toInstant();
            Set<TenantRole> roles = jdbc.query("""
                    SELECT role_code
                    FROM axiom.tenant_membership_role
                    WHERE tenant_id = ? AND membership_id = ?
                    ORDER BY role_code
                    """, (roleRs, roleRow) -> TenantRole.valueOf(roleRs.getString(1)),
                    tenantId, membershipId).stream().collect(Collectors.toUnmodifiableSet());
            return new TenantMembership(id, rowTenantId, identityUserId, status, roles, createdAt);
        }, tenantId, membershipId);
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
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
