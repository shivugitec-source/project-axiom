# ADR 0004 — Isolate the runtime database role from role membership

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

Engineering E-22 narrowed all tenant row-level security policies to `TO axiom_app`. PostgreSQL role membership is part of the effective security boundary: a role granted membership in `axiom_app` may gain inherited privileges or a path to `SET ROLE axiom_app`, depending on membership options. That would expand the set of identities capable of using the runtime role's table privileges and satisfying its RLS policies.

The existing runtime-role integration invariant already rejects `axiom_app` being a member of another role. It did not reject other roles being granted membership in `axiom_app`. The `NOINHERIT` role attribute is not a replacement for an explicit membership boundary because membership can still permit role switching.

## Decision

1. Treat `axiom_app` as an isolated node in the PostgreSQL role-membership graph.
2. Keep the existing check that rejects `axiom_app` being a member of another role.
3. Add a recursive `pg_auth_members` catalog check that rejects any direct or transitive role membership into `axiom_app`.
4. Do not use role membership to share runtime or migration privileges. Any future membership grant requires an explicit review of inherited privileges, `SET ROLE` options and the effective RLS audience.
5. Preserve the separate `axiom_migrator` identity and all existing database grants, RLS predicates and migrations.

## Consequences

- The `TO axiom_app` tenant policies remain scoped to the intended runtime identity rather than silently extending to member roles.
- The integration test fails if a future initialization script or platform integration introduces a direct or transitive membership path.
- This is a test and architecture invariant only; it does not change production role membership, schema, migrations, table grants or API behavior.
