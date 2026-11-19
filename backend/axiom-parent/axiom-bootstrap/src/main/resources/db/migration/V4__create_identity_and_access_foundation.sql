-- External identities are provider-neutral references. Project Axiom never stores IdP passwords.
CREATE TABLE axiom.user_identity (
    id UUID PRIMARY KEY,
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(300) NOT NULL,
    email VARCHAR(320),
    display_name VARCHAR(200),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_identity_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_user_identity_issuer_nonblank CHECK (length(btrim(issuer)) > 0),
    CONSTRAINT ck_user_identity_subject_nonblank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT ck_user_identity_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- A global identity may participate in multiple tenants; membership is the tenant boundary.
CREATE TABLE axiom.tenant_membership (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES axiom.tenant (id),
    identity_id UUID NOT NULL REFERENCES axiom.user_identity (id),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_membership_tenant_identity UNIQUE (tenant_id, identity_id),
    CONSTRAINT uq_membership_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_membership_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED'))
);
CREATE INDEX ix_membership_identity ON axiom.tenant_membership (identity_id);
CREATE INDEX ix_membership_tenant_status ON axiom.tenant_membership (tenant_id, status);

-- Roles are tenant-defined; permissions are a platform-owned capability catalogue.
CREATE TABLE axiom.tenant_role (
    tenant_id UUID NOT NULL REFERENCES axiom.tenant (id),
    role_code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_tenant_role PRIMARY KEY (tenant_id, role_code),
    CONSTRAINT ck_tenant_role_code CHECK (role_code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_tenant_role_name_nonblank CHECK (length(btrim(name)) > 0)
);

CREATE TABLE axiom.permission (
    code VARCHAR(100) PRIMARY KEY,
    description VARCHAR(300) NOT NULL
);

INSERT INTO axiom.permission (code, description) VALUES
    ('organization:read', 'View organizations'),
    ('organization:manage', 'Create and update organizations'),
    ('party:read', 'View parties'),
    ('party:manage', 'Create and update parties'),
    ('user:read', 'View tenant memberships'),
    ('user:manage', 'Invite, suspend and manage tenant memberships'),
    ('role:read', 'View tenant roles and permission assignments'),
    ('role:manage', 'Create roles and assign permissions');

CREATE TABLE axiom.role_permission (
    tenant_id UUID NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(100) NOT NULL REFERENCES axiom.permission (code),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_role_permission PRIMARY KEY (tenant_id, role_code, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (tenant_id, role_code)
        REFERENCES axiom.tenant_role (tenant_id, role_code) ON DELETE CASCADE
);

CREATE TABLE axiom.membership_role (
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_membership_role PRIMARY KEY (tenant_id, membership_id, role_code),
    CONSTRAINT fk_membership_role_membership FOREIGN KEY (tenant_id, membership_id)
        REFERENCES axiom.tenant_membership (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_membership_role_role FOREIGN KEY (tenant_id, role_code)
        REFERENCES axiom.tenant_role (tenant_id, role_code)
);

CREATE INDEX ix_membership_role_role ON axiom.membership_role (tenant_id, role_code);
CREATE INDEX ix_role_permission_permission ON axiom.role_permission (permission_code);

-- Tenant context is mandatory for every tenant-owned IAM table.
DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['tenant_membership', 'tenant_role', 'role_permission', 'membership_role']
    LOOP
        EXECUTE format('ALTER TABLE axiom.%I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE axiom.%I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY %I ON axiom.%I USING (tenant_id = NULLIF(current_setting(''axiom.tenant_id'', true), '''')::UUID) WITH CHECK (tenant_id = NULLIF(current_setting(''axiom.tenant_id'', true), '''')::UUID)',
            table_name || '_tenant_isolation', table_name
        );
    END LOOP;
END $$;
