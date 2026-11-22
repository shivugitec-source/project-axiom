package io.projectaxiom.migrator;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AxiomMigratorApplicationTest {

    @Test
    void migrationSettingsRequireDedicatedConnectionValues() {
        IllegalArgumentException missingUrl = assertThrows(IllegalArgumentException.class,
                () -> AxiomMigratorApplication.MigrationSettings.from(Map.of()));
        assertTrue(missingUrl.getMessage().contains("AXIOM_MIGRATION_DB_URL"));

        IllegalArgumentException blankPassword = assertThrows(IllegalArgumentException.class,
                () -> AxiomMigratorApplication.MigrationSettings.from(Map.of(
                        "AXIOM_MIGRATION_DB_URL", "jdbc:postgresql://localhost:5432/axiom",
                        "AXIOM_MIGRATION_DB_USERNAME", "axiom_migrator",
                        "AXIOM_MIGRATION_DB_PASSWORD", " ")));
        assertTrue(blankPassword.getMessage().contains("AXIOM_MIGRATION_DB_PASSWORD"));
    }

    @Test
    void migrationSettingsReadOnlyDedicatedEnvironmentNames() {
        var settings = AxiomMigratorApplication.MigrationSettings.from(Map.of(
                "AXIOM_MIGRATION_DB_URL", "jdbc:postgresql://localhost:5432/axiom",
                "AXIOM_MIGRATION_DB_USERNAME", "axiom_migrator",
                "AXIOM_MIGRATION_DB_PASSWORD", "local-only"));

        assertEquals("jdbc:postgresql://localhost:5432/axiom", settings.url());
        assertEquals("axiom_migrator", settings.username());
        assertEquals("local-only", settings.password());
    }
}
