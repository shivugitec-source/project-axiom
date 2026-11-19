# Project Axiom Backend

## Requirements
- JDK 21
- Maven 3.9+
- Docker Engine or Podman with Compose support

## Build and test
Run from the repository root:

```bash
mvn -B clean verify -f backend/axiom-parent/pom.xml
```

The integration tests require a reachable PostgreSQL 17 database. GitHub Actions provisions PostgreSQL 17 and creates a restricted, non-superuser application role before running Maven. The tests verify Flyway migrations, application startup, HTTP health endpoints, and tenant isolation with PostgreSQL row-level security enabled and forced.

## Start PostgreSQL locally
```bash
docker compose -f deployment/compose/docker-compose.yml up -d postgres
```

The local database listens on localhost:5432. The application connects as the restricted `axiom` role (default password: `axiom_dev_only`). The separate `axiom_admin` role is used only to initialize the local database. These credentials are for local development only.

The initialization SQL runs only when PostgreSQL creates a new data directory. If you already have a database volume from an earlier version, do not delete it if it contains data. Create the restricted role manually as a PostgreSQL administrator:

```sql
CREATE ROLE axiom LOGIN PASSWORD 'axiom_dev_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
GRANT CONNECT, CREATE ON DATABASE axiom TO axiom;
```

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

**Important:** this increment establishes token authentication only. It does not yet map the validated `(iss, sub)` pair to `axiom.user_identity`, select a tenant membership, authorize tenant permissions, or derive a tenant context for database RLS. A caller-supplied tenant ID is still not proof of authorization, and tenant-controlled APIs remain local-profile only until those steps are implemented.

Check:
- http://localhost:8080/actuator/health
- http://localhost:8080/api/v1/system

Only `axiom-bootstrap` is executable. Other modules are libraries within the modular monolith.
