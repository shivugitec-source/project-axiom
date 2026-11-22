#!/usr/bin/env python3
"""Verify the packaged API and standalone Flyway migrator remain isolated."""

from __future__ import annotations

import argparse
import re
import sys
import zipfile
from pathlib import Path


FLYWAY_ARTIFACTS = (
    "BOOT-INF/lib/flyway-core-",
    "BOOT-INF/lib/flyway-database-postgresql-",
)
INTERNAL_MODULE_ARTIFACTS = (
    "BOOT-INF/lib/axiom-common-",
    "BOOT-INF/lib/axiom-core-",
    "BOOT-INF/lib/axiom-business-",
    "BOOT-INF/lib/axiom-api-",
    "BOOT-INF/lib/axiom-bootstrap-",
)
MIGRATION_RESOURCE = re.compile(
    r"^BOOT-INF/classes/db/migration/V([0-9]+(?:\\.[0-9]+)*)__[^/]+\\.sql$"
)
EXPECTED_MIGRATOR_MAIN = "io.projectaxiom.migrator.AxiomMigratorApplication"


def read_archive(path: Path) -> tuple[set[str], str, list[str]]:
    """Return archive members and manifest text, or a fail-closed error."""
    errors: list[str] = []
    try:
        with zipfile.ZipFile(path) as archive:
            corrupt_member = archive.testzip()
            if corrupt_member is not None:
                errors.append(f"{path}: corrupt archive member {corrupt_member}")
            names = set(archive.namelist())
            try:
                manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
            except KeyError:
                manifest = ""
                errors.append(f"{path}: missing META-INF/MANIFEST.MF")
    except (OSError, zipfile.BadZipFile) as error:
        return set(), "", [f"{path}: cannot read ZIP/JAR archive: {error}"]
    return names, manifest, errors


def manifest_attributes(manifest: str) -> dict[str, str]:
    """Parse main manifest attributes, including continuation lines."""
    unfolded = re.sub(r"\\r?\\n ", "", manifest)
    attributes: dict[str, str] = {}
    for line in unfolded.splitlines():
        if not line or line.startswith(" "):
            continue
        key, separator, value = line.partition(":")
        if separator:
            attributes[key.strip()] = value.strip()
    return attributes


def validate_artifacts(api_path: Path, migrator_path: Path) -> list[str]:
    errors: list[str] = []
    if api_path.resolve() == migrator_path.resolve():
        return ["API and migrator paths must identify different artifacts"]

    api_names, api_manifest, archive_errors = read_archive(api_path)
    errors.extend(archive_errors)
    migrator_names, migrator_manifest, archive_errors = read_archive(migrator_path)
    errors.extend(archive_errors)
    if errors or not api_names or not migrator_names:
        return errors

    api_flyway = sorted(
        name for name in api_names
        if any(name.startswith(prefix) for prefix in FLYWAY_ARTIFACTS)
    )
    if api_flyway:
        errors.append("API artifact contains Flyway libraries: " + ", ".join(api_flyway))

    api_migrations = sorted(
        name for name in api_names
        if name.startswith("BOOT-INF/classes/db/migration/")
        and name.endswith(".sql")
    )
    if api_migrations:
        errors.append("API artifact contains Flyway migration resources: " + ", ".join(api_migrations))

    migration_environment_references = []
    with zipfile.ZipFile(api_path) as archive:
        for name in sorted(api_names):
            if name.startswith("BOOT-INF/classes/"):
                try:
                    if b"AXIOM_MIGRATION_DB_" in archive.read(name):
                        migration_environment_references.append(name)
                except (KeyError, OSError, RuntimeError):
                    errors.append(f"API artifact resource cannot be inspected: {name}")
    if migration_environment_references:
        errors.append(
            "API artifact references migration-only environment variables: "
            + ", ".join(migration_environment_references)
        )

    migrator_flyway = {
        prefix: sorted(name for name in migrator_names if name.startswith(prefix))
        for prefix in FLYWAY_ARTIFACTS
    }
    for prefix, matches in migrator_flyway.items():
        if len(matches) != 1:
            errors.append(
                f"Migrator artifact must contain exactly one {prefix.removeprefix('BOOT-INF/lib/')} library; "
                f"found {len(matches)}"
            )

    migration_versions: dict[str, str] = {}
    migration_files = sorted(
        name for name in migrator_names if MIGRATION_RESOURCE.fullmatch(name)
    )
    if not migration_files:
        errors.append("Migrator artifact contains no versioned SQL migration resources")
    for name in migration_files:
        match = MIGRATION_RESOURCE.fullmatch(name)
        assert match is not None
        version = match.group(1)
        if version in migration_versions:
            errors.append(
                f"Migrator artifact contains duplicate migration version {version}: "
                f"{migration_versions[version]} and {name}"
            )
        migration_versions[version] = name

    misplaced_migrations = sorted(
        name for name in migrator_names
        if name.startswith("BOOT-INF/classes/db/migration/")
        and name.endswith(".sql")
        and name not in migration_files
    )
    if misplaced_migrations:
        errors.append(
            "Migrator contains SQL resources that are not versioned Flyway migrations: "
            + ", ".join(misplaced_migrations)
        )

    internal_dependencies = sorted(
        name for name in migrator_names
        if any(name.startswith(prefix) for prefix in INTERNAL_MODULE_ARTIFACTS)
    )
    if internal_dependencies:
        errors.append(
            "Migrator artifact must not depend on API modules: " + ", ".join(internal_dependencies)
        )

    attributes = manifest_attributes(migrator_manifest)
    if attributes.get("Start-Class") != EXPECTED_MIGRATOR_MAIN:
        errors.append(
            "Migrator Start-Class must be "
            f"{EXPECTED_MIGRATOR_MAIN}; found {attributes.get('Start-Class', '<missing>')}"
        )

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Fail if the API and standalone Flyway migrator packaging boundaries drift."
    )
    parser.add_argument("api_jar", type=Path, help="Packaged axiom-bootstrap executable JAR")
    parser.add_argument("migrator_jar", type=Path, help="Packaged axiom-migrator executable JAR")
    args = parser.parse_args()

    errors = validate_artifacts(args.api_jar, args.migrator_jar)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Packaged API/migrator boundary verified:")
    print("- API contains no Flyway libraries, migration SQL or migration credentials")
    print("- standalone migrator contains Flyway, versioned migrations and the expected entry point")
    print("- standalone migrator has no internal API module dependencies")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
