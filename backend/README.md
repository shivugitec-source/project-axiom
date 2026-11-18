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

**Security:** the tenant/organization endpoints are local-profile only until authentication and authorization are implemented. Do not enable the `local` profile in production. Production tenant identity must be derived from an authenticated principal, not a caller-controlled tenant identifier.

Check:
- http://localhost:8080/actuator/health
- http://localhost:8080/api/v1/system

Only `axiom-bootstrap` is executable. Other modules are libraries within the modular monolith.
