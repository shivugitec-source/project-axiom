# ADR 0001 — Isolate Flyway from the API process

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

The API previously used Spring Boot startup auto-configuration to execute Flyway. That made the API process load both the runtime database identity and the migration-owner credentials. Database-role separation alone did not provide process isolation.

The immutable V1 migration only creates the `axiom` schema. Running that statement as a migration owner requires database-wide CREATE even when the schema already exists. Changing V1 in place would invalidate Flyway checksums for databases that have already applied it.

The repository currently has no production container image or orchestrator-specific deployment manifest, so the deployment platform and job syntax are not yet selected.

## Decision

1. Move all Flyway dependencies and migration resources into a separate `axiom-migrator` executable Maven module. It has no internal module dependencies.
2. Remove Flyway dependencies and configuration from `axiom-bootstrap`. The API connects only as `axiom_app`; it does not read migration credentials or run migrations at startup.
3. Run the migrator as a one-shot process before starting API instances. Provide `AXIOM_MIGRATION_DB_*` variables only to that process. Backend CI follows the same separation using step-scoped environments.
4. Require the database administrator or initialization script to pre-create schema `axiom`, owned by the migration role. The migration role needs schema CREATE but not database-wide CREATE.
5. Preserve the immutable V1 SQL statement and Flyway checksum compatibility; do not edit the already-applied migration. When the migrator finds an empty pre-created schema without Flyway history, it verifies that there are no existing relations and explicitly baselines at version 1 before applying V2 onward. If relations exist without history, it fails closed. Databases with existing Flyway history continue from that history.
6. Keep Flyway schema creation and clean disabled in the migration executable.

## Consequences

- New databases no longer grant database-wide CREATE to `axiom_migrator`.
- Existing databases with V1 already recorded do not need a checksum change. Their migration role must have database CREATE revoked by an administrator before using the new migrator.
- Deployment owners must run the migrator as a pre-start job and must not inject migration credentials into API workloads.
- The repository provides a runnable migration artifact and CI proof, but production image publication, job orchestration, ordering, retry policy and rollout coordination remain deployment-platform decisions. No infrastructure is deployed by this ADR.
