"""Regression tests for immutable Flyway migration history enforcement."""

from __future__ import annotations

import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "backend" / "verify-migration-immutability.py"
SPEC = importlib.util.spec_from_file_location("verify_migration_immutability", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

MIGRATION_DIR = (
    "backend/axiom-parent/axiom-migrator/src/main/resources/db/migration"
)


class MigrationRepository:
    def __init__(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary_directory.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Axiom CI")
        self.git("config", "user.email", "axiom-ci@example.invalid")

    def close(self) -> None:
        self.temporary_directory.cleanup()

    def git(self, *arguments: str) -> str:
        result = subprocess.run(
            ["git", *arguments],
            cwd=self.path,
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()

    def write_migration(self, name: str, content: str) -> Path:
        path = self.path / MIGRATION_DIR / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")


class MigrationImmutabilityTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repository = MigrationRepository()
        self.repository.write_migration("V1__initial.sql", "CREATE SCHEMA axiom;\n")
        self.base = self.repository.commit("initial migration")

    def tearDown(self) -> None:
        self.repository.close()

    def verify(self, head: str) -> list[str]:
        return VERIFIER.verify_migration_history(self.repository.path, self.base, head)

    def test_new_versioned_migration_is_allowed(self) -> None:
        self.repository.write_migration("V2__add_party.sql", "CREATE TABLE axiom.party (id UUID);\n")
        head = self.repository.commit("add a new migration")
        self.assertEqual([], self.verify(head))

    def test_modifying_an_existing_migration_is_rejected(self) -> None:
        self.repository.write_migration("V1__initial.sql", "CREATE SCHEMA axiom AUTHORIZATION axiom_migrator;\n")
        head = self.repository.commit("rewrite an applied migration")
        errors = self.verify(head)
        self.assertEqual(1, len(errors))
        self.assertIn("immutable", errors[0])
        self.assertIn("V1__initial.sql", errors[0])

    def test_deleting_an_existing_migration_is_rejected(self) -> None:
        (self.repository.path / MIGRATION_DIR / "V1__initial.sql").unlink()
        head = self.repository.commit("delete an applied migration")
        errors = self.verify(head)
        self.assertEqual(1, len(errors))
        self.assertIn("immutable", errors[0])

    def test_renaming_an_existing_migration_is_rejected(self) -> None:
        self.repository.git(
            "mv",
            f"{MIGRATION_DIR}/V1__initial.sql",
            f"{MIGRATION_DIR}/V2__renamed_initial.sql",
        )
        head = self.repository.commit("rename an applied migration")
        errors = self.verify(head)
        self.assertEqual(1, len(errors))
        self.assertIn("rename", errors[0])

    def test_unrelated_file_changes_are_allowed(self) -> None:
        (self.repository.path / "README.md").write_text("Updated docs\n", encoding="utf-8")
        head = self.repository.commit("update unrelated documentation")
        self.assertEqual([], self.verify(head))

    def test_invalid_revision_fails_closed(self) -> None:
        errors = VERIFIER.verify_migration_history(
            self.repository.path, "not-a-commit", self.base
        )
        self.assertEqual(1, len(errors))
        self.assertIn("full Git commit SHA", errors[0])


if __name__ == "__main__":
    unittest.main()
