"""Regression tests for the API/migration process boundary."""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
BOOTSTRAP = ROOT / "backend" / "axiom-parent" / "axiom-bootstrap"
MIGRATOR = ROOT / "backend" / "axiom-parent" / "axiom-migrator"


class MigrationProcessIsolationTests(unittest.TestCase):
    def test_api_artifact_has_no_flyway_dependency_or_configuration(self) -> None:
        bootstrap_pom = (BOOTSTRAP / "pom.xml").read_text(encoding="utf-8")
        application_config = (
            BOOTSTRAP / "src" / "main" / "resources" / "application.yml"
        ).read_text(encoding="utf-8")

        self.assertNotIn("flyway", bootstrap_pom.lower())
        self.assertNotIn("flyway", application_config.lower())
        self.assertNotIn("AXIOM_MIGRATION_DB_", application_config)

    def test_migration_artifact_owns_flyway_and_all_migration_resources(self) -> None:
        migrator_pom = (MIGRATOR / "pom.xml").read_text(encoding="utf-8")
        migration_files = sorted(
            path.name
            for path in (
                MIGRATOR / "src" / "main" / "resources" / "db" / "migration"
            ).glob("V*.sql")
        )
        old_location = (
            BOOTSTRAP / "src" / "main" / "resources" / "db" / "migration"
        )

        self.assertIn("flyway-core", migrator_pom)
        self.assertIn("flyway-database-postgresql", migrator_pom)
        self.assertEqual(
            [
                "V1__create_platform_schema.sql",
                "V2__create_tenant_and_organization.sql",
                "V3__create_party_foundation.sql",
                "V4__create_identity_and_access_foundation.sql",
                "V5__create_party_contact_points.sql",
                "V6__separate_migration_and_runtime_roles.sql",
            ],
            migration_files,
        )
        self.assertEqual([], list(old_location.glob("V*.sql")))

    def test_migration_role_has_schema_ddl_but_no_database_ddl(self) -> None:
        init_sql = (
            ROOT / "deployment" / "compose" / "init" / "01-create-app-user.sql"
        ).read_text(encoding="utf-8")
        workflow = (
            ROOT / ".github" / "workflows" / "backend-ci.yml"
        ).read_text(encoding="utf-8")
        migrator_source = (
            MIGRATOR / "src" / "main" / "java" / "io" / "projectaxiom"
            / "migrator" / "AxiomMigratorApplication.java"
        ).read_text(encoding="utf-8")

        self.assertNotIn(
            "GRANT CREATE ON DATABASE axiom TO axiom_migrator",
            init_sql + workflow,
        )
        self.assertIn(
            "NOT has_database_privilege(current_user, current_database(), 'CREATE')",
            migrator_source,
        )
        self.assertIn('.baselineVersion("1")', migrator_source)
        self.assertIn("schemaHistoryExists(connection)", migrator_source)

    def test_ci_scopes_migration_credentials_to_the_one_shot_step(self) -> None:
        workflow = (
            ROOT / ".github" / "workflows" / "backend-ci.yml"
        ).read_text(encoding="utf-8")
        job_configuration = workflow.split("    steps:", maxsplit=1)[0]
        migration_step = workflow.split(
            "- name: Run Flyway in a dedicated migration process", maxsplit=1
        )[1].split("- name:", maxsplit=1)[0]
        runtime_step = workflow.split(
            "- name: Verify backend with runtime credentials only", maxsplit=1
        )[1]

        self.assertNotIn("AXIOM_MIGRATION_DB_", job_configuration)
        self.assertIn("AXIOM_MIGRATION_DB_URL", migration_step)
        self.assertIn("AXIOM_MIGRATION_DB_USERNAME", migration_step)
        self.assertIn("AXIOM_MIGRATION_DB_PASSWORD", migration_step)
        self.assertNotIn("AXIOM_MIGRATION_DB_", runtime_step)
        self.assertIn("AXIOM_DB_USERNAME", runtime_step)
        self.assertIn("AXIOM_DB_PASSWORD", runtime_step)


if __name__ == "__main__":
    unittest.main()
