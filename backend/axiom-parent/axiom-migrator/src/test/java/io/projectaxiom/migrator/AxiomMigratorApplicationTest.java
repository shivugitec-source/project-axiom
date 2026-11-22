package io.projectaxiom.migrator;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AxiomMigratorApplicationTest {

    @Test
    void migrationSettingsRequireDedicatedConnectionValues() {
        assertThatThrownBy(() -> AxiomMigratorApplication.MigrationSettings.from(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AXIOM_MIGRATION_DB_URL");

        assertThatThrownBy(() -> AxiomMigratorApplication.MigrationSettings.from(Map.of(
                "AXIOM_MIGRATION_DB_URL", "jdbc:postgresql://localhost:5432/axiom",
                "AXIOM_MIGRATION_DB_USERNAME", "axiom_migrator",
                "AXIOM_MIGRATION_DB_PASSWORD", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AXIOM_MIGRATION_DB_PASSWORD");
    }

    @Test
    void migrationSettingsReadOnlyDedicatedEnvironmentNames() {
        var settings = AxiomMigratorApplication.MigrationSettings.from(Map.of(
                "AXIOM_MIGRATION_DB_URL", "jdbc:postgresql://localhost:5432/axiom",
                "AXIOM_MIGRATION_DB_USERNAME", "axiom_migrator",
                "AXIOM_MIGRATION_DB_PASSWORD", "local-only"));

        assertThat(settings.url()).isEqualTo("jdbc:postgresql://localhost:5432/axiom");
        assertThat(settings.username()).isEqualTo("axiom_migrator");
        assertThat(settings.password()).isEqualTo("local-only");
    }
}