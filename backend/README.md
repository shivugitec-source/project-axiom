# Project Axiom Backend

## Requirements
- JDK 21
- Maven 3.9+
- Docker Engine or Podman with Compose support
- Python 3.10+ (standard library only) for architecture verification

## Build and test
Run from the repository root:

```bash
python3 backend/verify-module-dependencies.py
python3 -m unittest discover -s backend/tests -p 'test_*.py' -v
mvn -B clean verify -f backend/axiom-parent/pom.xml
```

The architecture check validates the approved inward-only Maven module graph. Its regression tests use temporary POM fixtures to verify that reverse dependencies, inherited/profile dependencies, duplicate edges, missing modules and malformed POMs are rejected.

The integration tests require a reachable PostgreSQL 17 database. GitHub Actions provisions separate restricted migration-owner and runtime roles before running Maven. Flyway uses the migration credentials; the application JDBC pool uses the runtime identity. The tests verify schema/table ownership, denied runtime DDL, explicit runtime DML, Flyway migrations, application startup, HTTP health endpoints, and tenant isolation with PostgreSQL row-level security enabled and forced.

## Start PostgreSQL locally
```bash
docker compose -f deployment/compose/docker-compose.yml up -d postgres
```

The local database listens on localhost:5432. The application connects as the restricted `axiom_app` runtime role (default password: `axiom_dev_only`). Flyway connects as the separate `axiom_migrator` schema-owner role (default password: `axiom_migrator_dev_only`). The PostgreSQL `axiom_admin` role is used only to initialize the local database. These credentials are for local development only.

The initialization SQL runs only when PostgreSQL creates a new data directory. If you already have a database volume from an earlier single-role setup, do not delete it. Connect as a PostgreSQL administrator and perform a one-time ownership transition before starting the updated application:

```sql
CREATE ROLE axiom_migrator LOGIN PASSWORD 'replace_with_local_migration_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
CREATE ROLE axiom_app LOGIN PASSWORD 'replace_with_local_runtime_password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT ON DATABASE axiom TO axiom_migrator, axiom_app;
GRANT CREATE ON DATABASE axiom TO axiom_migrator;

ALTER SCHEMA axiom OWNER TO axiom_migrator;
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

Set `AXIOM_DB_USERNAME` / `AXIOM_DB_PASSWORD` for the runtime connection and `AXIOM_FLYWAY_USERNAME` / `AXIOM_FLYWAY_PASSWORD` for Flyway. The migration identity owns schema objects and has database CREATE only because the immutable V1 migration executes `CREATE SCHEMA IF NOT EXISTS`; the runtime identity never receives database or schema CREATE. The runtime identity receives schema USAGE and explicit DML grants from migrations. Future migrations must grant write privileges explicitly for each new runtime table. Flyway schema creation is disabled, so the `axiom` schema must be provisioned by the database administrator or initialization script before startup.

## Run the application
```bash
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

**Tenant selection:** for OIDC-authenticated requests, an optional `X-Tenant-ID` UUID selects the tenant. The request filter checks that the authenticated Axiom identity has an ACTIVE membership in that tenant before binding a request-scoped tenant context. Tenant-aware business services reject a path/service tenant ID that differs from the verified context and set `axiom.tenant_id` transaction-locally before accessing RLS-protected rows. The context is cleared at the end of the request. The header is a selector, never proof of authorization.

**Tenant permissions:** tenant-aware controller methods use Spring method security and require the permission granted by the selected tenant's active membership roles. Organization operations require `organization:read` or `organization:manage`; Party operations require `party:read` or `party:manage`. The check requires the same verified `X-Tenant-ID` request context and reads grants through `IdentityService` under transaction-local PostgreSQL RLS. Identity-provider role/group claims are not trusted. The tenant-creation endpoint remains development-only until platform provisioning authorization exists.

**Exposure boundary:** Organization and Party controllers remain restricted to the `local` profile. The local development bypass is active only when the `local` profile is selected and OIDC is not configured. CI exercises the complete bearer-token → identity → tenant-membership → permission path with a test-only `JwtDecoder`; production still uses issuer discovery, JWT validation and the configured audience. Do not enable tenant-controlled controllers in a non-local profile until the production issuer configuration and platform provisioning authorization are reviewed.

Check:
- http://localhost:8080/actuator/health
- http://localhost:8080/api/v1/system

Only `axiom-bootstrap` is executable. Other modules are libraries within the modular monolith.
