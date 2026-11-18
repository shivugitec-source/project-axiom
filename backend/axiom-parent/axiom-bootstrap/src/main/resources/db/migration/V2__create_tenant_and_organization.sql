CREATE TABLE axiom.tenant (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE axiom.organization (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES axiom.tenant (id),
    parent_id UUID NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(160) NOT NULL,
    organization_type VARCHAR(40) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_organization_tenant_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_organization_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_organization_parent_same_tenant
        FOREIGN KEY (tenant_id, parent_id) REFERENCES axiom.organization (tenant_id, id),
    CONSTRAINT ck_organization_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_organization_type CHECK (organization_type IN ('LEGAL_ENTITY', 'BUSINESS_UNIT', 'BRANCH', 'DEPARTMENT', 'WAREHOUSE', 'OTHER')),
    CONSTRAINT ck_organization_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX ix_organization_tenant_parent ON axiom.organization (tenant_id, parent_id);

-- Tenant-owned rows are visible only after the current transaction establishes tenant context.
ALTER TABLE axiom.organization ENABLE ROW LEVEL SECURITY;
ALTER TABLE axiom.organization FORCE ROW LEVEL SECURITY;

CREATE POLICY organization_tenant_isolation ON axiom.organization
    USING (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('axiom.tenant_id', true), '')::UUID);
