# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Infrastructure and Organization foundations merged; Party foundation implemented and awaiting CI validation.

## Sprint I-1 — Backend and infrastructure foundation

Implemented:
- Maven parent and five modules: common, core, business, api, bootstrap
- Spring Boot application entry point
- System information endpoint: GET /api/v1/system
- Actuator health endpoint
- PostgreSQL 17 local Compose service and Flyway bootstrap migration
- GitHub Actions PostgreSQL-backed Maven verification

Validation:
- GitHub Actions run #27 passed on PR #2; Flyway V1 and the system/health HTTP endpoints were verified against PostgreSQL.
- PR #2 merged to main as commit `e9901fc618dd6820c844b21480f257bbd9fa78fc`.
- Main-branch CI run #30 passed: https://github.com/shivugitec-source/project-axiom/actions/runs/44283300456.
- Local Docker/Podman runtime has not been executed from this environment; validation evidence is from GitHub Actions.

## Sprint O-1 — Organization and tenant foundation

Design:
- Tenant is the customer/account isolation boundary; Organization is a business hierarchy node inside a tenant.
- Shared PostgreSQL database and shared `axiom` schema.
- Tenant-owned organization rows carry `tenant_id`; composite foreign key prevents cross-tenant parent references.
- PostgreSQL RLS is enabled and forced; service transactions set transaction-local `axiom.tenant_id`.
- Tenant/organization HTTP endpoints are restricted to the `local` Spring profile until identity and authorization are implemented.

Implemented:
- Tenant and Organization domain records, persistence migration V2, JDBC service, local-profile API.
- RLS, hierarchy constraints, tenant-specific unique codes and indexes.
- CI/local database setup separates administrator and restricted application role.
- Integration tests verify non-superuser role, forced RLS, row isolation and cross-tenant parent rejection.

Validation:
- GitHub Actions run #46 passed the Organization integration suite against PostgreSQL 17 with a restricted application role.
- PR #3 merged to main as commit `82c77e46575f321d4cf977404e022b207d5b4a95`.
- Main-branch CI run #49 passed: https://github.com/shivugitec-source/project-axiom/actions/runs/44284535909.

## Sprint P-1 — Party foundation (in progress)

Domain decisions:
- Party represents an external or internal participant in business relationships: a person or an organization.
- Party identity is distinct from its business roles. A party may be both CUSTOMER and SUPPLIER without duplicate identity records.
- Initial roles: CUSTOMER, SUPPLIER, EMPLOYEE, OWNER, CONTACT, PARTNER.
- Party records and role assignments are tenant-owned and protected by forced PostgreSQL RLS.
- This first slice intentionally stores only identity classification, display name, status and roles. Contact points, addresses, tax identifiers, legal registrations, employee employment records and relationship graphs are separate future capabilities; do not prematurely add them here.
- Tenant is supplied to service/API methods for now because authentication is not yet implemented. API routes remain local-profile only. Never treat caller-supplied tenant IDs as production authorization.

Implemented on branch `feature/party-foundation`:
- Party, PartyType and PartyRole domain types in `axiom-core`.
- Flyway V3 creates `axiom.party` and `axiom.party_role`, composite tenant foreign key, constraints, indexes and forced RLS policies.
- JDBC PartyService supports create, get and list; role assignments are normalized and a party can have multiple roles.
- Local-profile REST endpoints: POST/GET `/api/v1/tenants/{tenantId}/parties` and GET `/api/v1/tenants/{tenantId}/parties/{partyId}`.
- Integration tests verify migration V3, role assignment, RLS enabled/forced and cross-tenant invisibility.

Validation:
- CI pending. Do not merge until the PostgreSQL-backed tests pass.

## Development rules
- Keep the modular-monolith architecture.
- Do not expand scope with new architecture documents.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are local-development only.
- Treat tenant isolation as a data-access and authorization boundary, not merely a field on business records.
