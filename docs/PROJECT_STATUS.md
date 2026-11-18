# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Backend CI verified; PostgreSQL runtime validation pending

## Sprint I-1 — Backend and infrastructure foundation

Implemented:
- Maven parent and five modules: common, core, business, api, bootstrap
- Spring Boot application entry point
- System information endpoint: GET /api/v1/system
- Actuator health endpoint
- PostgreSQL 17 local Compose service
- Flyway schema bootstrap migration
- Two unit tests
- GitHub Actions backend verification workflow

Validation:
- GitHub Actions run #5 completed successfully.
- Maven reactor: all six entries successful.
- Unit tests: 2 passed, 0 failures, 0 errors.
- The CI job currently proves compilation, packaging and unit tests. It does not prove Flyway migration or live HTTP health.

Next:
1. Add PostgreSQL-backed Spring Boot integration test.
2. Run local Compose and application smoke tests when Docker/Podman is available.
3. Mark Sprint I-1 complete only after integration and runtime checks pass.
4. Begin Organization capability with tenant isolation, persistence, REST APIs and integration tests.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are for local development only.
