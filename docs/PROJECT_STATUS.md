# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Infrastructure, Organization, Party and IAM data foundations merged. OIDC resource-server authentication is the current implementation increment.

## Completed foundations

### Sprint I-1 — Backend and infrastructure
- Maven parent and five modules: common, core, business, api, bootstrap.
- Spring Boot application entry point, system information endpoint and Actuator health endpoint.
- PostgreSQL 17 local Compose service, Flyway bootstrap migration and PostgreSQL-backed GitHub Actions verification.
- PR #2 merged; main CI run #30 passed.

### Sprint O-1 — Organization and tenant
- Tenant is the customer/account isolation boundary; Organization is a business hierarchy node inside a tenant.
- Shared PostgreSQL database/schema; composite foreign keys prevent cross-tenant parent references.
- Forced PostgreSQL RLS; service transactions set transaction-local `axiom.tenant_id`.
- Tenant/organization APIs remain local-profile only until trusted authentication and authorization exist.
- PR #3 merged as `82c77e46575f321d4cf977404e022b207d5b4a95`; main CI run #49 passed.

### Sprint P-1 — Party
- Party represents a person or organization; its business roles are independent, allowing one party to be both CUSTOMER and SUPPLIER.
- Initial roles: CUSTOMER, SUPPLIER, EMPLOYEE, OWNER, CONTACT, PARTNER.
- Party and role rows are tenant-owned and protected by forced RLS and tenant-consistent constraints.
- PR #4 merged as `e629e79fd5bde8f5ff0c401971854e1f2e0be78a`; main CI run #60 passed.

### Sprint IAM-1 — Identity and access data foundation
- Global identity keyed by OIDC `issuer` + `subject`; credentials remain with the external provider.
- One global identity can have independent tenant memberships.
- Roles are tenant-defined; the permission catalogue is platform-owned; grants are tenant-specific.
- Forced RLS and tenant-consistent composite foreign keys protect memberships, roles and role assignments.
- Flyway V4 creates user_identity, tenant_membership, tenant_role, permission, role_permission and membership_role.
- IdentityService resolves external identities, manages memberships/roles and evaluates permissions.
- PR #5 merged as `fc3b37544e7580076f7822d23e5a59841906e9b5`.
- Main CI run #97 passed on that exact merge commit: https://github.com/shivugitec-source/project-axiom/actions/runs/44469079295.

## Sprint IAM-2 — OIDC resource-server authentication (in progress)

Implementation on branch `feature/oidc-resource-server`:
- Add Spring Security OAuth 2.0 Resource Server support.
- Validate JWT signature through issuer discovery/JWKS, issuer, standard token timestamps and a required API audience.
- Require both `AXIOM_OIDC_ISSUER_URI` and `AXIOM_OIDC_AUDIENCE` to activate bearer-token authentication.
- Keep requests stateless; do not create application sessions.
- Keep health/info/system public. With OIDC configured, all other endpoints require an authenticated bearer token.
- Fail closed for protected endpoints when OIDC is not configured. The explicit `local` profile remains a development-only bypass.
- Add unit tests for audience acceptance/rejection and document configuration and current security boundaries.

Not yet implemented:
- Mapping validated `(iss, sub)` claims to `axiom.user_identity`.
- Resolving the caller's tenant membership and selected tenant context.
- Permission enforcement and transaction-local RLS tenant context derived from that authenticated membership.
- Production tenant-controlled HTTP endpoints.

Validation:
- Awaiting PostgreSQL 17-backed GitHub Actions verification on the exact PR head.
- No production-ready tenant authorization claim is made by this increment.

## Next implementation sequence
1. Complete OIDC token validation and principal extraction.
2. Resolve authenticated external identities to active Axiom identities and tenant memberships.
3. Establish tenant context only from an authorized membership; bind it transaction-locally for RLS.
4. Add permission enforcement before exposing tenant-controlled APIs.

## Development rules
- Keep the modular-monolith architecture.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are local-development only.
- Never treat a caller-supplied tenant ID as proof of authorization.
