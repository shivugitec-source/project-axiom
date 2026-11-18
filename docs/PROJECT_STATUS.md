# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Backend infrastructure foundation validated against PostgreSQL 17 in GitHub Actions; Organization capability is next.

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
- GitHub Actions run #12: successful; PostgreSQL 17 service container initialized and Maven verification completed. At that time no database-backed application integration test existed.
- GitHub Actions run #27: successful on PR #2 head commit `64fcd75a5250636085aaf35791fd81a21231bf27`. PostgreSQL 17.12 became healthy; Spring Boot connected to it; Flyway successfully applied migration V1; the integration test verified the system and health HTTP endpoints; all 3 tests passed with 0 failures, 0 errors and 0 skipped; Maven reactor reported BUILD SUCCESS.
- The earlier integration-test failure was caused by Spring Boot 4 modular Flyway auto-configuration not being activated by the bare Flyway core dependency. Replacing it with `spring-boot-starter-flyway` and retaining the PostgreSQL Flyway database module fixed the issue.
- Local Docker/Podman runtime has not been executed from this environment; validation evidence is from GitHub Actions.

Sprint I-1 exit criteria:
- [x] PostgreSQL 17 service starts and becomes healthy in CI.
- [x] Application connects to PostgreSQL.
- [x] Flyway V1 migration is applied and recorded.
- [x] GET /api/v1/system responds successfully.
- [x] GET /actuator/health responds with UP.
- [x] Maven clean verify completes with all tests passing.

Next:
1. Merge the validated infrastructure PR.
2. Begin Organization capability as a vertical slice: domain model, persistence migration, application service, REST API, validation and PostgreSQL-backed integration tests.
3. Finalize and enforce the tenant isolation strategy before exposing organization data APIs. Do not assume a tenant model from a `tenant_id` column alone.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are for local development only.
- Treat tenant isolation as a data-access and authorization boundary, not merely a field on business records.
