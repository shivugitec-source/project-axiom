-- Runtime access is explicit and separate from migration ownership.
-- The runtime identity must never own schema objects or manage Flyway history.
GRANT USAGE ON SCHEMA axiom TO axiom_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    axiom.tenant,
    axiom.organization,
    axiom.party,
    axiom.party_role,
    axiom.user_identity,
    axiom.tenant_membership,
    axiom.tenant_role,
    axiom.role_permission,
    axiom.membership_role,
    axiom.party_contact_point
TO axiom_app;

-- The platform permission catalogue is read-only to the runtime application.
GRANT SELECT ON TABLE axiom.permission TO axiom_app;

-- Default privileges make newly created tables readable; write access must be
-- explicitly granted by the migration that introduces each runtime capability.
REVOKE ALL PRIVILEGES ON TABLE axiom.flyway_schema_history FROM axiom_app;
