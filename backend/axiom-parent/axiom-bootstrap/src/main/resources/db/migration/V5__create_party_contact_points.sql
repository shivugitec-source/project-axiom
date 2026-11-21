CREATE TABLE axiom.party_contact_point (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    party_id UUID NOT NULL,
    contact_type VARCHAR(16) NOT NULL,
    contact_value VARCHAR(320) NOT NULL,
    label VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_party_contact_point_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_party_contact_point_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES axiom.party (tenant_id, id),
    CONSTRAINT ck_party_contact_point_type CHECK (contact_type IN ('EMAIL', 'PHONE')),
    CONSTRAINT ck_party_contact_point_value CHECK (length(btrim(contact_value)) > 0)
);

CREATE INDEX ix_party_contact_point_party
    ON axiom.party_contact_point (tenant_id, party_id, created_at, id);

ALTER TABLE axiom.party_contact_point ENABLE ROW LEVEL SECURITY;
ALTER TABLE axiom.party_contact_point FORCE ROW LEVEL SECURITY;
CREATE POLICY party_contact_point_tenant_isolation ON axiom.party_contact_point
    USING (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID);
