# Project Axiom — Project Status

> Source of truth for the next development session. Update this file at the end of each implementation session.

## Project
- Version: 0.1.0-SNAPSHOT
- Architecture: Modular monolith
- Repository: https://github.com/shivugitec-source/project-axiom
- Status: Infrastructure, Organization, Party, IAM data, OIDC token authentication, provider-neutral identity mapping, active membership verification, membership-verified request tenant context, tenant permission enforcement, end-to-end HTTP/OIDC security tests, Party contact points, modular-monolith dependency enforcement with regression tests, a database-backed invariant for forced tenant-scoped RLS on every tenant-owned table, and a separate Flyway migration-owner/runtime application-role boundary with explicit runtime privileges and PostgreSQL-backed ownership/DDL-denial tests are merged. The migration identity retains database CREATE because immutable V1 executes CREATE SCHEMA IF NOT EXISTS; the runtime identity has no database/schema CREATE, table ownership or Flyway-history access. Flyway still runs during Spring Boot startup, so migration credentials remain available to the application process and must be moved to a separate migration job before production API startup. Tenant controllers remain local-profile only pending platform tenant-provisioning authorization and production OIDC readiness.

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

### Sprint IAM-4 — active tenant membership verification
- PR #10 merged as `3d7b738d12c554b900aacb3ae3d788a6294a197b`.
- PR CI run #134 passed on exact PR head `1846fc261857bd93fd40f4c7dce6fea54a4a6a4f`.
- Main CI run #135 passed on exact merge commit `3d7b738d12c554b900aacb3ae3d788a6294a197b`.
- CI used PostgreSQL 17.12 and the restricted non-superuser application role. Maven reported BUILD SUCCESS; 19 tests passed with zero failures or errors.
- `IdentityService.hasActiveMembership(tenantId, identityId)` requires an ACTIVE membership and ACTIVE global identity, scopes the lookup to the requested tenant, and establishes transaction-local tenant context before reading forced-RLS membership rows.
- Integration coverage verifies active membership, no cross-tenant membership, suspended membership and disabled identity.
- This is a reusable authorization primitive only. No HTTP tenant context filter, tenant permission enforcement or production tenant endpoint is enabled in this slice.
- Tenant-controlled controllers remain local-profile only.

### Sprint IAM-5 — Membership-verified request tenant context
- An optional `X-Tenant-ID` request header is parsed as a UUID and checked against the authenticated Axiom identity's active membership.
- The filter binds a request-scoped tenant context only after membership verification and always clears it after request processing.
- Tenant-aware Organization, Party and IAM services reject tenant arguments that differ from the verified request context and continue to establish transaction-local PostgreSQL `axiom.tenant_id` for forced RLS.
- Tenant context is enabled only in the OIDC security chain; local-profile development behavior is unchanged.
- Added unit tests for context lifecycle, tenant mismatch, malformed selectors, missing membership and request cleanup, plus PostgreSQL integration coverage for service-level tenant mismatch.
- The initial CI attempts exposed incorrect Spring Security/Spring Web filter imports; corrected in commit `37a2104251c1d9b480c691d56e13f0b9d6d0a4f8`.
- Final push CI run #161 and pull-request CI run #162 passed on exact PR head `a7259f502afd4b80fcc1c03967d1286679a02931`.
- PR #12 was squash-merged as `328944158d39aafa90da23515d8efc275463e404` after the exact PR head passed both CI runs and the reviewed diff contained no unresolved security or correctness issue.
- PostgreSQL 17-backed Maven verification used the restricted non-superuser application role; BUILD SUCCESS, 26 tests, zero failures, errors or skips.
- Tenant-controlled controllers remain local-profile only; HTTP permission enforcement is not included.

### Sprint IAM-6 — Tenant permission authorization
- Add a method-security authorization component that requires an authenticated `AxiomPrincipal`, a non-null verified request tenant matching the controller tenant argument, and the requested permission granted by that tenant's active membership roles.
- Enable Spring method security and declare explicit `organization:read`, `organization:manage`, `party:read` and `party:manage` requirements on the tenant-aware controller methods.
- Keep tenant creation platform-level and local-development-only until platform provisioning authorization is designed.
- Preserve the local development bypass only when the `local` profile is active and OIDC is not configured; OIDC-configured requests must pass permission checks even under the local profile.
- Add unit tests for fail-closed behavior and an integration test that exercises method security against PostgreSQL-backed tenant role grants and forced RLS.
- Tenant controllers remain `@Profile("local")`; this slice does not enable production tenant APIs.
- Initial CI runs #168 and #169 exposed the missing direct Spring Security Core dependency in `axiom-api`; added `spring-security-core` in commit `1fe4b589d0898736eff1c60b47f691b1bebcebe7`.
- Push CI run #170 and PR CI run #171 passed on exact PR head `1fe4b589d0898736eff1c60b47f691b1bebcebe7`; PostgreSQL 17-backed Maven verification reported BUILD SUCCESS with 32 tests and zero failures, errors or skips.
- PR #14 was squash-merged as `c72b616247de39fa6c5ea26eb717f33f02e6cf66`; main CI run #172 passed on that exact merge commit with the same 32-test result.

### Sprint IAM-7 — End-to-end OIDC tenant authorization tests
- Make the resource-server decoder injectable while retaining the production Nimbus issuer-discovery decoder and issuer/time/audience validators as the default.
- Add a test-only decoder and HTTP integration coverage for bearer authentication, OIDC identity mapping, active tenant membership, `X-Tenant-ID` context, method-level permission checks and PostgreSQL forced RLS.
- Verify an authorized `party:read` request succeeds and requests with no token, no tenant selector, a mismatched tenant, no active membership or no `party:read` grant are denied.
- Keep Organization and Party controllers local-profile only; no production tenant API exposure is included.
- Initial CI runs #176, #177 and #178 exposed the Spring Boot 4 MVC test package/starter mismatch; fixed with `spring-boot-starter-webmvc-test` and the Boot 4 `AutoConfigureMockMvc` package in commit `dfd27386d7346c4802eab580fcc1c5cc7a76d16d`.
- Push CI run #179 and PR CI run #180 passed on exact PR head `dfd27386d7346c4802eab580fcc1c5cc7a76d16d`; PostgreSQL 17-backed Maven verification reported BUILD SUCCESS with 33 tests and zero failures, errors or skips.
- PR #16 was squash-merged as `104e89e1edce581083f72c483d72309abef39e28`; main CI run #181 passed on that exact merge commit with the same 33-test result.

### Engineering E-1 — Modular-monolith dependency guard
- Added `backend/verify-module-dependencies.py`, using only the Python standard library, to verify the parent Maven module list, each module artifact ID and the allowed inward-only internal dependency graph: common <- core <- business <- api <- bootstrap.
- Backend CI runs this check before Java setup and the PostgreSQL-backed Maven verification, so accidental reverse dependencies or skipped layers fail fast.
- PR #20 was squash-merged as `6cb5fb98ded7a8c4723c531957b06d4e094d3f06` after PR CI #199 passed on exact head `4af1e3e26cf721759a6f9f44a8929c386a046721`.
- Main CI #200 passed on merge commit `6cb5fb98ded7a8c4723c531957b06d4e094d3f06`.
- PR #21 was squash-merged as `7f777c9c23c22d417d6643c19d71d66a56d1a9a9` after PR CI #201 passed on exact head `cdd1d0a981b920da8549e04f9d9151a13cd6beae`; main CI #202 passed on that merge commit.

### Engineering E-2 — Regression-tested module dependency guard
- Refactored the standard-library verifier into a testable repository-validation function. It checks project and profile dependency declarations in the parent and child POMs; all profile declarations are checked conservatively so activation cannot introduce an unreviewed reverse edge. Parent-inherited internal dependencies, duplicate edges within a dependency container, unknown/reverse dependencies, missing module POMs, artifact mismatches and malformed POM XML fail validation.
- Added 12 `unittest` fixture tests covering the approved graph and the failure cases above. Backend CI executes the verifier and its regression suite before Java setup and PostgreSQL-backed Maven verification.
- PR #22 was squash-merged as `b53ae35f403f50be57ac7b8ccc8d64fd1df15569` after PR CI #207 passed on exact head `27987b9eff447061309226023bb16f97c49d8026`; push CI #206 also passed on that exact head.
- Both exact-head runs reported 12 Python tests passing and Maven `BUILD SUCCESS`; main CI #208 passed on merge commit `b53ae35f403f50be57ac7b8ccc8d64fd1df15569`.

### Engineering E-3 — Tenant-table forced-RLS invariant
- Added a PostgreSQL integration invariant that discovers all base tables in schema `axiom` with a `tenant_id` column and keeps the expected tenant-owned table set explicit.
- For every discovered tenant-owned table, the test requires both RLS enabled and FORCE RLS enabled, at least one policy, and rejects policies whose applicable `USING` or `WITH CHECK` expression omits the transaction-local `axiom.tenant_id` context. This protects future tenant-owned tables against accidental omission or an additional unscoped policy.
- PR #24 was squash-merged as `740d12c1581f7b0c8b38bb9bcdb4667cd1e78b89` after PR CI #211 passed on exact head `3195924f56f66b411a08f51c74ac7a8f47b0ee63`.
- Main CI #212 passed on merge commit `740d12c1581f7b0c8b38bb9bcdb4667cd1e78b89`; PostgreSQL 17-backed Maven verification reported 35 tests with zero failures, errors or skips and `BUILD SUCCESS`. The new invariant ran inside `AxiomApplicationIntegrationTest`.

### Engineering E-4 — Restricted application database role invariant
- Added a PostgreSQL integration assertion against the actual JDBC `current_user` used by the application.
- The runtime role must be a login and must not have SUPERUSER, CREATEROLE, CREATEDB, REPLICATION, BYPASSRLS or INHERIT attributes.
- The test also verifies the role retains the database CONNECT and CREATE privileges required by the documented connection and Flyway schema bootstrap.
- PR #26 was squash-merged as `532cd0b20084f4fc638e644d25cb5c844740fac4` after PR CI #215 passed on exact head `a7d7ae4badef311f41e9752e6c065179665b4197`; PostgreSQL 17-backed Maven verification reported 36 tests with zero failures, errors or skips and `BUILD SUCCESS`.
- Main CI #216 passed on merge commit `532cd0b20084f4fc638e644d25cb5c844740fac4` with the same 36-test result.
- PR #28 was squash-merged as `7ff9d3aca61e2b0a0f93731c00defa18640b98d2` after PR CI #219 passed on exact head `57cddf03eded2eb1a7daec72be2633dbee0940fa`; the integration invariant also rejects direct membership in another PostgreSQL role, preventing `SET ROLE` privilege escalation.
- Main CI #220 passed on merge commit `7ff9d3aca61e2b0a0f93731c00defa18640b98d2`; PostgreSQL 17-backed Maven verification reported 36 tests with zero failures, errors or skips and `BUILD SUCCESS`.
- At the time of PR #26, the same datasource identity performed Flyway migrations and runtime queries. PR #31 resolves the database-role ownership boundary; see Engineering E-5 for exact evidence.
- Remaining limitation after PR #31 is process-level: Spring Boot still loads both credential sets for startup Flyway, and the migration identity retains database CREATE because of the immutable V1 schema statement. Do not treat database-role separation as process isolation.

### Engineering E-5 — Separate Flyway migration owner and runtime database role
- The Spring datasource now connects as `axiom_app`; Flyway uses the distinct `axiom_migrator` login. The migration identity owns the `axiom` schema and its relations.
- The runtime role receives database CONNECT, schema USAGE and explicit SELECT/INSERT/UPDATE/DELETE grants on operational tables. The platform permission catalogue is read-only; runtime has no access to `flyway_schema_history`.
- New migration-created tables receive SELECT only by default. Each migration must explicitly grant write privileges to the runtime role for its new table.
- PostgreSQL integration coverage verifies the runtime role has no database/schema CREATE, no role memberships, no schema/table ownership, and cannot execute `ALTER TABLE axiom.party DISABLE ROW LEVEL SECURITY`. It also verifies migration ownership, restricted role attributes, read-only permission catalogue access and runtime DML.
- The migration role is non-superuser and has no CREATEDB, CREATEROLE, REPLICATION, BYPASSRLS, INHERIT or role memberships. It retains the database CREATE ACL because the immutable V1 migration executes `CREATE SCHEMA IF NOT EXISTS`; the runtime role never receives this privilege.
- Flyway history is inspected through the Flyway migration connection, not through the runtime JDBC identity.
- Local Compose initialization and Backend CI provision both identities. The backend README documents a one-time ownership transfer for existing local volumes without deleting data.
- PR #31 was squash-merged as `47ff60d092ad105edf3810a505b2aea07f1229b5` after exact-head PR CI #229 passed on `1b1f8da3d9fa943deb85e85450c9358ad3a56064`.
- Main CI #230 passed on merge commit `47ff60d092ad105edf3810a505b2aea07f1229b5`; PostgreSQL 17.12-backed Maven verification reported 37 tests with zero failures, errors or skips and `BUILD SUCCESS`.
- Remaining security limitation: Flyway still runs inside the Spring Boot application process, so that process receives both credential sets at startup. This is database-role separation, not process isolation. Before production, execute migrations in a separate deployment job and withhold migration credentials from API instances.

## Not yet implemented
- Move Flyway execution into a dedicated migration job before production API startup, so migration credentials are not injected into the application process. Revisit the legacy V1 schema bootstrap so the migration identity can eventually avoid database-wide CREATE while retaining ownership of the Axiom schema.
- Define and document the next business-domain capability (business purpose, actors, lifecycle, invariants, fields, workflows and API acceptance) before adding speculative domain entities. The current README lists target industries but no detailed business requirements or ADRs define the next slice.
- Define and implement platform-level tenant provisioning authorization only after its authority, approval and audit policy is established.
- Review real OIDC issuer/audience configuration and operational readiness before considering production tenant API exposure.

## Development rules
- Keep the modular-monolith architecture.
- Do not create a universal transaction table for unrelated business domains.
- Keep business rules in their owning modules; bootstrap is wiring and runtime configuration.
- Never commit production secrets. Compose defaults are local-development only.
- Never treat a caller-supplied tenant ID as proof of authorization.


### Sprint P-2 — Party contact points (completed)
- Add tenant-owned EMAIL and PHONE contact points linked to a Party; this is a separate capability and does not expand Party identity or role semantics.
- Flyway V5 creates `axiom.party_contact_point` with a tenant-consistent composite foreign key to Party, constrained contact type, nonblank value, lookup index and forced RLS.
- Add PartyContactPoint domain record, tenant-scoped JDBC service and local-profile API to add/list contact points. API operations reuse the existing `party:manage` and `party:read` permissions.
- Contact values are stored as supplied after trimming; this slice does not claim email/phone ownership verification, normalization, uniqueness, or consent management.
- PostgreSQL integration coverage checks forced RLS, tenant isolation, cross-tenant party binding rejection, and EMAIL/PHONE persistence.
- PR #18 was squash-merged as `13b766dcb12e08457ec56496f8dacb52c54f2df7` after PR CI #193 passed on exact head `d8e4bc920f62d249a7130684cb706c8854e12947`.
- Main CI #194 passed on merge commit `13b766dcb12e08457ec56496f8dacb52c54f2df7`.
- PR #19 (documentation correction) was squash-merged as `32783236d093fcc323dad8a88fd3f9dc4e0f616d` after PR CI #196 passed on exact head `71edb2d4b5d8f32dff3f1cbd677af4b0607ed745`.
- Main CI #197 passed on merge commit `32783236d093fcc323dad8a88fd3f9dc4e0f616d`.
- Tenant controllers remain local-profile only. No production endpoint exposure is added.

## Next implementation sequence
1. Move Flyway into a separate deployment/migration job and keep its credentials out of API instances; revisit the V1 bootstrap to reduce the migration identity's database-wide CREATE grant.
2. Select the next business-domain capability only when its lifecycle, invariants and API contract are supported by documented requirements. The current README lists target industries but does not define their business rules; do not invent a domain slice.
3. Define platform-level tenant-provisioning authorization, including authority, approval and audit requirements, before implementing tenant creation.
4. Review real production OIDC issuer/audience configuration and operational readiness before any production tenant API exposure.
