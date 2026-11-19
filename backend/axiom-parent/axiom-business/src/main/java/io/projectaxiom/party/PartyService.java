package io.projectaxiom.party;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartyService {
    private final JdbcTemplate jdbc;

    public PartyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Party createParty(UUID tenantId, PartyType partyType, String displayName, Set<PartyRole> roles) {
        establishTenant(tenantId);
        UUID partyId = UUID.randomUUID();
        Party created = jdbc.queryForObject("""
                INSERT INTO axiom.party (id, tenant_id, party_type, display_name)
                VALUES (?, ?, ?, ?)
                RETURNING id, tenant_id, party_type, display_name, status, created_at
                """, (rs, row) -> mapParty(rs, Set.of()),
                partyId, tenantId, partyType.name(), displayName);

        if (roles != null && !roles.isEmpty()) {
            for (PartyRole role : roles) {
                jdbc.update("""
                        INSERT INTO axiom.party_role (tenant_id, party_id, role_code)
                        VALUES (?, ?, ?)
                        """, tenantId, partyId, role.name());
            }
        }
        return findParty(tenantId, partyId);
    }

    @Transactional(readOnly = true)
    public List<Party> listParties(UUID tenantId) {
        establishTenant(tenantId);
        List<Party> parties = jdbc.query("""
                SELECT id, tenant_id, party_type, display_name, status, created_at
                FROM axiom.party
                ORDER BY display_name, id
                """, (rs, row) -> mapParty(rs, Set.of()));
        return parties.stream().map(party -> findParty(tenantId, party.id())).toList();
    }

    @Transactional(readOnly = true)
    public Party getParty(UUID tenantId, UUID partyId) {
        establishTenant(tenantId);
        return findParty(tenantId, partyId);
    }

    private Party findParty(UUID tenantId, UUID partyId) {
        return jdbc.queryForObject("""
                SELECT p.id, p.tenant_id, p.party_type, p.display_name, p.status, p.created_at
                FROM axiom.party p
                WHERE p.tenant_id = ? AND p.id = ?
                """, (rs, row) -> {
                    Party base = mapParty(rs, Set.of());
                    List<String> roleCodes = jdbc.query("""
                            SELECT role_code FROM axiom.party_role
                            WHERE tenant_id = ? AND party_id = ?
                            ORDER BY role_code
                            """, (roleRs, roleRow) -> roleRs.getString(1), tenantId, partyId);
                    EnumSet<PartyRole> roles = EnumSet.noneOf(PartyRole.class);
                    roleCodes.forEach(code -> roles.add(PartyRole.valueOf(code)));
                    return new Party(base.id(), base.tenantId(), base.partyType(), base.displayName(),
                            base.status(), Set.copyOf(roles), base.createdAt());
                }, tenantId, partyId);
    }

    private void establishTenant(UUID tenantId) {
        UUID effectiveTenantId = TenantContext.requireMatchingTenant(tenantId);
        jdbc.queryForObject("SELECT set_config('axiom.tenant_id', ?, true)", String.class, effectiveTenantId.toString());
    }

    private Party mapParty(ResultSet rs, Set<PartyRole> roles) throws SQLException {
        return new Party(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                PartyType.valueOf(rs.getString("party_type")), rs.getString("display_name"),
                rs.getString("status"), roles, rs.getTimestamp("created_at").toInstant());
    }
}
