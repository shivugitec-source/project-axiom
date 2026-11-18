# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: PostgreSQL-backed application integration tests added; follow-up CI result pending

## Sprint I-1 — Backend and infrastructure foundation

Implemented:
- Maven parent and five modules: common, core, business, api, bootstrap
- Spring Boot application entry point
- System information endpoint: GET /api/v1/system
- Actuator health endpoint configuration
- PostgreSQL 17 local Compose service
- Flyway schema bootstrap migration
- Unit tests
- GitHub Actions workflow provisions PostgreSQL 17 as a service container and runs Maven clean verify
- Spring Boot integration tests verify database connectivity, Flyway migration history, system endpoint and Actuator health endpoint

Validation evidence:
- GitHub Actions run #5: successful; all six Maven reactor entries passed and both unit tests passed.
- GitHub Actions run #12: successful; PostgreSQL 17 service container initialized and Maven verification completed successfully. At that time no database-backed application integration test existed.
- New integration tests are now committed to this branch. The next CI run must pass before Sprint I-1 is marked complete.
- Local Docker/Podman runtime has not been executed from this environment.

Next:
1. Confirm CI passes with the new Spring Boot integration tests.
2. Fix any integration or configuration failures revealed by CI.
3. Mark Sprint I-1 complete after successful integration validation.
4. Begin Organization capability with explicit tenant isolation, persistence, REST APIs and integration tests.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are for local development only.
