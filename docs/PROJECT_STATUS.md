# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Infrastructure foundation validated and merged; Organization foundation under implementation.

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
- PR #2 was merged to main as commit `e9901fc618dd6820c844b21480f257bbd9fa78fc`.
- Local Docker/Podman runtime has not been executed from this environment; validation evidence is from GitHub Actions.

## Sprint O-1 — Organization and tenant foundation (in progress)

Design decisions:
- A tenant is the customer/account isolation boundary; an organization is a business unit inside that tenant. Branches, departments and legal entities are not independent tenants by default.
- Initial persistence model is shared PostgreSQL database and shared `axiom` schema.
- Every tenant-owned organization row carries `tenant_id`; a composite foreign key prevents a child organization from referencing a parent in another tenant.
- PostgreSQL row-level security (RLS) is enabled and forced for organization rows. Each transactional service operation sets a transaction-local `axiom.tenant_id`; queries without a tenant context see no organization rows.
- Tenant provisioning is platform-level. Authentication and authorization are not implemented yet, so tenant/organization HTTP endpoints are restricted to the `local` Spring profile and must not be enabled in a production deployment.

Implemented on branch `feature/organization-foundation`:
- Tenant and Organization domain records in `axiom-core`.
- Tenant and organization schema migration V2, hierarchy constraints, indexes and RLS policy.
- JDBC-backed organization service in `axiom-business`.
- Local-profile REST endpoints for tenant provisioning and organization create/list.
- PostgreSQL integration coverage for migration V2, tenant row isolation, cross-tenant parent rejection, non-superuser role verification, and RLS enabled/forced verification.
- CI and local Compose now separate the PostgreSQL bootstrap administrator from the restricted application role. The application role has no superuser, database-creation, or role-creation privileges.

Validation:
- GitHub Actions run #46: successful on commit `8bb28d77bb4976463f47ba2da204f231a1d8b5bd`. PostgreSQL 17.12 became healthy; the restricted `axiom_app` role was provisioned; Flyway applied V1 and V2; all bootstrap integration tests and common unit tests passed; Maven reported BUILD SUCCESS.
- The integration suite verified that the application role is not a superuser, RLS is enabled and forced, tenant B cannot read tenant A's organization rows, and a tenant B organization cannot reference a tenant A parent.
- An expected PostgreSQL foreign-key rejection is emitted in the database log during the negative cross-tenant test; the test passes because the invalid relationship is rejected.
- Do not merge until the status-only update has a successful CI run.
- Production API exposure remains blocked on the identity/authentication capability; the local-only profile restriction is intentional.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are for local development only.
- Treat tenant isolation as a data-access and authorization boundary, not merely a field on business records.
