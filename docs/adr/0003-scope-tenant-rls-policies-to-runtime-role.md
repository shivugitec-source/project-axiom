# ADR 0003 — Scope tenant RLS policies to the runtime role

- **Status:** Accepted
- **Date:** 2026-11-23

## Context

PostgreSQL policies created without a `TO` clause apply to `PUBLIC`. The tenant-owned tables already had forced row-level security, the exact tenant-context predicate, and grants limited to the runtime application role. However, the policy audience was broader than the only application role intended to use these tables. If another database role later received table privileges, a caller able to set the tenant GUC could also satisfy the PUBLIC policy.

The existing V2–V5 migration files are immutable and must not be edited. The `axiom_app` role is provisioned by database initialization or CI before Flyway runs; V6 grants it the runtime table privileges, and V7 follows those grants.

## Decision

1. Add an additive V7 migration that replaces the existing policy on each of the eight tenant-owned tables.
2. Preserve each policy's name, permissive ALL command, USING expression and WITH CHECK expression exactly.
3. Scope each replacement policy explicitly with `TO axiom_app`; do not create a tenant policy for `PUBLIC`.
4. Extend the PostgreSQL catalog integration invariant to require the policy role array to equal exactly `{axiom_app}`, in addition to the existing single-policy, forced-RLS and predicate checks.
5. Leave table grants, schema ownership, runtime DML privileges, global tables and tenant authorization unchanged.

## Consequences

- Normal application queries retain the same tenant isolation behavior, but the tenant policy no longer applies to unrelated database roles that might later receive table privileges.
- The dedicated migrator remains the trusted schema owner and can administer migrations; this policy is not intended to constrain a privileged migration owner from changing schema or policy definitions.
- Any future data migration that needs tenant-owned rows must explicitly handle forced RLS as a migration-owner operation without broadening the runtime policy.
- V2–V5 remain byte-for-byte immutable. No production deployment platform or infrastructure is assumed.
