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

The integration tests require a reachable PostgreSQL database. GitHub Actions provisions PostgreSQL 17 automatically. The tests start the Spring Boot application on a random port, verify the Flyway schema-history record, and call the system and Actuator health endpoints.

## Start PostgreSQL locally
```bash
docker compose -f deployment/compose/docker-compose.yml up -d postgres
```

The local development database is available at localhost:5432. Compose credentials are for local development only.

## Run the application
```bash
mvn -f backend/axiom-parent/pom.xml -pl axiom-bootstrap -am spring-boot:run
```

Check:
- http://localhost:8080/actuator/health
- http://localhost:8080/api/v1/system

Only `axiom-bootstrap` is executable. Other modules are libraries within the modular monolith.
