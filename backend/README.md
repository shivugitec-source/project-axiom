# Project Axiom Backend

## Requirements
- JDK 21
- Maven 3.9+
- Docker Engine or Podman with Compose support
- Python 3.10+ (standard library only) for architecture verification

## Build and test
Run from the repository root after starting the local PostgreSQL service (see below). The sequence builds the artifacts, runs migrations as a separate process, then runs the integration suite with runtime credentials only:

```bash
python3 backend/verify-module-dependencies.py
python3 -m unittest discover -s backend/tests -p 'test_*.py' -v
mvn -B -DskipTests clean package -f backend/axiom-parent/pom.xml
python3 backend/verify-migration-artifacts.py \
  backend/axiom-parent/axiom-bootstrap/target/axiom-bootstrap-0.1.0-SNAPSHOT.jar \
  backend/axiom-parent/axiom-migrator/target/axiom-migrator-0.1.0-SNAPSHOT.jar
AXIOM_MIGRATION_DB_URL=jdbc:postgresql://localhost:5432/axiom \
AXIOM_MIGRATION_DB_USERNAME=axiom_migrator \
AXIOM_MIGRATION_DB_PASSWORD=axiom_migrator_dev_only \
java -jar backend/axiom-parent/axiom-migrator/target/axiom-migrator-0.1.0-SNAPSHOT.jar
AXIOM_DB_URL=jdbc:postgresql://localhost:5432/axiom \
AXIOM_DB_USERNAME=axiom_app \
AXIOM_DB_PASSWORD=axiom_dev_only \
mvn -B verify -f backend/axiom-parent/pom.xml
```

The architecture check validates the API's inward-only Maven module graph and keeps the standalone migrator isolated with no internal module dependencies. It also rejects any backend `src` tree outside the six declared Maven module directories, preventing orphan Java tests or migration resources from silently remaining outside the Maven reactor. Its regression tests use temporary POM fixtures to verify that reverse dependencies, inherited/profile dependencies, duplicate edges, missing modules and malformed POMs are rejected. Backend CI also compares each push or pull-request revision with its base and rejects edits, deletions or renames of Flyway migration resources already present in that base; schema changes must be additive through a new versioned migration. The standard-library regression suite exercises allowed additions and rejected historical edits, deletions and renames. Additional tests ensure Flyway dependencies and migration credentials stay outside the API artifact and process. After Maven packages the executable JARs, `backend/verify-migration-artifacts.py` inspects their actual contents: the API must contain no Flyway libraries, migration SQL or migration-only environment references, while the standalone migrator must contain Flyway, versioned migration resources and its expected entry point without depending on API modules.

The integration tests require a reachable PostgreSQL 17 database. GitHub Actions provisions separate restricted migration-owner and runtime roles, builds the standalone `axiom-migrator` artifact, and runs it as a one-shot process with migration credentials. The subsequent API build and integration tests receive only runtime credentials. The tests verify schema/table ownership, denied runtime DDL, explicit runtime DML, the pre-migrated schema, application startup, HTTP health endpoints, and tenant isolation with PostgreSQL row-level security enabled and forced. The RLS catalog invariant requires each tenant-owned table to have exactly one permissive ALL policy targeted only to `axiom_app` (never `PUBLIC`), with USING and WITH CHECK expressions equal to the approved comparison between `tenant_id` and `current_setting('axiom.tenant_id', true)`; merely mentioning the tenant setting is not sufficient. PostgreSQL catalog integration tests also require `tenant_id` to be NOT NULL on every tenant-owned table and require foreign keys targeting tenant-owned tables to map the referencing `tenant_id` to the referenced `tenant_id` at the same ordinal position in the composite key. A deliberately misordered composite-FK fixture verifies that the catalog check rejects a tenant column paired with a different target column. A same-connection PostgreSQL integration test also verifies that the transaction-local tenant setting is cleared after both commit and rollback, so a pooled JDBC connection cannot retain the previous request's tenant scope.

The runtime database role is also isolated in PostgreSQL's role-membership graph. `axiom_app` must not be a member of another role, and no other role may be a direct or transitive member of `axiom_app`. A policy declared `TO axiom_app` can also apply to roles that are members of `axiom_app`; `NOINHERIT` alone is not a substitute for eliminating role-switching paths. The PostgreSQL integration suite checks both directions. Do not grant role membership to or from the runtime login; any future platform-role design must review the effective RLS audience and `SET ROLE` privileges explicitly.

The migration database role is isolated from role membership as well. `axiom_migrator` must not be a member of another role, and no other role may be a direct or transitive member of `axiom_migrator`. The existing direct-membership check protects against the migration identity assuming another role; a recursive `pg_auth_members` check rejects roles that could assume the migration identity with `SET ROLE`. `NOINHERIT` alone does not prevent role switching. Do not grant membership to or from the migration identity.

## Start PostgreSQL locally
```bash
docker compose -f deployment/compose/docker-compose.yml up -d postgres
```

The PostgreSQL initialization directory also creates a test-only role-membership graph using `02-role-membership-fixture.sql`. Its three `NOLOGIN` roles have no Axiom database or schema privileges; the graph exists only to prove that the integration query finds both direct and transitive members. Compose applies it automatically when creating a fresh database volume. Existing volumes do not rerun initialization scripts, so apply the fixture once when preparing an existing local test database:

```bash
docker exec -i axiom-postgres psql -U axiom_admin -d axiom -v ON_ERROR_STOP=1 < deployment/compose/init/02-role-membership-fixture.sql
```

The local database listens on localhost:5432. The API connects as the restricted `axiom_app` runtime role (default password: `axiom_dev_only`). The separate `axiom-migrator` executable connects as `axiom_migrator` (default password: `axiom_migrator_dev_only`). The PostgreSQL `axiom_admin` role is used only to initialize the local database. These credentials are for local development only.

The initialization SQL runs only when PostgreSQL creates a new data directory. If you already have a database volume from an earlier single-role setup, do not delete it. Connect as a PostgreSQL administrator and perform a one-time ownership transition before starting the updated application:

```sql
CREATE ROLE axiom_migrator LOGIN PASSWORD 'replace_with_local_migration_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE axiom_app LOGIN PASSWORD 'replace_with_local_runtime_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE axiom TO axiom_migrator, axiom_app;
ALTER SCHEMA axiom OWNER TO axiom_migrator;
REVOKE CREATE ON DATABASE axiom FROM axiom_migrator;
ALTER TABLE axiom.flyway_schema_history OWNER TO axiom_migrator;
ALTER TABLE axiom.tenant OWNER TO axiom_migrator;
ALTER TABLE axiom.organization OWNER TO axiom_migrator;
ALTER TABLE axiom.party OWNER TO axiom_migrator;
ALTER TABLE axiom.party_role OWNER TO axiom_migrator;
ALTER TABLE axiom.user_identity OWNER TO axiom_migrator;
ALTER TABLE axiom.tenant_membership OWNER TO axiom_migrator;
ALTER TABLE axiom.tenant_role OWNER TO axiom_migrator;
ALTER TABLE axiom.permission OWNER TO axiom_migrator;
ALTER TABLE axiom.role_permission OWNER TO axiom_migrator;
ALTER TABLE axiom.membership_role OWNER TO axiom_migrator;
ALTER TABLE axiom.party_contact_point OWNER TO axiom_migrator;

GRANT USAGE ON SCHEMA axiom TO axiom_app;
ALTER DEFAULT PRIVILEGES FOR ROLE axiom_migrator IN SCHEMA axiom
    GRANT SELECT ON TABLES TO axiom_app;

-- Retire the previous single-purpose application login after confirming
-- no other local process still uses it.
ALTER ROLE axiom NOLOGIN;
REVOKE CREATE, CONNECT ON DATABASE axiom FROM axiom;
```

Set `AXIOM_DB_USERNAME` / `AXIOM_DB_PASSWORD` for the API runtime. The migration process uses only `AXIOM_MIGRATION_DB_URL`, `AXIOM_MIGRATION_DB_USERNAME` and `AXIOM_MIGRATION_DB_PASSWORD`; never inject these variables into API instances. The migration identity owns the `axiom` schema and has schema CREATE only, not database-wide CREATE. The database administrator or initialization script must pre-create the empty schema. For a new database, the migrator explicitly baselines that empty schema at version 1 because immutable V1 only created the schema, then applies V2 onward. Existing databases with Flyway history continue from their recorded version. If the history table is absent but the schema contains other objects, the migrator fails closed rather than baselining an unknown state. The runtime identity receives schema USAGE and explicit DML grants from migrations. Future migrations must grant write privileges explicitly for each new runtime table. Flyway schema creation is disabled.

## Run migrations and start the API

Build the one-shot migration executable and run it as a separate process. The command below is for local development; supply the migration credentials only to this process:

```bash
mvn -B -DskipTests package -f backend/axiom-parent/pom.xml
AXIOM_MIGRATION_DB_URL=jdbc:postgresql://localhost:5432/axiom \
AXIOM_MIGRATION_DB_USERNAME=axiom_migrator \
AXIOM_MIGRATION_DB_PASSWORD=axiom_migrator_dev_only \
java -jar backend/axiom-parent/axiom-migrator/target/axiom-migrator-0.1.0-SNAPSHOT.jar
```

Start the API separately with runtime credentials only:

```bash
AXIOM_DB_URL=jdbc:postgresql://localhost:5432/axiom \
AXIOM_DB_USERNAME=axiom_app \
AXIOM_DB_PASSWORD=axiom_dev_only \
mvn -f backend/axiom-parent/pom.xml -pl axiom-bootstrap -am spring-boot:run
```

To enable the tenant/organization development endpoints, use the local profile:

```bash
SPRING_PROFILES_ACTIVE=local mvn -f backend/axiom-parent/pom.xml -pl axiom-bootstrap -am spring-boot:run
```

## Configure OIDC for a non-local environment

Project Axiom accepts OAuth 2.0 bearer access tokens issued by a trusted OpenID Connect provider. Configure both values before enabling protected APIs:

```bash
export AXIOM_OIDC_ISSUER_URI="https://identity.example.com/realms/your-realm"
export AXIOM_OIDC_AUDIENCE="axiom-api"
```

The security configuration reads these exact environment variable names directly. It also accepts the explicit Spring properties `axiom.security.oidc.issuer-uri` and `axiom.security.oidc.audience`.

- The issuer must exactly match the token's `iss` claim and expose OIDC discovery/JWKS metadata.
- The audience must be the API identifier expected in the token's `aud` claim. Tokens without it are rejected.
- Spring Security validates the JWT signature using the issuer's published keys and validates issuer, expiry/not-before timestamps, and audience.
- Both values are required to activate bearer-token authentication. Partial or missing configuration does not enable OIDC; protected non-local routes remain denied.
- The API is stateless; browser sessions are not created.
- Health, info and the system-information endpoint are public. Other endpoints require a valid bearer token once OIDC is configured.
- The explicit `local` profile remains a development-only bypass for the existing local endpoints when OIDC is not configured. Never enable it in production.

**Tenant selection:** for OIDC-authenticated requests, at most one nonblank `X-Tenant-ID` header containing a canonical UUID may select the tenant. The value must use the standard 36-character `8-4-4-4-12` hexadecimal representation (case-insensitive); shortened UUID groups and other lenient spellings are rejected with HTTP 400. This is stricter than Java's `UUID.fromString`, which accepts some noncanonical forms such as shortened groups and leading plus signs. Duplicate header values and explicitly blank values are also rejected rather than silently choosing one value or treating an empty selector as absent. The request filter checks that the authenticated Axiom identity has an ACTIVE membership in that tenant before binding a request-scoped tenant context. Tenant-aware business services reject a path/service tenant ID that differs from the verified context and set `axiom.tenant_id` transaction-locally before accessing RLS-protected rows. The context is cleared at the end of the request. The header is a selector, never proof of authorization.

**Tenant permissions:** tenant-aware controller methods use Spring method security and require the permission granted by the selected tenant's active membership roles. Organization operations require `organization:read` or `organization:manage`; Party operations require `party:read` or `party:manage`. The check requires the same verified `X-Tenant-ID` request context and reads grants through `IdentityService` under transaction-local PostgreSQL RLS. Identity-provider role/group claims are not trusted. The tenant-creation endpoint remains development-only until platform provisioning authorization exists.

**Exposure boundary:** Organization and Party controllers remain restricted to the `local` profile. The local development bypass is active only when the `local` profile is selected and OIDC is not configured. CI exercises the complete bearer-token → identity → tenant-membership → permission path with a test-only `JwtDecoder`; production still uses issuer discovery, JWT validation and the configured audience. Do not enable tenant-controlled controllers in a non-local profile until the production issuer configuration and platform provisioning authorization are reviewed.

Check:
- http://localhost:8080/actuator/health
- http://localhost:8080/api/v1/system

`axiom-bootstrap` is the API executable and `axiom-migrator` is a separate one-shot migration executable. The migrator has no internal module dependencies and contains the Flyway migration resources; the API artifact does not include Flyway or migration credentials. Other modules are libraries within the modular monolith. The repository does not yet define a production container image or orchestrator-specific Job manifest; deployment owners must run the migrator artifact as a pre-start job and provide migration credentials only to that job.


## Request-scoped tenant context lifecycle

`TenantContext.open(...)` returns an AutoCloseable scope backed by a thread-local value. The scope must be closed by the same thread that opened it. Closing it from another thread now fails fast and leaves the scope open so its owner can still remove the thread-local value. This prevents a cross-thread close from marking the scope closed after removing only the other thread's ThreadLocal, which would leave the original request thread's tenant context bound. Tenant context is not implicitly propagated to asynchronous work; asynchronous execution must establish and close its own verified context on the executing thread.

## GitHub Actions supply-chain policy

Every external action referenced from `.github/workflows` must be pinned to its full 40-character upstream commit SHA, not a mutable release tag or branch. Container actions must use an immutable `sha256` image digest. Local actions and reusable workflows are allowed because they are resolved from the checked-out repository revision. Backend CI runs `backend/verify-github-actions-pins.py` and its standard-library regression tests to enforce this rule. When upgrading an action, inspect the intended upstream release and its commit, then update the pinned SHA while retaining the release tag as a comment for readability. This protects the workflow from tag retargeting; it does not replace review of an action's own dependencies or source.

The verifier recognizes both block-style and flow-style YAML `uses` keys. Escaped double-quoted mapping keys fail closed because YAML decodes them before GitHub interprets workflow keys; this prevents alternate scalar spellings from disguising `uses`. Local action and reusable-workflow references must remain inside the checked-out repository; parent-directory traversal is rejected. Unsupported or ambiguous references fail closed. Explicit (`? key`) and aliased mapping-key forms fail closed. YAML merge keys are rejected throughout workflow documents because a merged step can inherit an action and override its inputs. Mapping keys carrying YAML anchor or tag properties also fail closed, including when both properties are combined in either valid order. This includes YAML's bare non-specific `!` tag as well as named tags; the verifier intentionally accepts only plain or ordinary quoted `uses` keys rather than attempting to resolve YAML indirection without a full YAML parser.


## Immutable GitHub Actions service images

Backend CI pins its PostgreSQL service to the multi-platform OCI index for the Docker Official Image postgres:17.12-alpine:

    image: postgres:17.12-alpine@sha256:f9cf3e1f0066f0eb0c48707d864f0ac5941e9ff454a072077d97a4d278d07d73

The digest is published in the Docker Library's official image metadata: https://github.com/docker-library/repo-info/blob/master/repos/postgres/remote/17.12-alpine.md. The workflow verifier rejects service/container image references without a full SHA-256 digest, including mutable tags and abbreviated digests. Updating the PostgreSQL patch image requires reviewing the upstream image metadata and changing the version tag and digest together.


### Checkout credential isolation

Backend CI sets `persist-credentials: false` on `actions/checkout`. Checkout needs the read-only workflow token to obtain the source, but subsequent repository-controlled verification, build and test steps do not. The default checkout behavior stores that token in the local Git configuration for later Git commands; disabling persistence prevents those later processes from reading it. The workflow still fetches full history, and its public-repository fallback fetch does not require a persisted credential. The workflow verifier rejects pinned checkout actions that omit this setting, enable it, or declare it ambiguously. The `with` mapping must contain direct scalar inputs: nested mappings/sequences and aliased or tagged values fail closed, and key-like text inside quoted scalar values is never counted as an input key.
