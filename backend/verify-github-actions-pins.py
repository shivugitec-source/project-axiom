#!/usr/bin/env python3
"""Require immutable references for external actions used by GitHub workflows."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


USES_LINE = re.compile(r"^\s*(?:-\s*)?uses\s*:\s*(.*?)\s*$")
EXTERNAL_ACTION = re.compile(
    r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*@[0-9a-fA-F]{40}$"
)
DOCKER_ACTION = re.compile(r"^docker://[^@\s]+@sha256:[0-9a-fA-F]{64}$")


def normalize_reference(raw_value: str) -> str:
    """Remove a YAML inline comment and optional scalar quotes."""
    value = raw_value.split(" #", 1)[0].strip()
    if len(value) >= 2 and value[0] in {"'", '"'} and value[-1] == value[0]:
        value = value[1:-1]
    return value


def verify_workflows(workflow_directory: Path) -> list[str]:
    """Return violations for mutable or unsupported workflow action references."""
    if not workflow_directory.is_dir():
        return [f"workflow directory does not exist: {workflow_directory}"]

    workflow_files = sorted(
        path
        for pattern in ("*.yml", "*.yaml")
        for path in workflow_directory.rglob(pattern)
        if path.is_file()
    )
    if not workflow_files:
        return [f"no GitHub workflow YAML files found in {workflow_directory}"]

    violations: list[str] = []
    for workflow_file in workflow_files:
        try:
            lines = workflow_file.read_text(encoding="utf-8").splitlines()
        except (OSError, UnicodeError) as exception:
            violations.append(f"cannot read workflow {workflow_file}: {exception}")
            continue

        for line_number, line in enumerate(lines, start=1):
            match = USES_LINE.match(line)
            if match is None:
                continue

            reference = normalize_reference(match.group(1))
            if reference.startswith("./") and "@" not in reference:
                # Local actions and reusable workflows are tied to this repository revision.
                continue
            if EXTERNAL_ACTION.fullmatch(reference) or DOCKER_ACTION.fullmatch(reference):
                continue

            violations.append(
                f"{workflow_file}:{line_number}: external action must use a full "
                f"40-character commit SHA (or a container digest): {reference!r}"
            )

    return violations


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Require immutable GitHub Actions and container-action references."
    )
    parser.add_argument(
        "--workflow-directory",
        type=Path,
        default=Path(__file__).resolve().parents[1] / ".github" / "workflows",
        help="Directory containing GitHub workflow YAML files",
    )
    args = parser.parse_args()

    violations = verify_workflows(args.workflow_directory)
    if violations:
        for violation in violations:
            print(f"ERROR: {violation}", file=sys.stderr)
        return 1

    print("GitHub workflow action references use immutable revisions.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
