#!/usr/bin/env python3
"""Verify the declared Maven dependency direction of the Axiom modular monolith."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
POM_ROOT = Path("backend") / "axiom-parent"
MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
INTERNAL_GROUP = "io.projectaxiom"

EXPECTED_MODULES = (
    "axiom-common",
    "axiom-core",
    "axiom-business",
    "axiom-api",
    "axiom-bootstrap",
)

# Dependencies point inward only: common <- core <- business <- api <- bootstrap.
# A change to this graph must be deliberate and reviewed as an architecture change.
ALLOWED_INTERNAL_DEPENDENCIES = {
    "axiom-common": set(),
    "axiom-core": {"axiom-common"},
    "axiom-business": {"axiom-core"},
    "axiom-api": {"axiom-business"},
    "axiom-bootstrap": {"axiom-api"},
}


def parse_pom(path: Path, repository_root: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        try:
            display_path = path.relative_to(repository_root)
        except ValueError:
            display_path = path
        raise ValueError(f"Cannot parse {display_path}: {exc}") from exc


def child_text(element: ET.Element, name: str) -> str | None:
    value = element.findtext(f"m:{name}", namespaces=MAVEN_NS)
    return value.strip() if value else None


def dependency_containers(pom: ET.Element) -> list[tuple[str, ET.Element]]:
    """Return project and profile dependency containers.

    All profile declarations are checked, including currently inactive profiles,
    so activating a profile cannot silently introduce a reverse module edge.
    dependencyManagement is intentionally excluded: it manages versions but does
    not add a dependency to a module's classpath by itself.
    """
    containers: list[tuple[str, ET.Element]] = []
    direct = pom.find("m:dependencies", MAVEN_NS)
    if direct is not None:
        containers.append(("project dependencies", direct))

    profiles = pom.find("m:profiles", MAVEN_NS)
    if profiles is not None:
        for profile in profiles.findall("m:profile", MAVEN_NS):
            profile_id = child_text(profile, "id") or "(unnamed)"
            dependencies = profile.find("m:dependencies", MAVEN_NS)
            if dependencies is not None:
                containers.append((f"profile {profile_id!r} dependencies", dependencies))
    return containers


def internal_dependencies(
    pom: ET.Element, owner: str
) -> tuple[list[str], list[str]]:
    found: list[str] = []
    errors: list[str] = []

    for container_name, container in dependency_containers(pom):
        declared_here: list[str] = []
        for dependency in container.findall("m:dependency", MAVEN_NS):
            if child_text(dependency, "groupId") != INTERNAL_GROUP:
                continue
            artifact = child_text(dependency, "artifactId")
            if not artifact:
                errors.append(
                    f"{owner} {container_name} declares an internal dependency "
                    "without an artifactId"
                )
                continue
            declared_here.append(artifact)
            found.append(artifact)

        if len(declared_here) != len(set(declared_here)):
            errors.append(
                f"{owner} {container_name} declares duplicate internal dependencies: "
                f"{declared_here}"
            )

    return found, errors


def verify_repository(repository_root: Path) -> list[str]:
    errors: list[str] = []
    pom_root = repository_root / POM_ROOT
    parent_path = pom_root / "pom.xml"

    try:
        parent_pom = parse_pom(parent_path, repository_root)
    except ValueError as exc:
        return [str(exc)]

    declared_modules = tuple(
        (module.text or "").strip()
        for module in parent_pom.findall("./m:modules/m:module", MAVEN_NS)
    )
    if declared_modules != EXPECTED_MODULES:
        errors.append(
            "Parent module order/set differs from the approved graph: "
            f"expected {EXPECTED_MODULES}, found {declared_modules}"
        )

    inherited, parent_errors = internal_dependencies(parent_pom, "Parent POM")
    errors.extend(parent_errors)
    if inherited:
        errors.append(
            "Parent POM declares internal dependencies inherited by child modules: "
            f"{sorted(set(inherited))}"
        )

    for module in EXPECTED_MODULES:
        pom_path = pom_root / module / "pom.xml"
        if not pom_path.is_file():
            errors.append(f"Missing module POM: {pom_path.relative_to(repository_root)}")
            continue

        try:
            pom = parse_pom(pom_path, repository_root)
        except ValueError as exc:
            errors.append(str(exc))
            continue

        artifact_id = child_text(pom, "artifactId")
        if artifact_id != module:
            errors.append(
                f"{pom_path.relative_to(repository_root)} declares artifactId "
                f"{artifact_id!r}, expected {module!r}"
            )

        actual_dependencies, dependency_errors = internal_dependencies(pom, module)
        errors.extend(dependency_errors)
        actual = set(actual_dependencies)
        expected = ALLOWED_INTERNAL_DEPENDENCIES[module]
        if actual != expected:
            errors.append(
                f"{module} internal dependencies must be {sorted(expected)}, "
                f"found {sorted(actual)}"
            )

    return errors


def main() -> int:
    errors = verify_repository(ROOT)
    if errors:
        print("Modular-monolith dependency verification FAILED:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    print("Modular-monolith dependency boundaries verified:")
    for module in EXPECTED_MODULES:
        dependencies = sorted(ALLOWED_INTERNAL_DEPENDENCIES[module])
        print(
            f" - {module}: "
            f"{', '.join(dependencies) if dependencies else '(no internal dependencies)'}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
