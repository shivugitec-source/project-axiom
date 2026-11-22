"""Regression tests for the modular-monolith dependency verifier."""

from pathlib import Path
import importlib.util
import tempfile
import unittest

BACKEND_DIR = Path(__file__).resolve().parents[1]
VERIFIER_PATH = BACKEND_DIR / "verify-module-dependencies.py"
SPEC = importlib.util.spec_from_file_location("verify_module_dependencies", VERIFIER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load dependency verifier from {VERIFIER_PATH}")
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)


MAVEN_NAMESPACE = "http://maven.apache.org/POM/4.0.0"


def dependency(artifact_id: str) -> str:
    return (
        "<dependency><groupId>io.projectaxiom</groupId>"
        f"<artifactId>{artifact_id}</artifactId><version>0.1.0-SNAPSHOT</version>"
        "</dependency>"
    )


def profile(profile_id: str, dependencies: list[str]) -> str:
    entries = "".join(dependency(artifact) for artifact in dependencies)
    return (
        "<profile><id>"
        f"{profile_id}"
        "</id><dependencies>"
        f"{entries}"
        "</dependencies></profile>"
    )


class VerifyModuleDependenciesTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_fixture(
        self,
        module_dependencies: dict[str, list[str]] | None = None,
        module_profiles: dict[str, list[str]] | None = None,
        parent_dependencies: list[str] | None = None,
        parent_profiles: list[str] | None = None,
        modules: tuple[str, ...] | None = None,
        missing_modules: set[str] | None = None,
        artifact_overrides: dict[str, str] | None = None,
        malformed_module: str | None = None,
        malformed_parent: bool = False,
    ) -> None:
        module_dependencies = module_dependencies or {}
        module_profiles = module_profiles or {}
        parent_dependencies = parent_dependencies or []
        parent_profiles = parent_profiles or []
        modules = modules or verifier.EXPECTED_MODULES
        missing_modules = missing_modules or set()
        artifact_overrides = artifact_overrides or {}

        pom_root = self.root / verifier.POM_ROOT
        pom_root.mkdir(parents=True, exist_ok=True)

        if malformed_parent:
            (pom_root / "pom.xml").write_text("<project><modules>", encoding="utf-8")
            return

        parent_deps = "".join(dependency(item) for item in parent_dependencies)
        parent_profile_xml = "".join(
            profile(f"parent-{index}", [artifact])
            for index, artifact in enumerate(parent_profiles)
        )
        module_xml = "".join(f"<module>{item}</module>" for item in modules)
        parent_pom = (
            f'<project xmlns="{MAVEN_NAMESPACE}">'
            "<modelVersion>4.0.0</modelVersion>"
            "<groupId>io.projectaxiom</groupId><artifactId>axiom-parent</artifactId>"
            "<version>0.1.0-SNAPSHOT</version><packaging>pom</packaging>"
            f"<modules>{module_xml}</modules>"
            f"<dependencies>{parent_deps}</dependencies>"
            f"<profiles>{parent_profile_xml}</profiles>"
            "</project>"
        )
        (pom_root / "pom.xml").write_text(parent_pom, encoding="utf-8")

        for module in verifier.EXPECTED_MODULES:
            if module in missing_modules:
                continue
            if module == malformed_module:
                (pom_root / module).mkdir(parents=True, exist_ok=True)
                (pom_root / module / "pom.xml").write_text(
                    "<project><artifactId>", encoding="utf-8"
                )
                continue

            deps = "".join(
                dependency(item) for item in module_dependencies.get(module, [])
            )
            profiles = "".join(
                profile(f"profile-{index}", [artifact])
                for index, artifact in enumerate(module_profiles.get(module, []))
            )
            artifact_id = artifact_overrides.get(module, module)
            pom = (
                f'<project xmlns="{MAVEN_NAMESPACE}">'
                "<modelVersion>4.0.0</modelVersion>"
                f"<artifactId>{artifact_id}</artifactId>"
                f"<dependencies>{deps}</dependencies>"
                f"<profiles>{profiles}</profiles>"
                "</project>"
            )
            module_dir = pom_root / module
            module_dir.mkdir(parents=True, exist_ok=True)
            (module_dir / "pom.xml").write_text(pom, encoding="utf-8")

    def test_approved_dependency_graph_passes(self) -> None:
        self.write_fixture(
            module_dependencies={
                module: sorted(dependencies)
                for module, dependencies in verifier.ALLOWED_INTERNAL_DEPENDENCIES.items()
            }
        )
        self.assertEqual([], verifier.verify_repository(self.root))

    def test_declared_maven_module_sources_are_allowed(self) -> None:
        self.write_fixture()
        source = (
            self.root / verifier.POM_ROOT / "axiom-migrator"
            / "src" / "test" / "java" / "ExampleTest.java"
        )
        source.parent.mkdir(parents=True)
        source.write_text("class ExampleTest {}", encoding="utf-8")
        self.assertEqual([], verifier.verify_repository(self.root))

    def test_unmanaged_source_and_resource_trees_fail(self) -> None:
        self.write_fixture()
        orphan_root = self.root / "backend" / "axiom-migrator" / "src"
        java_source = orphan_root / "test" / "java" / "ExampleTest.java"
        migration = orphan_root / "main" / "resources" / "db" / "migration" / "V1__old.sql"
        java_source.parent.mkdir(parents=True)
        migration.parent.mkdir(parents=True)
        java_source.write_text("class ExampleTest {}", encoding="utf-8")
        migration.write_text("CREATE SCHEMA axiom;", encoding="utf-8")

        errors = verifier.verify_repository(self.root)
        unmanaged = [error for error in errors if "outside a declared Maven module" in error]
        self.assertEqual(2, len(unmanaged))
        self.assertTrue(any("ExampleTest.java" in error for error in unmanaged))
        self.assertTrue(any("V1__old.sql" in error for error in unmanaged))

    def test_migrator_cannot_depend_on_api_or_bootstrap(self) -> None:
        self.write_fixture(module_dependencies={"axiom-migrator": ["axiom-bootstrap"]})
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("axiom-migrator" in error for error in errors))

    def test_reverse_dependency_fails(self) -> None:
        self.write_fixture(module_dependencies={"axiom-common": ["axiom-core"]})
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("axiom-common" in error for error in errors))

    def test_unknown_internal_dependency_fails(self) -> None:
        self.write_fixture(module_dependencies={"axiom-api": ["axiom-bootstrap"]})
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("axiom-api" in error for error in errors))

    def test_duplicate_dependency_in_one_container_fails(self) -> None:
        self.write_fixture(
            module_dependencies={"axiom-core": ["axiom-common", "axiom-common"]}
        )
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("duplicate internal dependencies" in error for error in errors))

    def test_parent_inherited_internal_dependency_fails(self) -> None:
        self.write_fixture(parent_dependencies=["axiom-common"])
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("Parent POM declares internal dependencies" in error for error in errors))

    def test_profile_cannot_introduce_reverse_dependency(self) -> None:
        self.write_fixture(module_profiles={"axiom-common": ["axiom-bootstrap"]})
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("axiom-common" in error for error in errors))

    def test_parent_profile_cannot_introduce_inherited_dependency(self) -> None:
        self.write_fixture(parent_profiles=["axiom-common"])
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("Parent POM declares internal dependencies" in error for error in errors))

    def test_missing_module_pom_fails(self) -> None:
        self.write_fixture(missing_modules={"axiom-api"})
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("Missing module POM" in error for error in errors))

    def test_wrong_artifact_id_fails(self) -> None:
        self.write_fixture(artifact_overrides={"axiom-core": "wrong-artifact"})
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("axiom-core" in error and "artifactId" in error for error in errors))

    def test_parent_module_order_must_match_approved_order(self) -> None:
        self.write_fixture(modules=tuple(reversed(verifier.EXPECTED_MODULES)))
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("Parent module order/set" in error for error in errors))

    def test_malformed_parent_pom_fails_cleanly(self) -> None:
        self.write_fixture(malformed_parent=True)
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("Cannot parse" in error for error in errors))

    def test_malformed_module_pom_fails_cleanly(self) -> None:
        self.write_fixture(malformed_module="axiom-api")
        errors = verifier.verify_repository(self.root)
        self.assertTrue(any("Cannot parse" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
