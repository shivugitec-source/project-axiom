-- Dedicated schema owner used only by Flyway migrations.
CREATE ROLE axiom_migrator LOGIN PASSWORD 'axiom_migrator_dev_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;

-- Runtime application identity: no database/schema DDL or administrative privileges.
CREATE ROLE axiom_app LOGIN PASSWORD 'axiom_dev_only'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;

GRANT CONNECT ON DATABASE axiom TO axiom_migrator, axiom_app;
-- The schema is pre-created and owned by the migration identity. The migrator
-- baselines the empty schema at V1, so no database-wide CREATE is required.
CREATE SCHEMA axiom AUTHORIZATION axiom_migrator;
GRANT USAGE ON SCHEMA axiom TO axiom_app;

-- New migration-created tables are readable by the application by default.
-- Write privileges must be granted explicitly by a migration when a table needs them.
ALTER DEFAULT PRIVILEGES FOR ROLE axiom_migrator IN SCHEMA axiom
    GRANT SELECT ON TABLES TO axiom_app;
