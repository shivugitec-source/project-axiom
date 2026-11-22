"""Tests for the packaged API/migrator boundary verifier."""

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "backend" / "verify-migration-artifacts.py"
SPEC = importlib.util.spec_from_file_location("verify_migration_artifacts", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


API_MANIFEST = "Manifest-Version: 1.0\nStart-Class: io.projectaxiom.bootstrap.AxiomApplication\n\n"
MIGRATOR_MANIFEST = (
    "Manifest-Version: 1.0\n"
    "Start-Class: io.projectaxiom.migrator.AxiomMigratorApplication\n\n"
)
FLYWAY_CORE = "BOOT-INF/lib/flyway-core-11.0.0.jar"
FLYWAY_POSTGRES = "BOOT-INF/lib/flyway-database-postgresql-11.0.0.jar"
MIGRATION_V1 = "BOOT-INF/classes/db/migration/V1__create_platform_schema.sql"
MIGRATION_V2 = "BOOT-INF/classes/db/migration/V2__create_tenant.sql"


class PackagedMigrationArtifactTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.api_jar = self.root / "axiom-bootstrap.jar"
        self.migrator_jar = self.root / "axiom-migrator.jar"
        self.api_members = {
            "META-INF/MANIFEST.MF": API_MANIFEST.encode(),
            "BOOT-INF/classes/application.yml": b"spring:\n  application:\n    name: axiom-api\n",
            "BOOT-INF/lib/spring-webmvc.jar": b"dependency",
        }
        self.migrator_members = {
            "META-INF/MANIFEST.MF": MIGRATOR_MANIFEST.encode(),
            FLYWAY_CORE: b"dependency",
            FLYWAY_POSTGRES: b"dependency",
            "BOOT-INF/lib/postgresql-42.7.7.jar": b"dependency",
            MIGRATION_V1: b"CREATE SCHEMA axiom;",
            MIGRATION_V2: b"CREATE TABLE axiom.tenant (id UUID);",
        }
        self.write_archives()

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_archives(self) -> None:
        for path, members in (
            (self.api_jar, self.api_members),
            (self.migrator_jar, self.migrator_members),
        ):
            with zipfile.ZipFile(path, "w") as archive:
                for name, content in members.items():
                    archive.writestr(name, content)

    def errors(self) -> list[str]:
        return VERIFIER.validate_artifacts(self.api_jar, self.migrator_jar)

    def test_accepts_separate_api_and_migrator_archives(self) -> None:
        self.assertEqual([], self.errors())

    def test_rejects_flyway_core_in_api_archive(self) -> None:
        self.api_members[FLYWAY_CORE] = b"dependency"
        self.write_archives()
        self.assertTrue(any("API artifact contains Flyway libraries" in e for e in self.errors()))

    def test_rejects_postgresql_flyway_adapter_in_api_archive(self) -> None:
        self.api_members[FLYWAY_POSTGRES] = b"dependency"
        self.write_archives()
        self.assertTrue(any("API artifact contains Flyway libraries" in e for e in self.errors()))

    def test_rejects_migration_sql_in_api_archive(self) -> None:
        self.api_members[MIGRATION_V1] = b"CREATE SCHEMA axiom;"
        self.write_archives()
        self.assertTrue(any("API artifact contains Flyway migration resources" in e for e in self.errors()))

    def test_rejects_migration_environment_reference_in_api_archive(self) -> None:
        self.api_members["BOOT-INF/classes/application.yml"] = (
            b"migration: ${AXIOM_MIGRATION_DB_PASSWORD}\n"
        )
        self.write_archives()
        self.assertTrue(any("migration-only environment variables" in e for e in self.errors()))

    def test_requires_flyway_core_in_migrator_archive(self) -> None:
        del self.migrator_members[FLYWAY_CORE]
        self.write_archives()
        self.assertTrue(any("exactly one flyway-core" in e for e in self.errors()))

    def test_requires_postgresql_flyway_adapter_in_migrator_archive(self) -> None:
        del self.migrator_members[FLYWAY_POSTGRES]
        self.write_archives()
        self.assertTrue(any("exactly one flyway-database-postgresql" in e for e in self.errors()))

    def test_requires_versioned_migration_resources(self) -> None:
        del self.migrator_members[MIGRATION_V1]
        del self.migrator_members[MIGRATION_V2]
        self.write_archives()
        self.assertTrue(any("contains no versioned SQL migration" in e for e in self.errors()))

    def test_rejects_duplicate_flyway_versions(self) -> None:
        self.migrator_members["BOOT-INF/classes/db/migration/V1__duplicate.sql"] = b"-- duplicate"
        self.write_archives()
        self.assertTrue(any("duplicate migration version 1" in e for e in self.errors()))

    def test_rejects_unversioned_sql_resources(self) -> None:
        self.migrator_members["BOOT-INF/classes/db/migration/R__reference_data.sql"] = b"-- repeatable"
        self.write_archives()
        self.assertTrue(any("not versioned Flyway migrations" in e for e in self.errors()))

    def test_rejects_internal_module_dependencies_in_migrator(self) -> None:
        self.migrator_members["BOOT-INF/lib/axiom-api-0.1.0.jar"] = b"dependency"
        self.write_archives()
        self.assertTrue(any("must not depend on API modules" in e for e in self.errors()))

    def test_requires_expected_migrator_entry_point(self) -> None:
        self.migrator_members["META-INF/MANIFEST.MF"] = (
            b"Manifest-Version: 1.0\nStart-Class: io.projectaxiom.api.ApiApplication\n\n"
        )
        self.write_archives()
        self.assertTrue(any("Migrator Start-Class must be" in e for e in self.errors()))

    def test_fails_closed_for_non_jar_input(self) -> None:
        self.api_jar.write_text("not a jar", encoding="utf-8")
        self.assertTrue(any("cannot read ZIP/JAR archive" in e for e in self.errors()))

    def test_rejects_same_path_for_both_artifacts(self) -> None:
        self.assertIn(
            "API and migrator paths must identify different artifacts",
            VERIFIER.validate_artifacts(self.api_jar, self.api_jar),
        )


if __name__ == "__main__":
    unittest.main()
