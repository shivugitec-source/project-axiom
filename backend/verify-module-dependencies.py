#!/usr/bin/env python3
"""Verify the declared Maven dependency direction of the Axiom modular monolith."""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
POM_ROOT = ROOT / "backend" / "axiom-parent"
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


def parse_pom(path: Path) -> ET.Element:
    try:
        return ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ValueError(f"Cannot parse {path.relative_to(ROOT)}: {exc}") from exc


def child_text(element: ET.Element, name: str) -> str | None:
    value = element.findtext(f"m:{name}", namespaces=MAVEN_NS)
    return value.strip() if value else None


def main() -> int:
    errors: list[str] = []
    parent_pom = parse_pom(POM_ROOT / "pom.xml")
    declared_modules = tuple(
        (module.text or "").strip()
        for module in parent_pom.findall("./m:modules/m:module", MAVEN_NS)
    )

    if declared_modules != EXPECTED_MODULES:
        errors.append(
            "Parent module order/set differs from the approved graph: "
            f"expected {EXPECTED_MODULES}, found {declared_modules}"
        )

    for module in EXPECTED_MODULES:
        pom_path = POM_ROOT / module / "pom.xml"
        if not pom_path.is_file():
            errors.append(f"Missing module POM: {pom_path.relative_to(ROOT)}")
            continue

        pom = parse_pom(pom_path)
        artifact_id = child_text(pom, "artifactId")
        if artifact_id != module:
            errors.append(
                f"{pom_path.relative_to(ROOT)} declares artifactId "
                f"{artifact_id!r}, expected {module!r}"
            )

        internal_dependencies: list[str] = []
        for dependency in pom.findall("./m:dependencies/m:dependency", MAVEN_NS):
            if child_text(dependency, "groupId") == INTERNAL_GROUP:
                artifact = child_text(dependency, "artifactId")
                if artifact:
                    internal_dependencies.append(artifact)

        if len(internal_dependencies) != len(set(internal_dependencies)):
            errors.append(
                f"{module} declares duplicate internal dependencies: "
                f"{internal_dependencies}"
            )

        actual = set(internal_dependencies)
        expected = ALLOWED_INTERNAL_DEPENDENCIES[module]
        if actual != expected:
            errors.append(
                f"{module} internal dependencies must be {sorted(expected)}, "
                f"found {sorted(actual)}"
            )

    if errors:
        print("Modular-monolith dependency verification FAILED:", file=sys.stderr)
        for error in errors:
            print(f" - {error}", file=sys.stderr)
        return 1

    print("Modular-monolith dependency boundaries verified:")
    for module in EXPECTED_MODULES:
        dependencies = sorted(ALLOWED_INTERNAL_DEPENDENCIES[module])
        print(f" - {module}: {', '.join(dependencies) if dependencies else '(no internal dependencies)'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
