# ADR 0005 — Isolate the migration database role from role membership

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

The `axiom_migrator` identity owns the application schema and has schema-level CREATE privileges so the isolated migration process can apply versioned schema changes. It is intentionally separate from the API's `axiom_app` runtime identity.

The existing PostgreSQL catalog invariant rejects `axiom_migrator` being a member of another role. It did not reject another role being granted membership in `axiom_migrator`. Such a member may inherit privileges or use `SET ROLE axiom_migrator`, depending on membership options. The `NOINHERIT` attribute does not by itself remove the ability to switch roles.

## Decision

1. Treat `axiom_migrator` as an isolated node in the PostgreSQL role-membership graph.
2. Preserve the existing check that rejects the migration identity being a member of another role.
3. Add a recursive `pg_auth_members` catalog check that rejects any direct or transitive role membership into `axiom_migrator`.
4. Do not use role membership to share migration-owner privileges. Any future membership grant requires explicit review of inherited privileges, `SET ROLE` options and the migration trust boundary.
5. Keep migration credentials restricted to the standalone one-shot `axiom-migrator` process; do not pass them to API instances.

## Consequences

- No unrelated login can silently acquire a path to the migration identity's schema ownership and DDL capabilities.
- PostgreSQL integration validation fails if initialization or platform configuration introduces a direct or transitive membership path.
- This is a test and architecture invariant only. It does not change production role membership, grants, schema, migrations, RLS policies, credentials or API behavior.
