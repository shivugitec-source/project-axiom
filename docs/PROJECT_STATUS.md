# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Infrastructure, Organization, Party, IAM data, OIDC token authentication, OIDC configuration hardening and provider-neutral identity mapping are merged. Active tenant-membership verification is the current increment; request-scoped RLS context and permission enforcement remain incomplete.

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
- PR #4 merged as `e629e79fd5bde8f5ff0c401971854e1f2e0be78a`.
- CI run #58 passed on exact PR head `9d929d7f980ed2c3b6f695f7d36176a30f96994a`; main CI run #60 passed on the merge commit.

### Sprint IAM-1 — Identity and access data foundation
- Global identity keyed by OIDC `issuer` + `subject`; credentials remain with the external provider.
- One global identity can have independent tenant memberships.
- Roles are tenant-defined; the permission catalogue is platform-owned; grants are tenant-specific.
- Forced RLS and tenant-consistent composite foreign keys protect memberships, roles and role assignments.
- Flyway V4 creates user_identity, tenant_membership, tenant_role, permission, role_permission and membership_role.
- IdentityService resolves external identities, manages memberships/roles and evaluates permissions.
- PR #5 merged as `fc3b37544e7580076f7822d23e5a59841906e9b5`; main CI run #97 passed on that exact merge commit.

### Sprint IAM-2 — OIDC resource-server authentication
- PR #6 merged as `0c7e9841869905a3349f399d395d3488e4874fcc`.
- PR CI run #105 passed on exact PR head `bc1fe8c7118db17abeb981cfb65219a23d6eed56`.
- Main CI run #106 passed on exact merge commit `0c7e9841869905a3349f399d395d3488e4874fcc`.
- PostgreSQL 17.12 and the restricted non-superuser application role were used in CI; Maven reported BUILD SUCCESS. The bootstrap integration, audience-validator and system-information test classes all passed.
- Spring Security validates issuer-discovered JWT signatures, issuer, standard time claims and the required API audience.
- Requests are stateless. Health, info and system-information routes are public; other non-local routes fail closed without OIDC and require a valid bearer token when OIDC is configured.
- The local profile remains a development-only bypass. Tenant-controlled controllers remain local-profile only.
- This is token authentication only, not tenant authorization.

### OIDC configuration hardening
- PR #7 merged as `4d0c3ebcb9624f4a76b81cbac21ddaa075e576fd`.
- PR CI run #112 passed on exact PR head `4aff8cf30b635e50988a4b7c6c2888e0dd14f3c8`.
- Main CI run #113 passed on exact merge commit `4d0c3ebcb9624f4a76b81cbac21ddaa075e576fd`.
- CI used PostgreSQL 17.12 and the restricted non-superuser application role; four OIDC configuration tests passed and Maven reported BUILD SUCCESS.
- The exact documented `AXIOM_OIDC_ISSUER_URI` and `AXIOM_OIDC_AUDIENCE` variables are now resolved explicitly, with fallback to the Spring properties `axiom.security.oidc.issuer-uri` and `axiom.security.oidc.audience`. Partial configuration does not activate OIDC.

### Sprint IAM-3 — OIDC principal to Axiom identity mapping
- PR #8 merged as `fcb53057af5e85d4565243990423b1ca7e525275`.
- PR CI run #126 passed on exact PR head `b5fe78926969e28db385f5e0571bb50bd318591d`.
- Main CI run #127 passed on exact merge commit `fcb53057af5e85d4565243990423b1ca7e525275`.
- CI used PostgreSQL 17.12 and the restricted non-superuser application role. Maven reported BUILD SUCCESS; 18 tests passed with zero failures or errors.
- The resource-server authentication converter maps validated `(issuer, subject)` claims to the idempotent global Axiom identity.
- The principal exposes only identity UUID, issuer and subject. Missing identity claims and disabled Axiom identities are rejected.
- No IdP group/role claims are translated into tenant authorities. A global identity record grants no tenant membership.
- Tenant-controlled controllers remain local-profile only; no tenant context or production tenant authorization is claimed.

## Sprint IAM-4 — active tenant membership verification (in progress)
- Add `IdentityService.hasActiveMembership(tenantId, identityId)`.
- The query requires an ACTIVE membership and ACTIVE global identity, scopes the query to the requested tenant, and establishes transaction-local tenant context before reading forced-RLS membership rows.
- PostgreSQL integration coverage checks active membership, absence of cross-tenant membership, suspended membership and disabled identity.
- This is a reusable authorization primitive only. No HTTP tenant context filter, tenant permission enforcement or production tenant endpoint is enabled in this slice.
- Tenant-controlled controllers remain local-profile only.
- PostgreSQL-backed CI is pending on the exact PR head.

## Not yet implemented
- Bind a verified membership to request-scoped transaction-local `axiom.tenant_id`.
- Enforce tenant-scoped permissions before exposing tenant-controlled APIs in a non-local profile.

## Next implementation sequence
1. Bind the verified membership to request-scoped transaction-local PostgreSQL RLS context and preserve the restricted database role.
2. Enforce tenant permissions before enabling production tenant APIs; a path/header tenant identifier alone is never authority.

## Development rules
- Keep the modular-monolith architecture.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are local-development only.
- Never treat a caller-supplied tenant ID as proof of authorization.
