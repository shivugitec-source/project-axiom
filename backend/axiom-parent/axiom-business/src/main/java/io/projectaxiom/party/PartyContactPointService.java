package io.projectaxiom.party;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import io.projectaxiom.common.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartyContactPointService {
    private final JdbcTemplate jdbc;

    public PartyContactPointService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public PartyContactPoint add(UUID tenantId, UUID partyId, ContactPointType type, String value, String label) {
        establishTenant(tenantId);
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO axiom.party_contact_point
                    (id, tenant_id, party_id, contact_type, contact_value, label)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, tenant_id, party_id, contact_type, contact_value, label, created_at
                """, this::map, id, tenantId, partyId, type.name(), value.trim(),
                label == null || label.isBlank() ? null : label.trim());
    }

    @Transactional(readOnly = true)
    public List<PartyContactPoint> list(UUID tenantId, UUID partyId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT id, tenant_id, party_id, contact_type, contact_value, label, created_at
                FROM axiom.party_contact_point
                WHERE tenant_id = ? AND party_id = ?
                ORDER BY contact_type, created_at, id
                """, this::map, tenantId, partyId);
    }

    private void establishTenant(UUID tenantId) {
        UUID effectiveTenantId = TenantContext.requireMatchingTenant(tenantId);
        jdbc.queryForObject("SELECT set_config('axiom.tenant_id', ?, true)",
                String.class, effectiveTenantId.toString());
    }

    private PartyContactPoint map(ResultSet rs, int row) throws SQLException {
        return new PartyContactPoint(rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("party_id", UUID.class),
                ContactPointType.valueOf(rs.getString("contact_type")),
                rs.getString("contact_value"), rs.getString("label"),
                rs.getTimestamp("created_at").toInstant());
    }
}
