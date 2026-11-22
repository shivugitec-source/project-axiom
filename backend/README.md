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

The architecture check validates the API's inward-only Maven module graph and keeps the standalone migrator isolated with no internal module dependencies. Its regression tests use temporary POM fixtures to verify that reverse dependencies, inherited/profile dependencies, duplicate edges, missing modules and malformed POMs are rejected. Additional tests ensure Flyway dependencies and migration credentials stay outside the API artifact and process. After Maven packages the executable JARs, `backend/verify-migration-artifacts.py` inspects their actual contents: the API must contain no Flyway libraries, migration SQL or migration-only environment references, while the standalone migrator must contain Flyway, versioned migration resources and its expected entry point without depending on API modules.

The integration tests require a reachable PostgreSQL 17 database. GitHub Actions provisions separate restricted migration-owner and runtime roles, builds the standalone `axiom-migrator` artifact, and runs it as a one-shot process with migration credentials. The subsequent API build and integration tests receive only runtime credentials. The tests verify schema/table ownership, denied runtime DDL, explicit runtime DML, the pre-migrated schema, application startup, HTTP health endpoints, and tenant isolation with PostgreSQL row-level security enabled and forced.

## Start PostgreSQL locally
```bash
docker compose -f deployment/compose/docker-compose.yml up -d postgres
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

**Tenant selection:** for OIDC-authenticated requests, an optional `X-Tenant-ID` UUID selects the tenant. The request filter checks that the authenticated Axiom identity has an ACTIVE membership in that tenant before binding a request-scoped tenant context. Tenant-aware business services reject a path/service tenant ID that differs from the verified context and set `axiom.tenant_id` transaction-locally before accessing RLS-protected rows. The context is cleared at the end of the request. The header is a selector, never proof of authorization.

**Tenant permissions:** tenant-aware controller methods use Spring method security and require the permission granted by the selected tenant's active membership roles. Organization operations require `organization:read` or `organization:manage`; Party operations require `party:read` or `party:manage`. The check requires the same verified `X-Tenant-ID` request context and reads grants through `IdentityService` under transaction-local PostgreSQL RLS. Identity-provider role/group claims are not trusted. The tenant-creation endpoint remains development-only until platform provisioning authorization exists.

**Exposure boundary:** Organization and Party controllers remain restricted to the `local` profile. The local development bypass is active only when the `local` profile is selected and OIDC is not configured. CI exercises the complete bearer-token → identity → tenant-membership → permission path with a test-only `JwtDecoder`; production still uses issuer discovery, JWT validation and the configured audience. Do not enable tenant-controlled controllers in a non-local profile until the production issuer configuration and platform provisioning authorization are reviewed.

Check:
- http://localhost:8080/actuator/health
- http://localhost:8080/api/v1/system

`axiom-bootstrap` is the API executable and `axiom-migrator` is a separate one-shot migration executable. The migrator has no internal module dependencies and contains the Flyway migration resources; the API artifact does not include Flyway or migration credentials. Other modules are libraries within the modular monolith. The repository does not yet define a production container image or orchestrator-specific Job manifest; deployment owners must run the migrator artifact as a pre-start job and provide migration credentials only to that job.
