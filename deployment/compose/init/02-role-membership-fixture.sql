-- Test-only role graph used to prove direct and transitive pg_auth_members traversal.
-- These NOLOGIN roles receive no Axiom database or schema privileges.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'axiom_membership_probe') THEN
        EXECUTE 'CREATE ROLE axiom_membership_probe NOLOGIN NOINHERIT';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'axiom_membership_probe_direct') THEN
        EXECUTE 'CREATE ROLE axiom_membership_probe_direct NOLOGIN NOINHERIT';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'axiom_membership_probe_nested') THEN
        EXECUTE 'CREATE ROLE axiom_membership_probe_nested NOLOGIN NOINHERIT';
    END IF;
END
$$;

GRANT axiom_membership_probe TO axiom_membership_probe_direct;
GRANT axiom_membership_probe_direct TO axiom_membership_probe_nested;
