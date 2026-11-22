CREATE TABLE axiom.party (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES axiom.tenant (id),
    party_type VARCHAR(24) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_party_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_party_type CHECK (party_type IN ('PERSON', 'ORGANIZATION')),
    CONSTRAINT ck_party_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_party_tenant_name ON axiom.party (tenant_id, display_name);

CREATE TABLE axiom.party_role (
    tenant_id UUID NOT NULL,
    party_id UUID NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_party_role PRIMARY KEY (tenant_id, party_id, role_code),
    CONSTRAINT fk_party_role_party FOREIGN KEY (tenant_id, party_id)
        REFERENCES axiom.party (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_party_role_code CHECK (
        role_code IN ('CUSTOMER', 'SUPPLIER', 'EMPLOYEE', 'OWNER', 'CONTACT', 'PARTNER')
    )
);

CREATE INDEX ix_party_role_tenant_code ON axiom.party_role (tenant_id, role_code);

ALTER TABLE axiom.party ENABLE ROW LEVEL SECURITY;
ALTER TABLE axiom.party FORCE ROW LEVEL SECURITY;
CREATE POLICY party_tenant_isolation ON axiom.party
    USING (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID);

ALTER TABLE axiom.party_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE axiom.party_role FORCE ROW LEVEL SECURITY;
CREATE POLICY party_role_tenant_isolation ON axiom.party_role
    USING (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID);
