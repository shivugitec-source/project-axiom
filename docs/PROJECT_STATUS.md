# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Backend build and PostgreSQL CI service validation passed; application/database integration test remains pending

## Sprint I-1 — Backend and infrastructure foundation

Implemented:
- Maven parent and five modules: common, core, business, api, bootstrap
- Spring Boot application entry point
- System information endpoint: GET /api/v1/system
- Actuator health endpoint configuration
- PostgreSQL 17 local Compose service
- Flyway schema bootstrap migration
- Two unit tests
- GitHub Actions workflow provisions PostgreSQL 17 as a service container and runs Maven clean verify

Validation evidence:
- GitHub Actions run #5: successful; all six Maven reactor entries passed and both unit tests passed.
- GitHub Actions run #12: successful; PostgreSQL 17 service container initialized, Maven verification completed successfully, and service cleanup passed.
- Run #12 confirms build/test execution with PostgreSQL provisioned, but no test currently asserts Flyway migration state or HTTP endpoint responses against the running application.
- Local Docker/Podman runtime was not executed from this environment.

Next:
1. Add a database-backed Spring Boot integration test for Flyway and system/health HTTP endpoints.
2. Run the full CI suite and verify that integration test passes.
3. Run local Compose + application smoke test where Docker/Podman is available.
4. Mark Sprint I-1 complete only after integration/runtime validation.
5. Begin Organization capability with explicit tenant isolation, persistence, REST APIs and integration tests.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are for local development only.
