package io.projectaxiom.migrator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.Flyway;

/**
 * One-shot database migration process. This executable is packaged separately
 * from the API so migration credentials never need to be present in API pods.
 */
public final class AxiomMigratorApplication {
    private static final String SCHEMA = "axiom";

    private AxiomMigratorApplication() {
    }

    public static void main(String[] args) throws Exception {
        migrate(MigrationSettings.from(System.getenv()));
    }

    static void migrate(MigrationSettings settings) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                settings.url(), settings.username(), settings.password())) {
            verifyMigrationIdentity(connection, settings.username());

            if (!schemaHistoryExists(connection)) {
                int existingRelations = countSchemaRelations(connection);
                if (existingRelations != 0) {
                    throw new IllegalStateException(
                            "Schema axiom has objects but no Flyway history; refusing to baseline a non-empty schema");
                }
                flyway(settings).baseline();
                System.out.println("Baselined the pre-created empty axiom schema at version 1; legacy V1 only created the schema.");
            } else {
                verifyHistoryOwnership(connection);
            }
        }

        flyway(settings).migrate();
        System.out.println("Axiom database migrations completed successfully.");
    }

    private static Flyway flyway(MigrationSettings settings) {
        return Flyway.configure()
                .dataSource(settings.url(), settings.username(), settings.password())
                .locations("classpath:db/migration")
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(false)
                .baselineVersion("1")
                .baselineDescription("Axiom schema pre-created by database administrator")
                .cleanDisabled(true)
                .load();
    }

    private static void verifyMigrationIdentity(Connection connection, String configuredUsername)
            throws SQLException {
        String sql = """
                SELECT current_user,
                       has_database_privilege(current_user, current_database(), 'CONNECT'),
                       NOT has_database_privilege(current_user, current_database(), 'CREATE'),
                       has_schema_privilege(current_user, 'axiom', 'CREATE'),
                       n.nspowner = r.oid,
                       r.rolcanlogin
                           AND NOT r.rolsuper
                           AND NOT r.rolcreatedb
                           AND NOT r.rolcreaterole
                           AND NOT r.rolreplication
                           AND NOT r.rolbypassrls
                           AND NOT r.rolinherit,
                       NOT EXISTS (
                           SELECT 1 FROM pg_auth_members m WHERE m.member = r.oid
                       )
                FROM pg_namespace n
                JOIN pg_roles r ON r.rolname = current_user
                WHERE n.nspname = 'axiom'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("Required schema axiom does not exist; provision it before migration");
            }
            String currentUser = result.getString(1);
            if (!configuredUsername.equals(currentUser)
                    || !result.getBoolean(2)
                    || !result.getBoolean(3)
                    || !result.getBoolean(4)
                    || !result.getBoolean(5)
                    || !result.getBoolean(6)
                    || !result.getBoolean(7)) {
                throw new IllegalStateException(
                        "Migration connection must use the schema-owner login with CONNECT and schema CREATE only; "
                                + "database CREATE, administrative attributes and role memberships are forbidden");
            }
        }
    }

    private static boolean schemaHistoryExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT to_regclass('axiom.flyway_schema_history') IS NOT NULL");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static int countSchemaRelations(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    (SELECT COUNT(*) FROM pg_class c WHERE c.relnamespace = n.oid)
                    + (SELECT COUNT(*) FROM pg_proc p WHERE p.pronamespace = n.oid)
                    + (SELECT COUNT(*) FROM pg_type t WHERE t.typnamespace = n.oid AND t.typtype <> 'p')
                FROM pg_namespace n
                WHERE n.nspname = 'axiom'
                """);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void verifyHistoryOwnership(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_get_userbyid(c.relowner) = current_user
                FROM pg_class c
                WHERE c.oid = 'axiom.flyway_schema_history'::regclass
                """);
             ResultSet result = statement.executeQuery()) {
            if (!result.next() || !result.getBoolean(1)) {
                throw new IllegalStateException("Flyway history must be owned by the migration identity");
            }
        }
    }

    record MigrationSettings(String url, String username, String password) {
        static MigrationSettings from(Map<String, String> environment) {
            return new MigrationSettings(
                    required(environment, "AXIOM_MIGRATION_DB_URL"),
                    required(environment, "AXIOM_MIGRATION_DB_USERNAME"),
                    required(environment, "AXIOM_MIGRATION_DB_PASSWORD"));
        }

        private static String required(Map<String, String> environment, String key) {
            String value = environment.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Required environment variable is missing: " + key);
            }
            return value;
        }
    }
}