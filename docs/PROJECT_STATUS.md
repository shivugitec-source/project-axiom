# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Infrastructure foundation in implementation

## Completed design decisions
- Platform-first enterprise foundation (not a single-industry ERP)
- Modular monolith
- Java 21, Spring Boot, PostgreSQL
- React frontend and Flutter mobile client remain later deliverables

## Current sprint
**Sprint I-1 — Backend and infrastructure foundation**

Initial implementation committed on branch `development/infrastructure-foundation`:
- Maven parent and five modules: common, core, business, api, bootstrap
- Spring Boot application entry point
- System information endpoint: GET /api/v1/system
- Actuator health endpoint configuration
- PostgreSQL 17 local Compose service
- Flyway schema bootstrap migration
- Unit tests
- GitHub Actions backend verification workflow

## Validation status
- Files have been added to the development branch.
- A GitHub Actions run and a local Maven build have not yet been observed. Do not mark the build as passing until CI reports success.

## Next actions
1. Run backend CI and resolve any dependency or compilation failures.
2. Verify PostgreSQL Compose startup and Flyway migration.
3. Verify /actuator/health and /api/v1/system.
4. Complete Sprint I-1 only after build, tests, and runtime checks pass.
5. Begin the Organization capability with explicit tenant isolation design and integration tests.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are for local development only.
