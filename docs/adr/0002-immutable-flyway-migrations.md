# ADR 0002 — Treat committed Flyway migrations as immutable

- **Status:** Accepted
- **Date:** 2026-11-22

## Context

Flyway records checksums for applied versioned migrations. Editing or deleting a migration that has already reached the shared default branch can make existing database histories fail validation or behave differently from clean installations. The repository already preserves the immutable V1 baseline migration, but that rule should apply consistently to every committed versioned migration.

## Decision

1. Once a migration resource is committed to the default branch, its contents and path are immutable.
2. Schema corrections and subsequent changes must be expressed as a new, higher-versioned migration. Do not edit or delete an applied migration to repair it.
3. Backend CI compares the migration-resource directory between the base and head commits. It allows additive resources and rejects modifications, deletions and renames of resources already present in the base commit.
4. The check fails closed when Git cannot compare the supplied full commit revisions or returns an unrecognized change status.
5. The existing packaged-artifact verifier remains responsible for checking versioned SQL resource names and duplicate migration versions in the built migrator JAR.

## Consequences

- Migration history remains reproducible for both clean installations and databases that have already applied earlier versions.
- Intentional schema corrections require a new migration rather than rewriting historical SQL.
- The guard is repository/CI enforcement; it does not replace Flyway checksum validation or PostgreSQL integration tests.
